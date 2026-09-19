#!/usr/bin/env python3
"""Where each thread blocks during the route, from the JFR wait events.

  harness/waits.py <run-dir> [--threads MainThread,main,...] [--top 12]

Needs a run made with --jfr and the wait thresholds lowered, e.g.
  --jfr-setting 'jdk.JavaMonitorWait#threshold=0ms' --jfr-setting 'jdk.ThreadPark#threshold=0ms'
  --jfr-setting 'jdk.JavaMonitorEnter#threshold=0ms'
tools/JfrSamples.java dumps them as "wait" lines (thread, start, duration, event, stack)
and this script sums, per thread and per blocking site (first game frame under the
wait), the time blocked inside the route window, as a share of the window.

The point: a frame that is shorter than the sum of the two threads' CPU time means
the threads are serialised somewhere; the sites below are where.
"""
import argparse
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from attribute import REPO, load_frames, short  # noqa: E402

SKIP = ("java.lang.Object.wait", "java.lang.Object.wait0", "java.util.concurrent.locks.LockSupport.park",
        "java.util.concurrent.locks.LockSupport.parkNanos", "jdk.internal.misc.Unsafe.park",
        "java.util.concurrent.locks.AbstractQueuedSynchronizer")


def site(frames):
    """The first frame that is not the wait primitive itself, plus its two callers."""
    keep = [f for f in frames if not f.startswith(SKIP)]
    return " < ".join(short(f) for f in keep[:3]) if keep else (frames[0] if frames else "?")


def load_waits(run):
    run = Path(run)
    tsv = run / "samples.tsv"
    jfr = run / "pzopt.jfr"
    if not tsv.exists() or tsv.stat().st_mtime < jfr.stat().st_mtime:
        with tsv.open("w") as out:
            subprocess.run(["java", str(REPO / "tools" / "JfrSamples.java"), str(jfr)], stdout=out, check=True)
    waits = []
    with tsv.open() as f:
        f.readline()
        for line in f:
            if not line.startswith("wait\t"):
                continue
            _, th, t, dur, ev, frames = line.rstrip("\n").split("\t", 5)
            waits.append((int(t) // 1000, th, int(dur), ev, frames.split(";") if frames else []))
    return waits


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--threads", default="MainThread,main,Lighting Thread,World Streamer")
    ap.add_argument("--top", type=int, default=12)
    a = ap.parse_args()
    frames, window, _, _ = load_frames(a.run)
    waits = load_waits(a.run)
    if not waits:
        print("no wait events in the recording (run with --jfr-setting 'jdk.JavaMonitorWait#threshold=0ms' ...)")
        return
    if window:
        lo, hi = window
        # keep the part of each wait that lies inside the window (a wait that began before it still counts)
        inwin = [(t, th, min(t + d, hi) - max(t, lo), ev, fr) for t, th, d, ev, fr in waits if t < hi and t + d > lo]
        win_us = hi - lo
    else:
        inwin = waits
        win_us = max(w[0] for w in waits) - min(w[0] for w in waits)
    nframes = sum(1 for f in frames if window and window[0] <= f[0] < window[1]) if window else len(frames)
    print(f"== {Path(a.run).name}: {len(inwin)} wait events in the route window ({win_us / 1e6:.1f} s, {nframes} frames)")
    per_thread = defaultdict(list)
    for w in inwin:
        per_thread[w[1]].append(w)
    order = [t for t in a.threads.split(",") if t in per_thread] + sorted(t for t in per_thread if t not in a.threads.split(","))
    for th in order:
        ws = per_thread[th]
        total = sum(w[2] for w in ws)
        if total < win_us * 0.005 and th not in a.threads.split(","):
            continue
        print(f"\n{th}: blocked {total / win_us * 100:.1f}% of the window in {len(ws)} events"
              f" (mean {total / len(ws):.0f} us, per frame {total / max(nframes, 1):.0f} us)")
        by_site = Counter()
        n_site = Counter()
        by_ev = Counter()
        for _, _, dur, ev, fr in ws:
            s = site(fr)
            by_site[s] += dur
            n_site[s] += 1
            by_ev[ev] += dur
        for s, us in by_site.most_common(a.top):
            print(f"  {us / win_us * 100:5.1f}%  {n_site[s]:6d}x  mean {us / n_site[s]:7.0f} us  {s}")
        print("  by event: " + ", ".join(f"{ev} {us / win_us * 100:.1f}%" for ev, us in by_ev.most_common()))


if __name__ == "__main__":
    main()
