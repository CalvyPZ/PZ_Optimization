#!/usr/bin/env python3
"""Inclusive call tree of the game thread over the route window, from a run's
JFR samples (harness/run.sh --jfr; samples.tsv is produced by attribute.py).

  harness/gametree.py <run-dir> [--min-pct 0.4] [--depth 40] [--thread MainThread]
                      [--root zombie.GameWindow.frameStep] [--callers pkg.Class.method]

Prints the tree of zombie.*/se.krka.* frames (JDK frames folded into their
caller) below --root, each node with its inclusive share of the root's
samples, pruned at --min-pct. --callers prints, for one method, which callers
account for its samples (first non-JDK caller above it).
"""
import argparse
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent


def route_window(run):
    """[start_ns, end_ns] of the route, via attribute.py's frame/anchor join."""
    sys.path.insert(0, str(REPO / "harness"))
    import attribute  # noqa
    try:
        _frames, window, _a, _m = attribute.load_frames(run)
    except Exception:
        return None
    if window:
        return window[0] * 1000, window[1] * 1000
    return None


def ensure_samples(run):
    tsv = run / "samples.tsv"
    if not tsv.exists():
        subprocess.run([sys.executable, str(REPO / "harness/attribute.py"), str(run), "--top", "1"],
                       check=False, stdout=subprocess.DEVNULL)
    return tsv


def is_game(frame):
    return not (frame.startswith("java.") or frame.startswith("jdk.") or frame.startswith("sun.")
                or frame.startswith("org.lwjgl"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--min-pct", type=float, default=0.4)
    ap.add_argument("--depth", type=int, default=40)
    ap.add_argument("--thread", default="MainThread")
    ap.add_argument("--root", default="zombie.GameWindow.frameStep")
    ap.add_argument("--callers", default=None)
    ap.add_argument("--all-frames", action="store_true", help="keep JDK frames in the tree")
    a = ap.parse_args()
    run = Path(a.run)
    tsv = ensure_samples(run)
    win = route_window(run)
    tree = {}
    counts = defaultdict(int)
    total = 0
    callers = defaultdict(int)
    n_thread = 0
    with open(tsv, errors="replace") as f:
        next(f)
        for line in f:
            kind, thread, ts, frames = line.rstrip("\n").split("\t", 3)
            if thread != a.thread:
                continue
            n_thread += 1
            if win and not (win[0] <= int(ts) <= win[1]):
                continue
            fr = frames.split(";")  # top first
            if a.root not in fr:
                continue
            fr = fr[: fr.index(a.root) + 1]
            path = [x for x in fr if a.all_frames or is_game(x)]
            path.reverse()  # root first
            total += 1
            if a.callers:
                if a.callers in fr:
                    i = fr.index(a.callers)
                    up = next((x for x in fr[i + 1:] if is_game(x)), "?")
                    callers[up] += 1
            node = tree
            seen = set()
            for i, x in enumerate(path[: a.depth]):
                key = x
                node = node.setdefault(key, {})
                if key not in seen:  # recursion: count once per sample per node identity
                    pass
                counts[(i, key, id(node))] += 1
    if total == 0:
        print(f"no {a.thread} samples under {a.root} (thread samples: {n_thread}, window: {win})")
        return
    print(f"== {run.name}: {total} {a.thread} samples under {a.root} on the route (window {'yes' if win else 'none'})")
    if a.callers:
        n = sum(callers.values())
        print(f"callers of {a.callers}: {n} samples = {100*n/total:.1f}% of the root")
        for k, v in sorted(callers.items(), key=lambda kv: -kv[1])[:40]:
            print(f"  {100*v/total:5.1f}%  {k}")
        return
    # second pass to attach counts per node object
    def walk(node, depth, prefix):
        items = []
        for k, child in node.items():
            c = counts.get((depth, k, id(child)), 0)
            items.append((c, k, child))
        items.sort(key=lambda t: -t[0])
        for c, k, child in items:
            pct = 100 * c / total
            if pct < a.min_pct:
                continue
            short = k.replace("zombie.", "").replace("se.krka.kahlua.", "kahlua.")
            print(f"{pct:5.1f}%  {'  ' * depth}{short}")
            walk(child, depth + 1, prefix)
    walk(tree, 0, "")


if __name__ == "__main__":
    main()
