#!/usr/bin/env python3
"""Which spike frames (> N ms) overlap a GC pause (gc.log with wall-clock time decoration)? Usage: gc-spikes.py RUN [N=50]"""
import re, sys, datetime
from pathlib import Path
run = Path(sys.argv[1]); N = float(sys.argv[2]) if len(sys.argv) > 2 else 50
kv = dict(l.split("=", 1) for l in (run / "pzopt-bench.out").read_text().splitlines() if "=" in l)
A, B = int(kv["route_start_epoch_ms"]), int(kv["route_end_epoch_ms"])
pauses = []
for g in sorted(run.glob("gc.log*")):
    for l in g.read_text().splitlines():
        m = re.match(r"\[(\S+?)\]\[\S+\] GC\(\d+\) (Pause \S+(?: \(\S+\))?).* ([\d.]+)ms$", l)
        if m:
            t = datetime.datetime.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S.%f%z").timestamp() * 1000
            d = float(m.group(3)); pauses.append((t - d, t, d, m.group(2)))  # the line is written at the pause end
pr = [p for p in pauses if A <= p[1] <= B]
lines = (run / "pzopt-overlay.out").read_text().splitlines(); h = lines[0].split(",")
it, ie = h.index("frametime"), h.index("epoch_ms")
fr = []
for l in lines[1:]:
    p = l.split(",")
    try:
        e, f = int(p[ie]), float(p[it])
    except (ValueError, IndexError):
        continue
    if A <= e <= B: fr.append((e - f, e, f))
sp = [f for f in fr if f[2] > N]
hit = [f for f in sp if any(p[0] < f[1] and p[1] > f[0] for p in pr)]
gcms = sum(p[2] for p in pr); secs = (B - A) / 1000
print(f"{run.name}: {len(pr)} GC pauses in the route ({len(pr)/secs:.1f}/s, {gcms:.0f} ms = {100*gcms/(B-A):.1f} % of wall, max {max((p[2] for p in pr), default=0):.0f} ms); "
      f"{len(sp)} frames > {N:.0f} ms, {len(hit)} of them overlap a pause ({100*len(hit)/max(1,len(sp)):.0f} %)")
