#!/usr/bin/env python3
"""A long run in windows: fps, p99, frames > 50 / > 100 ms per minute, JIT and GC cores (schedmon) per window.
Usage: long-windows.py RUN [window_s=60]"""
import sys
from pathlib import Path

run = Path(sys.argv[1])
W = float(sys.argv[2]) if len(sys.argv) > 2 else 60
kv = dict(l.split("=", 1) for l in (run / "pzopt-bench.out").read_text().splitlines() if "=" in l)
A, B = int(kv["route_start_epoch_ms"]), int(kv["route_end_epoch_ms"])
lines = (run / "pzopt-overlay.out").read_text().splitlines()
h = lines[0].split(",")
it, ie = h.index("frametime"), h.index("epoch_ms")
fr = []
for l in lines[1:]:
    p = l.split(",")
    try:
        e, f = int(p[ie]), float(p[it])
    except (ValueError, IndexError):
        continue
    if A <= e <= B:
        fr.append((e, f))
# schedmon: cumulative run_ns per thread class at each sample
jit, gc = {}, {}
sm = run / "schedmon.txt"
if sm.exists():
    for l in sm.read_text().splitlines():
        if l.startswith("#"):
            continue
        f = l.split()
        if f[1] == "-":
            continue
        t, comm, r = int(f[0]), f[2], int(f[3])
        k = jit if ("Compiler" in comm or comm.startswith(("C1_", "C2_"))) else gc if comm.startswith(("G1_", "GC_")) else None
        if k is not None:
            k.setdefault(t, {})[f[1]] = r


def cores(d, a, b):
    ts = sorted(t for t in d if a <= t <= b)
    if len(ts) < 2:
        return float("nan")
    s0, s1 = d[ts[0]], d[ts[-1]]
    return sum(v - s0.get(tid, 0) for tid, v in s1.items()) / ((ts[-1] - ts[0]) * 1e6)


print(f"{run.name}: route {(B - A) / 1000:.0f} s, windows of {W:.0f} s")
print(f"  {'from s':>6s} {'fps':>5s} {'p50':>5s} {'p99':>6s} {'>50/m':>6s} {'>100/m':>6s} {'JIT':>5s} {'GC':>5s}")
t = A
while t < B:
    e = min(B, t + W * 1000)
    w = sorted(f for x, f in fr if t <= x < e)
    secs = (e - t) / 1000
    if len(w) > 10 and secs > W / 3:
        print(f"  {(t - A) / 1000:6.0f} {len(w) / secs:5.1f} {w[len(w) // 2]:5.1f} {w[int(.99 * len(w))]:6.1f} "
              f"{60 * sum(f > 50 for f in w) / secs:6.1f} {60 * sum(f > 100 for f in w) / secs:6.1f} {cores(jit, t, e):5.2f} {cores(gc, t, e):5.2f}")
    t = e
