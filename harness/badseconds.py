#!/usr/bin/env python3
"""Game-thread sub-phases (pzopt-gamethread.out, 1 s blocks) in the route seconds with the most frame time in
spike frames vs the calmest seconds. Usage: badseconds.py RUN [spike_ms=50] [key prefix=s:]"""
import sys, collections
from pathlib import Path
run = Path(sys.argv[1]); N = float(sys.argv[2]) if len(sys.argv) > 2 else 50; pref = sys.argv[3] if len(sys.argv) > 3 else "s:"
kv = dict(l.split("=", 1) for l in (run / "pzopt-bench.out").read_text().splitlines() if "=" in l)
A, B = int(kv["route_start_epoch_ms"]), int(kv["route_end_epoch_ms"])
lines = (run / "pzopt-overlay.out").read_text().splitlines(); h = lines[0].split(",")
it, ie = h.index("frametime"), h.index("epoch_ms")
fr = []
for l in lines[1:]:
    p = l.split(",")
    try: fr.append((int(p[ie]), float(p[it])))
    except (ValueError, IndexError): pass
secs = []
for l in (run / "pzopt-gamethread.out").read_text().splitlines():
    if l.startswith("#"): continue
    f = l.split("\t"); t = int(f[0]); n = int(f[1])
    if not (A <= t and t + 1000 <= B) or n == 0: continue
    d = collections.Counter()
    for kvp in f[2:]:
        k, v = kvp.rsplit("=", 1)
        if k.startswith(pref) or k.startswith("w:"): d[k] += int(v) / n
    bad = sum(x for e, x in fr if t <= e < t + 1000 and x > N)
    secs.append((bad, d))
secs.sort(key=lambda s: s[0])
q = max(1, len(secs) // 4)
calm, worst = secs[:q], secs[-q:]
def avg(ss):
    a = collections.Counter()
    for _, d in ss:
        for k, v in d.items(): a[k] += v / len(ss)
    return a
c, w = avg(calm), avg(worst)
print(f"{run.name}: {len(secs)} route seconds; worst quarter {sum(s[0] for s in worst)/len(worst):.0f} ms/s in frames > {N:.0f} ms, calm quarter {sum(s[0] for s in calm)/len(calm):.0f}")
print(f"  {'key':70s} {'worst':>6s} {'calm':>6s} {'diff':>6s}")
for k in sorted(set(c) | set(w), key=lambda k: -(w[k] - c[k]))[:18]:
    print(f"  {k[:70]:70s} {100*w[k]:6.1f} {100*c[k]:6.1f} {100*(w[k]-c[k]):+6.1f}")
