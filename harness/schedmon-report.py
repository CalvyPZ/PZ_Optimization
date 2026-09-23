#!/usr/bin/env python3
"""Read <run>/schedmon.txt (harness/schedmon.py) against the run's frames.

Prints, over the route window (pzopt-bench.out):
  - per thread (and grouped: GC, JIT, driver / native, pools): CPU share of wall, run-queue wait share
    (runnable but no CPU), major faults;
  - the machine: PSI cpu / memory / io stall shares, swap-ins, major faults;
  - spike frames (> --spike ms, from pzopt-overlay.out): for the sample intervals that overlap them, the game
    thread's split into running / waiting for a CPU / off-CPU (sleeping or blocked), the other threads that ran
    most, faults and PSI, next to the same split over normal frames.
Usage: schedmon-report.py RUN [--spike 50] [--top 14]
"""
import argparse, collections, re
from pathlib import Path

ap = argparse.ArgumentParser()
ap.add_argument("run")
ap.add_argument("--spike", type=float, default=50.0)
ap.add_argument("--top", type=int, default=14)
args = ap.parse_args()
run = Path(args.run)

kv = dict(l.split("=", 1) for l in (run / "pzopt-bench.out").read_text().splitlines() if "=" in l)
A, B = int(kv["route_start_epoch_ms"]), int(kv["route_end_epoch_ms"])

# samples: t -> {tid: (comm, run, wait, majflt)}, psi rows
samples = collections.OrderedDict()
psi = []
for line in (run / "schedmon.txt").read_text().splitlines():
    if line.startswith("#"):
        continue
    f = line.split()
    t = int(f[0])
    if f[1] == "-":
        if f[2] == "PSI":
            psi.append((t, *map(int, f[3:])))
        continue
    samples.setdefault(t, {})[f[1]] = (f[2], int(f[3]), int(f[4]), int(f[6]), f[7])
ts = [t for t in samples if A - 1000 <= t <= B + 1000]


def group(comm):
    c = comm
    if re.match(r"(GC_Thread|G1_|GC_|ZWorker|ZDirector|ZStat|ZUnmapper|ZDriver|VM_Periodic|G1_Conc|G1_Refine|G1_Main|G1_Service)", c):
        return "[GC]"
    if re.match(r"(C2_CompilerThre|C1_CompilerThre|C2_Compiler|C1_Compiler|Compiler)", c):
        return "[JIT]"
    if re.match(r"(pool-\d+-thread|pzopt-frame|ForkJoinPool|pzopt-batch|pzopt-bone|pzopt-char)", c):
        return "[pools]"
    return c


def deltas(t0, t1):
    s0, s1 = samples[t0], samples[t1]
    d = {}
    for tid, v in s1.items():
        o = s0.get(tid)
        r0, w0, m0 = (o[1], o[2], o[3]) if o else (0, 0, 0)
        d[tid] = (v[0], v[1] - r0, v[2] - w0, v[3] - m0)
    return d


# whole route
win = [t for t in ts if A <= t <= B]
if len(win) < 2:
    raise SystemExit("schedmon: no samples in the route window")
wall = (win[-1] - win[0]) * 1e6
tot = deltas(win[0], win[-1])
per = collections.defaultdict(lambda: [0, 0, 0, 0])
game_tid = None
for tid, (comm, r, w, m) in tot.items():
    g = group(comm)
    p = per[g]
    p[0] += r; p[1] += w; p[2] += m; p[3] += 1
    if comm == "MainThread":
        game_tid = tid
print(f"== {run.name}: schedmon, {len(win)} samples over {wall/1e9:.1f} s of route, {len(samples[win[-1]])} threads")
print(f"  {'thread':24s} {'cpu%':>6s} {'rq-wait%':>9s} {'majflt':>7s}  (share of one core's wall; rq-wait = runnable, no CPU)")
tr = tw = 0
for g, (r, w, m, n) in sorted(per.items(), key=lambda kv: -kv[1][0])[:args.top]:
    print(f"  {g + (f' x{n}' if n > 1 else ''):24s} {100*r/wall:6.1f} {100*w/wall:9.1f} {m:7d}")
for g, (r, w, m, n) in per.items():
    tr += r; tw += w
print(f"  {'process total':24s} {100*tr/wall:6.1f} {100*tw/wall:9.1f}   = {tr/wall/4*100:.0f} % of 4 cores busy, {tw/wall:.2f} threads waiting for a CPU on average")

pw = [p for p in psi if A <= p[0] <= B]
if len(pw) >= 2:
    a, b = pw[0], pw[-1]
    ms = (b[0] - a[0]) * 1000.0
    print(f"  machine PSI over the route: cpu some {100*(b[1]-a[1])/ms:.1f} %, memory some {100*(b[2]-a[2])/ms:.1f} % full {100*(b[3]-a[3])/ms:.1f} %, "
          f"io some {100*(b[4]-a[4])/ms:.1f} %; swap-in {b[5]-a[5]} pages, swap-out {b[6]-a[6]}, major faults {b[7]-a[7]}, ctx switches {(b[8]-a[8])/(ms/1e3):.0f}/s")

# spike frames
fr = []
lines = (run / "pzopt-overlay.out").read_text().splitlines()
hdr = lines[0].split(",")
it, ie = hdr.index("frametime"), hdr.index("epoch_ms")
for l in lines[1:]:
    p = l.split(",")
    try:
        e, ft = int(p[ie]), float(p[it])
    except (ValueError, IndexError):
        continue
    if A <= e <= B:
        fr.append((e - ft, e, ft))

wt = sorted(win)


def interval_split(t0, t1, spike_iv):
    """Game thread run / wait / off over the sample intervals covering [t0, t1]."""
    import bisect
    i = max(bisect.bisect_right(wt, t0) - 1, 0)
    j = min(bisect.bisect_left(wt, t1), len(wt) - 1)
    if j <= i:
        return None
    for k in range(i, j):
        spike_iv.add(k)
    return i, j


spike_iv, norm_iv = set(), set()
spikes = [f for f in fr if f[2] > args.spike]
for a, b, ft in spikes:
    interval_split(a, b, spike_iv)
for a, b, ft in fr:
    if ft < 25:
        interval_split(a, b, norm_iv)
norm_iv -= spike_iv


def summarize(ivs, label):
    if not ivs:
        print(f"  {label}: none")
        return
    g = collections.defaultdict(lambda: [0, 0, 0])
    wall = 0
    psi_d = [0] * 8
    for k in ivs:
        t0, t1 = wt[k], wt[k + 1]
        wall += (t1 - t0) * 1e6
        for tid, (comm, r, w, m) in deltas(t0, t1).items():
            x = g[("GAME" if tid == game_tid else group(comm))]
            x[0] += r; x[1] += w; x[2] += m
        p0 = [p for p in psi if p[0] <= t0]
        p1 = [p for p in psi if p[0] <= t1]
        if p0 and p1:
            for q in range(8):
                psi_d[q] += p1[-1][q + 1] - p0[-1][q + 1]
    gm = g.pop("GAME", [0, 0, 0])
    off = wall - gm[0] - gm[1]
    others = sorted(g.items(), key=lambda kv: -kv[1][0])[:8]
    tot_r = sum(v[0] for v in g.values()) + gm[0]
    tot_w = sum(v[1] for v in g.values()) + gm[1]
    print(f"  {label}: {len(ivs)} intervals, {wall/1e9:.2f} s")
    print(f"    game thread: running {100*gm[0]/wall:.0f} %, waiting for a CPU {100*gm[1]/wall:.0f} %, off-CPU (sleep / lock / IO) {100*off/wall:.0f} %, major faults {gm[2]}")
    print(f"    process: {tot_r/wall:.2f} cores busy, {tot_w/wall:.2f} threads queued; PSI cpu {100*psi_d[0]/(wall/1e3):.0f} % mem {100*psi_d[1]/(wall/1e3):.0f} % io {100*psi_d[3]/(wall/1e3):.0f} %, swap-in {psi_d[4]}, majflt {psi_d[6]}")
    print("    others by cpu: " + "  ".join(f"{k} {100*v[0]/wall:.0f}%(q{100*v[1]/wall:.0f})" for k, v in others))


print(f"frames in route: {len(fr)}, spikes > {args.spike:.0f} ms: {len(spikes)}")
summarize(sorted(spike_iv), f"spike intervals (frames > {args.spike:.0f} ms)")
summarize(sorted(norm_iv), "normal intervals (frames < 25 ms)")
