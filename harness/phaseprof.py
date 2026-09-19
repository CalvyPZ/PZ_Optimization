#!/usr/bin/env python3
"""Where a thread spends a window of a run, from the JFR samples: boot and load profiling.

  harness/phaseprof.py <run-dir> [--thread MainThread] [--from REGEX] [--to REGEX] [--top 40]
                       [--depth-of pkg.Class.method] [--min-share 1.0] [--buckets 0.25]

The window is [first trace line matching --from, first later line matching --to) on the
run's pzopt-loadtrace.out (default: launch -> "continuing latest save", i.e. the boot).
Samples are read from samples.tsv (made from pzopt.jfr by tools/JfrSamples.java, as
attribute.py does). Two rankings for the thread's samples in the window:

  inclusive   share of samples whose stack contains the method (game frames only:
              zombie.*, fmod.*, se.krka.*, org.lwjgl*), so a phase method's share is its wall share
  leaf        share by the innermost game frame (what the code was doing itself)

--depth-of M lists the callees of M (the frame just above M) ranked, to drill one level.
--buckets S prints, per S-second slice of the window, the dominant inclusive game frame at the
outermost interesting depth (the frame two above the thread's root), as a timeline.

Wall clock is used for the window; the samples' epoch is JFR's own, which is the same clock.
"""
import argparse
import re
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
GAME = ("zombie.", "fmod.", "se.krka.", "org.lwjgl", "pzopt.")


def trace_rows(run):
    p = Path(run) / "pzopt-loadtrace.out"
    rows = []
    with p.open(errors="replace") as f:
        for line in f:
            t, _, text = line.rstrip("\n").partition("\t")
            if t.isdigit():
                rows.append((int(t), text))
    return rows


def window(run, frm, to):
    rows = trace_rows(run)
    i = 0
    if frm:
        rx = re.compile(frm)
        i = next((k for k, r in enumerate(rows) if rx.search(r[1])), None)
        if i is None:
            sys.exit(f"--from {frm!r} not found in the trace")
    rx = re.compile(to)
    j = next((k for k in range(i + 1, len(rows)) if rx.search(rows[k][1])), None)
    if j is None:
        sys.exit(f"--to {to!r} not found in the trace")
    return rows[i][0], rows[j][0]


def samples(run, thread, t0_ms, t1_ms):
    tsv = Path(run) / "samples.tsv"
    jfr = Path(run) / "pzopt.jfr"
    if not tsv.exists() or tsv.stat().st_mtime < jfr.stat().st_mtime:
        with tsv.open("w") as out:
            subprocess.run(["java", str(REPO / "tools" / "JfrSamples.java"), str(jfr)], stdout=out, check=True)
    out = []
    with tsv.open() as f:
        next(f)
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if parts[0] not in ("java", "native") or parts[1] != thread:
                continue
            ms = int(parts[2]) // 1_000_000
            if ms < t0_ms or ms >= t1_ms:
                continue
            out.append((ms, parts[-1].split(";")))
    return out


def is_game(fr):
    return fr.startswith(GAME)


def short(fr):
    parts = fr.split(".")
    return ".".join(parts[-2:]) if len(parts) > 2 else fr


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--thread", default="MainThread")
    ap.add_argument("--from", dest="frm", default=None)
    ap.add_argument("--to", default=r"continuing latest save")
    ap.add_argument("--top", type=int, default=40)
    ap.add_argument("--min-share", type=float, default=1.0)
    ap.add_argument("--depth-of", default=None)
    ap.add_argument("--buckets", type=float, default=0)
    ap.add_argument("--root-depth", type=int, default=2, help="for --buckets: frame index from the root to report")
    a = ap.parse_args()

    t0, t1 = window(a.run, a.frm, a.to)
    smp = samples(a.run, a.thread, t0, t1)
    n = len(smp)
    print(f"{a.run}: thread {a.thread}, window {(t1 - t0) / 1000:.2f} s, {n} samples "
          f"({n / max(1, (t1 - t0)):.2f} per ms)")
    if not n:
        return
    incl, leaf = Counter(), Counter()
    for _, frames in smp:
        seen = set()
        for fr in frames:
            if is_game(fr) and fr not in seen:
                seen.add(fr)
                incl[fr] += 1
        lf = next((fr for fr in frames if is_game(fr)), frames[0])
        leaf[lf] += 1
    print(f"\ninclusive (share of the window's samples whose stack contains the method), top {a.top}:")
    for fr, c in incl.most_common(a.top):
        share = 100.0 * c / n
        if share < a.min_share:
            break
        print(f"  {share:5.1f}%  {(t1 - t0) * c / n / 1000:5.2f}s  {fr}")
    print(f"\nleaf (innermost game frame), top {a.top // 2}:")
    for fr, c in leaf.most_common(a.top // 2):
        share = 100.0 * c / n
        if share < a.min_share:
            break
        print(f"  {share:5.1f}%  {(t1 - t0) * c / n / 1000:5.2f}s  {fr}")
    if a.depth_of:
        callee = Counter()
        tot = 0
        for _, frames in smp:
            for i, fr in enumerate(frames):
                if fr == a.depth_of or fr.startswith(a.depth_of + "("):
                    tot += 1
                    callee[frames[i - 1] if i >= 1 else "(self)"] += 1
                    break
        print(f"\ncallees of {a.depth_of} ({tot} samples):")
        for fr, c in callee.most_common(a.top):
            print(f"  {100.0 * c / max(1, tot):5.1f}%  {(t1 - t0) * c / n / 1000:5.2f}s  {fr}")
    if a.buckets:
        step = int(a.buckets * 1000)
        b = defaultdict(Counter)
        for ms, frames in smp:
            game = [fr for fr in frames if is_game(fr)]
            key = game[-1 - a.root_depth] if len(game) > a.root_depth else (game[0] if game else frames[0])
            b[(ms - t0) // step][key] += 1
        print(f"\ntimeline ({a.buckets} s buckets, frame {a.root_depth} above the outermost game frame):")
        for k in sorted(b):
            tot = sum(b[k].values())
            top = "; ".join(f"{short(fr)}={c}" for fr, c in b[k].most_common(3))
            print(f"  {k * a.buckets:6.2f}s  n={tot:4d}  {top}")


if __name__ == "__main__":
    main()
