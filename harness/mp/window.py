import sys, csv, statistics as st
def stats(run, secs):
    start = int([l for l in open(f"{run}/pzopt-schedule.out") if l.startswith("route_start")][0].split("=")[1])
    ft = [float(r["frametime"]) for r in csv.DictReader(open(f"{run}/pzopt-overlay.out")) if start <= int(r["epoch_ms"]) < start + secs*1000]
    ft.sort(); n = len(ft)
    q = lambda p: ft[min(n-1, int(p*n))]
    return n, n/secs, st.mean(ft), q(0.5), q(0.99), q(0.999), ft[-1], sum(1 for f in ft if f > 1000/240)/n*100
for run, secs in [(a.split(":")[0], int(a.split(":")[1])) for a in sys.argv[1:]]:
    n, fps, mean, p50, p99, p999, mx, below = stats(run, secs)
    print(f"{run.split('/')[-1][:22]:22s} {secs:3d}s  fps {fps:6.1f}  mean {mean:4.1f}  p50 {p50:4.1f}  p99 {p99:5.1f}  p99.9 {p999:5.1f}  max {mx:5.1f}  <240fps {below:4.1f}%")
