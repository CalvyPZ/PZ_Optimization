#!/usr/bin/env python3
"""Per-frame section times from the game's own GameProfiler recording, as a
second opinion next to the JFR attribution (harness/attribute.py).

  harness/sections.py <run-dir> [--thread game|render] [--threshold-ms 20] [--top 15] [--json out.json]

The game records one file set per profiled thread, named after the Java thread:
"MainThread" is the game thread (GameWindow.frameStep) and "main" the render thread
(RenderThread.renderStep). --thread game (default) / render picks one (the raw
names work too); older recordings without a thread name in the file names are read
as a whole. The route window is applied by frame number on the game thread; for the
render thread, whose frame counter is its own, it is applied by recording time
(the game-thread route frames' first start / last end).

Reads <run-dir>/profiler/, the Zomboid/Recording files a run made with
harness/run.sh --game-profiler (GameProfiler.Enabled=true in debug-options.ini):
  <stamp>_<uuid>_header.csv       column names, "KeyNamesTable" index -> section name
  <stamp>_<uuid>_times.csv        one line per frame: frameNo,start,end,segmentNo   (times in 100 ns units)
  <stamp>_<uuid>_times_NNNN.csv   60 frames per file: frameNo, then per span key,depth,start,length
                                  (depth-first, children after their parent; start relative to the frame)

Frames are keyed by the game's frame counter, the same counter pzopt-frames.out
records in its anchor and route markers, so the route window is applied by
frame number. Frames at or above the threshold are "slow". For each group the
report gives the mean time per top-level section (depth 0) and the inclusive
mean for every section key, and ranks the sections by how much more of a slow
frame they take than of an ordinary one — that difference is what the slow
frames spend their extra time on.
"""
import json
import re
import sys
from collections import defaultdict
from pathlib import Path


def load_recording(pdir, thread=None):
    pdir = Path(pdir)
    tag = f"_{thread}" if thread else ""
    if thread and not any(pdir.glob(f"*{tag}_header.csv")):
        raise SystemExit(f"no GameProfiler recording for thread {thread!r} in {pdir}")
    header = next(pdir.glob(f"*{tag}_header.csv"))
    names = {}
    in_keys = False
    for line in header.read_text().splitlines():
        if line.startswith("KeyNamesTable"):
            in_keys = True
            continue
        if in_keys and "," in line and not line.startswith("Index"):
            idx, name = line.split(",", 1)
            names[int(idx)] = name
    times = {}
    tfile = next(p for p in pdir.glob(f"*{tag}_times.csv") if not re.search(r"_times_\d+\.csv$", p.name))
    for line in tfile.read_text().splitlines():
        parts = line.split(",")
        if len(parts) < 4 or not parts[0].lstrip("-").isdigit():
            continue
        fno, start, end = int(parts[0]), int(parts[1]), int(parts[2])
        times[fno] = (start * 100, end * 100)  # ns
    frames = {}  # frameNo -> list of (key, depth, start_ns, len_ns)
    for seg in sorted(pdir.glob(f"*{tag}_times_*.csv")):
        for line in seg.read_text().splitlines():
            parts = line.rstrip(",").split(",")
            if len(parts) < 5 or not parts[0].lstrip("-").isdigit():
                continue
            fno = int(parts[0])
            spans = []
            for i in range(1, len(parts) - 3, 4):
                try:
                    spans.append((names.get(int(parts[i]), f"key{parts[i]}"), int(parts[i + 1]), int(parts[i + 2]) * 100, int(parts[i + 3]) * 100))
                except ValueError:
                    break
            frames[fno] = spans
    return names, times, frames


def route_frame_ids(run):
    """(first, last) frame counter of the route from pzopt-frames.out markers, or None."""
    p = Path(run) / "pzopt-frames.out"
    ids = {}
    if p.exists():
        for line in p.read_text().splitlines():
            if line.startswith("# route-"):
                parts = line.split()
                if len(parts) > 3:
                    ids[parts[1]] = int(parts[3])
    if "route-start" in ids and "route-end" in ids:
        return ids["route-start"], ids["route-end"]
    return None


THREADS = {"game": "MainThread", "render": "main"}


def mark_frame_ids(run, prefix):
    """Frame counters of every "# <prefix>..." mark in pzopt-frames.out (the frame the mark fell in)."""
    p = Path(run) / "pzopt-frames.out"
    ids = []
    if p.exists():
        for line in p.read_text().splitlines():
            if line.startswith("# " + prefix):
                parts = line.split()
                if len(parts) > 3:
                    ids.append(int(parts[3]))
    return ids


def analyse(run, threshold_ms=20.0, thread="game", after_mark=None):
    pdir = Path(run) / "profiler"
    thread = THREADS.get(thread, thread)
    if not any(pdir.glob(f"*_{thread}_header.csv")):
        thread = None  # single-thread recording from before the per-thread file names
    names, times, spans = load_recording(pdir, thread)
    window = route_frame_ids(run)
    twindow = None
    if window and thread == "main":
        # the render thread counts its own frames: take the game thread's route window by recording time
        _, gtimes, _ = load_recording(pdir, "MainThread")
        inside = [gtimes[f] for f in gtimes if window[0] <= f <= window[1]]
        twindow = (min(s for s, _ in inside), max(e for _, e in inside)) if inside else None
    rows = []
    for fno, (start, end) in sorted(times.items()):
        if twindow:
            if not (twindow[0] <= start and end <= twindow[1]):
                continue
        elif window and not (window[0] <= fno <= window[1]):
            continue
        total = end - start
        top = defaultdict(int)
        incl = defaultdict(int)
        for key, depth, s, ln in spans.get(fno, []):
            incl[key] += ln
            if depth == 0:
                top[key] += ln
        rows.append({"frame": fno, "total_ns": total, "top": top, "incl": incl, "top_sum": sum(top.values())})
    thr = threshold_ms * 1e6
    if after_mark:
        # --after-mark PREFIX:N: "slow" = the frame holding each mark and the N-1 after it (harness zoom steps and the like)
        slow_ids = set()
        for fid in mark_frame_ids(run, after_mark[0]):
            slow_ids.update(range(fid, fid + after_mark[1]))
        is_slow = lambda r: r["frame"] in slow_ids
    else:
        is_slow = lambda r: r["total_ns"] >= thr
    groups = {"slow": [r for r in rows if is_slow(r)], "ordinary": [r for r in rows if not is_slow(r)],
              "spike33": [r for r in rows if r["total_ns"] >= 33.333e6], "spike50": [r for r in rows if r["total_ns"] >= 50e6]}
    out = {"run": Path(run).name, "thread": thread or "all", "threshold_ms": threshold_ms, "frames": len(rows), "window": window, "groups": {}}
    keys = sorted({k for r in rows for k in r["incl"]})
    for g, rs in groups.items():
        if not rs:
            out["groups"][g] = {"frames": 0}
            continue
        n = len(rs)
        tot = sum(r["total_ns"] for r in rs)
        top_mean = {k: sum(r["top"].get(k, 0) for r in rs) / n / 1e6 for k in keys if any(k in r["top"] for r in rs)}
        incl_mean = {k: sum(r["incl"].get(k, 0) for r in rs) / n / 1e6 for k in keys}
        out["groups"][g] = {
            "frames": n, "mean_ms": tot / n / 1e6, "total_ms": tot / 1e6,
            "top_mean_ms": top_mean, "incl_mean_ms": incl_mean,
            "unaccounted_mean_ms": sum(r["total_ns"] - r["top_sum"] for r in rs) / n / 1e6,
        }
    return out


def print_report(a, top=15):
    print(f"== {a['run']} [{a['thread']}]: {a['frames']} profiled frames" + (f" in route frames {a['window'][0]}..{a['window'][1]}" if a["window"] else ""))
    s, o = a["groups"]["slow"], a["groups"]["ordinary"]
    for g, title in (("slow", f"SLOW (>= {a['threshold_ms']:g} ms)"), ("ordinary", "ORDINARY"), ("spike33", "SPIKES >= 33 ms"), ("spike50", "SPIKES >= 50 ms")):
        d = a["groups"][g]
        if not d["frames"]:
            print(f"--- {title}: no frames")
            continue
        print(f"--- {title}: {d['frames']} frames, mean {d['mean_ms']:.1f} ms, unaccounted by top-level sections {d['unaccounted_mean_ms']:.2f} ms/frame")
        print("  top-level sections, mean ms per frame (share of frame):")
        for k, v in sorted(d["top_mean_ms"].items(), key=lambda kv: -kv[1])[:top]:
            print(f"    {v:7.2f} ms {v / d['mean_ms'] * 100:5.1f}%  {k}")
    if s["frames"] and o["frames"]:
        print(f"--- what slow frames spend their extra {s['mean_ms'] - o['mean_ms']:.1f} ms on (inclusive mean, slow minus ordinary):")
        diffs = sorted(((s["incl_mean_ms"].get(k, 0) - o["incl_mean_ms"].get(k, 0), k) for k in s["incl_mean_ms"]), reverse=True)
        extra = s["mean_ms"] - o["mean_ms"]
        for dv, k in diffs[:top]:
            print(f"    {dv:+7.2f} ms {dv / extra * 100:5.1f}%  {k}   (slow {s['incl_mean_ms'].get(k, 0):.2f} / ordinary {o['incl_mean_ms'].get(k, 0):.2f})")


if __name__ == "__main__":
    args = sys.argv[1:]
    threshold, top, out_json, thread = 20.0, 15, None, "game"
    after_mark = None
    runs = []
    i = 0
    while i < len(args):
        if args[i] == "--threshold-ms":
            threshold = float(args[i + 1]); i += 2
        elif args[i] == "--thread":
            thread = args[i + 1]; i += 2
        elif args[i] == "--after-mark":
            pfx, nf = args[i + 1].rsplit(":", 1)
            after_mark = (pfx, int(nf)); i += 2
        elif args[i] == "--top":
            top = int(args[i + 1]); i += 2
        elif args[i] == "--json":
            out_json = args[i + 1]; i += 2
        else:
            runs.append(args[i]); i += 1
    for r in runs:
        a = analyse(r, threshold, thread, after_mark)
        print_report(a, top)
        if out_json:
            Path(out_json).write_text(json.dumps(a, indent=1))
