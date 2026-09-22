#!/usr/bin/env python3
"""One row per run: frame tail (overlay, route window), utilization, and the schedmon split.

Usage: hitch-table.py RUN [RUN...]   (run dirs or labels under harness/runs/, newest match)
Columns: fps, p50 / p99 / p99.9 / max ms, frames > 50 / > 100 ms per minute, frame-to-frame jitter, sysmon CPU / GPU %,
game thread running / waiting for a CPU / off-CPU %, JIT and GC cores, swap-ins, major faults.
"""
import statistics, sys
from pathlib import Path

RUNS = Path(__file__).resolve().parent / "runs"


def find(arg):
    p = Path(arg)
    if p.is_dir():
        return p
    c = sorted(RUNS.glob(f"*{arg}-2*"), key=lambda q: q.stat().st_mtime)
    if not c:
        raise SystemExit(f"no run {arg}")
    return c[-1]


def pct(v, q):
    v = sorted(v)
    return v[min(len(v) - 1, int(q * len(v)))]


def row(run):
    kv = dict(l.split("=", 1) for l in (run / "pzopt-bench.out").read_text().splitlines() if "=" in l)
    A, B = int(kv["route_start_epoch_ms"]), int(kv["route_end_epoch_ms"])
    lines = (run / "pzopt-overlay.out").read_text().splitlines()
    h = lines[0].split(",")
    it, ie = h.index("frametime"), h.index("epoch_ms")
    ft = []
    for l in lines[1:]:
        p = l.split(",")
        try:
            if A <= int(p[ie]) <= B:
                ft.append(float(p[it]))
        except (ValueError, IndexError):
            pass
    secs = (B - A) / 1000
    jit = statistics.mean(abs(a - b) for a, b in zip(ft, ft[1:])) if len(ft) > 1 else 0
    r = {"run": run.name[:34], "fps": len(ft) / secs, "p50": pct(ft, .5), "p99": pct(ft, .99), "p999": pct(ft, .999),
         "max": max(ft), "s50": 60 * sum(f > 50 for f in ft) / secs, "s100": 60 * sum(f > 100 for f in ft) / secs, "jit": jit}
    # sysmon
    try:
        sl = (run / "sysmon.csv").read_text().splitlines()
        sh = sl[0].split(",")
        rows = [dict(zip(sh, x.split(","))) for x in sl[1:]]
        rows = [x for x in rows if A <= int(x["epoch_ms"]) <= B]
        r["cpu"] = statistics.mean(float(x["cpu_pct"]) for x in rows)
        r["gpu"] = statistics.mean(float(x["gpu_pct"]) for x in rows)
    except Exception:
        r["cpu"] = r["gpu"] = float("nan")
    # schedmon
    sm = run / "schedmon.txt"
    r.update(run_=float("nan"), wait=float("nan"), off=float("nan"), jitc=float("nan"), gcc=float("nan"), swin=0, mf=0)
    if sm.exists():
        first, last, psi0, psi1 = {}, {}, None, None
        for l in sm.read_text().splitlines():
            if l.startswith("#"):
                continue
            f = l.split()
            t = int(f[0])
            if t < A or t > B:
                continue
            if f[1] == "-":
                psi0 = psi0 or f
                psi1 = f
                continue
            v = (t, f[2], int(f[3]), int(f[4]))
            first.setdefault(f[1], v)
            last[f[1]] = v
        wall = None
        g = {"game": [0, 0], "jit": [0, 0], "gc": [0, 0]}
        for tid, (t1, comm, r1, w1) in last.items():
            t0, _, r0, w0 = first[tid]
            k = "game" if comm == "MainThread" else "jit" if "Compiler" in comm or comm.startswith("C2_") or comm.startswith("C1_") else "gc" if comm.startswith(("G1_", "GC_", "VM_Thread")) else None
            if k:
                g[k][0] += r1 - r0
                g[k][1] += w1 - w0
            if comm == "MainThread":
                wall = (t1 - t0) * 1e6
        if wall:
            r["run_"] = 100 * g["game"][0] / wall
            r["wait"] = 100 * g["game"][1] / wall
            r["off"] = 100 - r["run_"] - r["wait"]
            r["jitc"] = g["jit"][0] / wall
            r["gcc"] = g["gc"][0] / wall
        if psi0 and psi1:
            r["swin"] = int(psi1[7]) - int(psi0[7])
            r["mf"] = int(psi1[9]) - int(psi0[9])
    return r


print(f"{'run':34s} {'fps':>5s} {'p50':>5s} {'p99':>6s} {'p99.9':>6s} {'max':>6s} {'>50/m':>6s} {'>100/m':>6s} {'jit':>5s} {'cpu%':>5s} {'gpu%':>5s} "
      f"{'gRun%':>5s} {'gQ%':>4s} {'gOff%':>5s} {'JIT':>4s} {'GC':>4s} {'swpin':>6s} {'majflt':>6s}")
for a in sys.argv[1:]:
    r = row(find(a))
    print(f"{r['run']:34s} {r['fps']:5.1f} {r['p50']:5.1f} {r['p99']:6.1f} {r['p999']:6.1f} {r['max']:6.1f} {r['s50']:6.1f} {r['s100']:6.1f} {r['jit']:5.1f} "
          f"{r['cpu']:5.0f} {r['gpu']:5.0f} {r['run_']:5.0f} {r['wait']:4.0f} {r['off']:5.0f} {r['jitc']:4.2f} {r['gcc']:4.2f} {r['swin']:6d} {r['mf']:6d}")
