#!/usr/bin/env python3
"""Callee tree under one game frame over a run's route window, from the overlay's stack log.

  harness/subtree.py <run-dir> [<run-dir> ...] [--frame FBORenderCell.renderMovingObjects]
                     [--depth 6] [--min-pct 0.2] [--all] [--self]

Reads pzopt-stacks.out like flamegraph.py, keeps the route-window seconds, finds every stack
that passes through --frame (default: the characters draw, FBORenderCell.renderMovingObjects)
and prints the inclusive share of each callee, as a percentage of ALL game-thread samples
(the number analyze.py prints next to the sub-phase), biggest first, --depth levels deep.
--self adds a flat list of the leaf frames under it (where the time really goes). Several runs
print one tree each, so a before / after pair is one command.
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from flamegraph import read_stacks, route_window  # noqa: E402


class Node:
    __slots__ = ("name", "count", "kids")

    def __init__(self, name):
        self.name, self.count, self.kids = name, 0, {}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--frame", default="FBORenderCell.renderMovingObjects")
    ap.add_argument("--depth", type=int, default=6)
    ap.add_argument("--min-pct", type=float, default=0.2)
    ap.add_argument("--all", action="store_true", help="every second, not just the route window")
    ap.add_argument("--self", dest="self_", action="store_true", help="also list the leaf frames under --frame")
    a = ap.parse_args()
    for run in a.runs:
        window = None if a.all else route_window(run)
        frames, stacks, samples, seconds, hz = read_stacks(run, window)
        root = Node(a.frame)
        leaves = {}
        for path, c in stacks.items():
            try:
                i = path.index(a.frame)
            except ValueError:
                continue
            root.count += c
            n = root
            for f in path[i + 1:]:
                k = n.kids.get(f)
                if k is None:
                    k = n.kids[f] = Node(f)
                k.count += c
                n = k
            leaves[path[-1]] = leaves.get(path[-1], 0) + c
        total = max(1, samples)
        print(f"== {Path(run).name}: {a.frame} {100.0 * root.count / total:.1f}% of {samples} samples over {seconds} s"
              f" ({root.count} samples)")

        def walk(n, depth):
            if depth > a.depth:
                return
            for k in sorted(n.kids.values(), key=lambda k: -k.count):
                pct = 100.0 * k.count / total
                if pct < a.min_pct:
                    continue
                own = k.count - sum(x.count for x in k.kids.values())
                own_s = f"  (self {100.0 * own / total:.1f}%)" if own and k.kids else ""
                print(f"{'  ' * depth}{pct:5.1f}%  {k.name}{own_s}")
                walk(k, depth + 1)

        walk(root, 1)
        if a.self_:
            print("  -- leaves:")
            for name, c in sorted(leaves.items(), key=lambda x: -x[1]):
                pct = 100.0 * c / total
                if pct < a.min_pct:
                    break
                print(f"  {pct:5.1f}%  {name}")


if __name__ == "__main__":
    main()
