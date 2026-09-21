#!/usr/bin/env python3
"""
harness/secdiff.py <run-a> <run-b> [--thread game|render] [--top 40] [--match REGEX]

Mean inclusive GameProfiler section time per route frame for two --game-profiler runs,
side by side and sorted by the difference (a minus b). Every route frame counts, so
this answers "where does scene A spend more per frame than scene B" (e.g. a storm vs
clear weather), which sections.py's slow-vs-ordinary split does not. Also prints the
per-frame call count (mean spans per frame) so a section that got called more often is
told apart from one that got slower per call.
"""
import re
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from sections import THREADS, load_recording, route_frame_ids  # noqa: E402


def per_frame(run, thread):
    pdir = Path(run) / "profiler"
    thread = THREADS.get(thread, thread)
    if not any(pdir.glob(f"*_{thread}_header.csv")):
        thread = None
    names, times, spans = load_recording(pdir, thread)
    window = route_frame_ids(run)
    incl = defaultdict(int)
    calls = defaultdict(int)
    n = 0
    total = 0
    for fno, (start, end) in sorted(times.items()):
        if window and not (window[0] <= fno <= window[1]):
            continue
        n += 1
        total += end - start
        for key, depth, s, ln in spans.get(fno, []):
            incl[key] += ln
            calls[key] += 1
    if n == 0:
        raise SystemExit(f"no route frames in {run}")
    return n, total / n / 1e6, {k: v / n / 1e6 for k, v in incl.items()}, {k: v / n for k, v in calls.items()}


def main(argv):
    args = [a for a in argv if not a.startswith("--")]
    thread = "game"
    top = 40
    match = None
    it = iter(argv)
    for a in it:
        if a == "--thread":
            thread = next(it)
        elif a == "--top":
            top = int(next(it))
        elif a == "--match":
            match = re.compile(next(it))
    if len(args) < 2:
        raise SystemExit(__doc__)
    ra, rb = args[0], args[1]
    na, ta, ia, ca = per_frame(ra, thread)
    nb, tb, ib, cb = per_frame(rb, thread)
    print(f"a = {Path(ra).name}: {na} route frames, mean {ta:.2f} ms")
    print(f"b = {Path(rb).name}: {nb} route frames, mean {tb:.2f} ms")
    print(f"{'section (inclusive, mean ms/frame)':52} {'a':>8} {'b':>8} {'a-b':>8} {'calls a':>8} {'calls b':>8}")
    keys = set(ia) | set(ib)
    if match:
        keys = {k for k in keys if match.search(k)}
    rows = sorted(keys, key=lambda k: -(ia.get(k, 0) - ib.get(k, 0)))
    for k in rows[:top]:
        print(f"{k[:52]:52} {ia.get(k, 0):8.3f} {ib.get(k, 0):8.3f} {ia.get(k, 0) - ib.get(k, 0):8.3f} {ca.get(k, 0):8.1f} {cb.get(k, 0):8.1f}")


if __name__ == "__main__":
    main(sys.argv[1:])
