#!/usr/bin/env python3
"""Frame times around every camera zoom step of a `--flag zoom_cycle=N` run.

Reads <run>/pzopt-frames.out (one game-thread frame duration in microseconds per line, with
"# <label> <us> <frameId>" marks; the harness writes a "zoom-<level>" mark at every step) and prints,
per step, the worst frame and the frames over 8.3 / 16.7 / 33 ms in the window after it, plus the
same numbers for the rest of the route (the frames not inside any step window) so the cost of a zoom
change stands out from the route's own tail.

    harness/zoomsteps.py <run-dir-or-label> [--window 1.5] [--json]
"""
import argparse
import json
import statistics
import sys
from pathlib import Path


def pct(xs, p):
    if not xs:
        return 0
    k = (len(xs) - 1) * p / 100.0
    f = int(k)
    c = min(f + 1, len(xs) - 1)
    return xs[f] + (xs[c] - xs[f]) * (k - f)


def load(run):
    """[(t_us_from_route_start, dur_us)] between route-start and route-end, and the zoom marks."""
    frames, marks = [], []
    t = None  # running clock, us, set by the marks (frames advance it)
    in_route = False
    for line in (run / "pzopt-frames.out").read_text().splitlines():
        if line.startswith("#"):
            parts = line.split()
            label = parts[1]
            if label == "anchor":
                continue
            t = int(parts[2])
            if label == "route-start":
                in_route = True
                frames, marks = [], []
            elif label == "route-end":
                in_route = False
            elif label.startswith("zoom-") and in_route:
                marks.append((t, label[5:]))
            continue
        if not in_route or not line.strip() or t is None:
            continue
        d = int(line)
        frames.append((t, d))
        t += d
    return frames, marks


def stats(durs):
    xs = sorted(durs)
    return {
        "frames": len(xs),
        "mean_ms": statistics.fmean(xs) / 1000 if xs else 0,
        "p99_ms": pct(xs, 99) / 1000,
        "max_ms": (xs[-1] / 1000) if xs else 0,
        "over_8ms": sum(1 for x in xs if x > 8333),
        "over_16ms": sum(1 for x in xs if x > 16667),
        "over_33ms": sum(1 for x in xs if x > 33333),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--window", type=float, default=1.5, help="seconds after a step that count as the step's window")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()
    run = Path(a.run)
    if not run.is_dir():
        run = Path(__file__).resolve().parent / "runs" / a.run
    frames, marks = load(run)
    if not frames:
        sys.exit("no route frames in " + str(run / "pzopt-frames.out"))
    t0 = frames[0][0]
    win = int(a.window * 1e6)
    steps = []
    in_step = [False] * len(frames)
    for i, (tm, level) in enumerate(marks):
        end = tm + win
        if i + 1 < len(marks):
            end = min(end, marks[i + 1][0])
        durs = []
        for j, (t, d) in enumerate(frames):
            if tm <= t < end:
                durs.append(d)
                in_step[j] = True
        steps.append({"t_s": (tm - t0) / 1e6, "level": level, **stats(durs)})
    rest = stats([d for j, (t, d) in enumerate(frames) if not in_step[j]])
    whole = stats([d for t, d in frames])
    out = {"run": run.name, "window_s": a.window, "steps": steps, "rest": rest, "route": whole}
    if a.json:
        print(json.dumps(out, indent=1))
        return
    print(f"{run.name}: {len(marks)} zoom steps, window {a.window} s after each")
    print(f"{'t(s)':>6} {'zoom':>5} {'frames':>6} {'mean':>6} {'p99':>6} {'max':>7} {'>8ms':>5} {'>16ms':>5} {'>33ms':>5}")
    for s in steps:
        print(f"{s['t_s']:6.1f} {s['level']:>5} {s['frames']:6d} {s['mean_ms']:6.2f} {s['p99_ms']:6.2f} {s['max_ms']:7.2f} {s['over_8ms']:5d} {s['over_16ms']:5d} {s['over_33ms']:5d}")
    for name, s in (("rest", rest), ("route", whole)):
        print(f"{name:>12} {s['frames']:6d} {s['mean_ms']:6.2f} {s['p99_ms']:6.2f} {s['max_ms']:7.2f} {s['over_8ms']:5d} {s['over_16ms']:5d} {s['over_33ms']:5d}")
    if steps:
        worst = max(steps, key=lambda s: s["max_ms"])
        print(f"worst step: t={worst['t_s']:.1f}s zoom {worst['level']} max {worst['max_ms']:.1f} ms; "
              f"steps max median {statistics.median(s['max_ms'] for s in steps):.1f} ms vs rest max {rest['max_ms']:.1f} ms")


if __name__ == "__main__":
    main()
