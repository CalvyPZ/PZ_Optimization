#!/usr/bin/env python3
"""Compare two runs' per-frame simulation checksums (pzopt-sim.out, --prop devSimChecksum=true).

    harness/simdiff.py <run-a> <run-b>      # run directories under harness/runs/ (or the .out files)

Prints the number of frames compared, the first frame whose hash differs and which part moved
(positions / states), and how many frames differ in total. Exit 0 = identical over the shorter run,
1 = a divergence, 2 = a file is missing. The frame index is the count of postupdate passes since
boot, so both runs must start from the same save with the same route (the frame the route starts
on can still shift by a few frames between runs; a divergence within the first seconds of the
route window on one of two otherwise identical runs is timing, on every run it is the change).
"""
import os
import sys


def load(path):
    if os.path.isdir(path):
        path = os.path.join(path, "pzopt-sim.out")
    if not os.path.exists(path):
        print(f"missing: {path}", file=sys.stderr)
        sys.exit(2)
    rows = {}
    with open(path) as f:
        for line in f:
            if not line or line[0] == "#":
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 5:
                continue
            rows[int(parts[0])] = (int(parts[1]), parts[2], parts[3], parts[4])
    return path, rows


def main(argv):
    if len(argv) != 3:
        print(__doc__)
        return 2
    pa, a = load(argv[1])
    pb, b = load(argv[2])
    common = sorted(set(a) & set(b))
    if not common:
        print(f"no common frames ({len(a)} in a, {len(b)} in b)")
        return 2
    first = None
    differing = 0
    zombies_differ = 0
    parts = {"positions": 0, "states": 0}
    for fr in common:
        ra, rb = a[fr], b[fr]
        if ra == rb:
            continue
        differing += 1
        if ra[0] != rb[0]:
            zombies_differ += 1
        if ra[2] != rb[2]:
            parts["positions"] += 1
        if ra[3] != rb[3]:
            parts["states"] += 1
        if first is None:
            first = fr
    print(f"{os.path.basename(os.path.dirname(pa)) or pa} vs {os.path.basename(os.path.dirname(pb)) or pb}: "
          f"{len(common)} common frames ({len(a)} / {len(b)})")
    if first is None:
        print("identical")
        return 0
    ra, rb = a[first], b[first]
    print(f"first divergence at frame {first}: zombies {ra[0]} vs {rb[0]}, "
          f"positions {'differ' if ra[2] != rb[2] else 'same'}, states {'differ' if ra[3] != rb[3] else 'same'}")
    print(f"{differing} of {len(common)} frames differ (zombie count differs in {zombies_differ}, "
          f"positions in {parts['positions']}, states in {parts['states']})")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
