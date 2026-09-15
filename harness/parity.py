#!/usr/bin/env python3
"""Compare two pzopt-parity.out captures square by square.

  harness/parity.py <runA-dir-or-file> <runB-dir-or-file> [--only-on-disk <save-map-dir>] [--limit N]

Reports chunks present in only one capture, and every square whose captured
fields differ, with the coordinate and the names of the differing fields.
Exit status 0 = parity (no differing squares among chunks captured in both
runs), 1 = differences found, 2 = usage/input error.

--only-on-disk restricts the comparison to chunks that exist in the given save's
map/ directory (map/<wx>/<wy>.bin): chunks generated on the fly by the game use
random content and are not expected to match run-to-run.
"""
import sys
from pathlib import Path

FIELDS = ["flags", "props", "matrices", "roof_surface_solid", "nav", "room", "zone"]


def load(path):
    p = Path(path)
    if p.is_dir():
        p = p / "pzopt-parity.out"
    if not p.exists():
        print(f"missing capture: {p}", file=sys.stderr)
        sys.exit(2)
    chunks = {}
    squares = {}
    cur = None
    with p.open() as f:
        for line in f:
            line = line.rstrip("\n")
            if line.startswith("# chunk"):
                parts = line.split()
                cur = (int(parts[2]), int(parts[3]))
                chunks[cur] = line
                continue
            if not line:
                continue
            head, *fields = [s.strip() for s in line.split(" | ")]
            wx, wy, x, y, z = (int(v) for v in head.split())
            squares[(x, y, z)] = ((wx, wy), fields)
    return chunks, squares


def main(argv):
    limit = 20
    only = None
    args = []
    i = 0
    while i < len(argv):
        if argv[i] == "--limit":
            limit = int(argv[i + 1]); i += 2
        elif argv[i] == "--only-on-disk":
            only = Path(argv[i + 1]); i += 2
        else:
            args.append(argv[i]); i += 1
    if len(args) != 2:
        print(__doc__); return 2
    ca, sa = load(args[0])
    cb, sb = load(args[1])

    def on_disk(c):
        return only is None or (only / str(c[0]) / f"{c[1]}.bin").exists()

    common = {c for c in ca if c in cb and on_disk(c)}
    only_a = sorted(c for c in ca if c not in cb and on_disk(c))
    only_b = sorted(c for c in cb if c not in ca and on_disk(c))
    skipped = sum(1 for c in ca if not on_disk(c))
    print(f"chunks: {len(ca)} in A, {len(cb)} in B, {len(common)} compared, {len(only_a)} only in A, {len(only_b)} only in B"
          + (f", {skipped} not on disk (skipped)" if only else ""))

    diffs = []
    missing = 0
    for key, (chunk, fa) in sa.items():
        if chunk not in common:
            continue
        b = sb.get(key)
        if b is None:
            missing += 1
            diffs.append((key, chunk, ["square-missing-in-B"], fa, None))
            continue
        fb = b[1]
        bad = [FIELDS[k] if k < len(FIELDS) else f"field{k}" for k in range(max(len(fa), len(fb))) if k >= len(fa) or k >= len(fb) or fa[k] != fb[k]]
        if bad:
            diffs.append((key, chunk, bad, fa, fb))
    for key, (chunk, fb) in sb.items():
        if chunk in common and key not in sa:
            diffs.append((key, chunk, ["square-missing-in-A"], None, fb))

    by_field = {}
    for _, _, bad, _, _ in diffs:
        for f in bad:
            by_field[f] = by_field.get(f, 0) + 1
    total = sum(1 for k, (c, _) in sa.items() if c in common)
    if not diffs:
        print(f"PARITY: {total} squares in {len(common)} chunks identical")
        return 0
    print(f"NO PARITY: {len(diffs)} of {total} squares differ; by field: {by_field}")
    for key, chunk, bad, fa, fb in sorted(diffs)[:limit]:
        print(f"  square {key} chunk {chunk}: {', '.join(bad)}")
        for f in bad:
            if f in FIELDS:
                k = FIELDS.index(f)
                print(f"      A {f}: {fa[k] if fa and k < len(fa) else '-'}")
                print(f"      B {f}: {fb[k] if fb and k < len(fb) else '-'}")
    if len(diffs) > limit:
        print(f"  ... {len(diffs) - limit} more")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
