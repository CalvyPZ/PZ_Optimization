#!/usr/bin/env python3
"""Compare benchmark runs against the stock baseline and say whether each
difference exceeds run-to-run noise.

  harness/compare.py <run-dir>... [--baseline harness/baseline]

Noise floor per metric = spread between the two stock baseline runs
(bench-stock-1.json / bench-stock-2.json), never less than a small absolute
floor. A difference counts as real when it exceeds twice that floor. Frame
times come from the in-game sampler and, when present, MangoHud.
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from analyze import summarize  # noqa: E402

METRICS = [
    # (label, getter, unit, lower_is_better, absolute noise floor)
    ("chunk latency p50 (enqueue→publish)", lambda s: s["latency"]["p50"], "ms", True, 2.0),
    ("chunk latency p90", lambda s: s["latency"]["p90"], "ms", True, 5.0),
    ("chunk latency p99", lambda s: s["latency"]["p99"], "ms", True, 20.0),
    ("chunks/s", lambda s: s["chunks"]["per_second"], "/s", False, 1.0),
    ("recalc per chunk mean", lambda s: s["chunks"]["recalc_us"]["mean"] / 1000, "ms", True, 0.1),
    ("streamer busy", lambda s: 100 * (s["chunks"]["load_us"]["total_s"] + s["chunks"]["recalc_us"]["total_s"]) / s["route_seconds"], "%", True, 0.5),
    ("frame mean", lambda s: s["frames"]["us"]["mean"] / 1000, "ms", True, 0.2),
    ("frame p99", lambda s: s["frames"]["us"]["p99"] / 1000, "ms", True, 0.5),
    ("frame p99.9", lambda s: s["frames"]["us"]["p99_9"] / 1000, "ms", True, 2.0),
    ("frame max", lambda s: s["frames"]["us"]["max"] / 1000, "ms", True, 10.0),
    ("frames >33ms", lambda s: s["frames"]["over_33ms"], "", True, 3),
    ("MangoHud p99", lambda s: s["mangohud"]["us"]["p99"] / 1000, "ms", True, 0.5),
    ("MangoHud p99.9", lambda s: s["mangohud"]["us"]["p99_9"] / 1000, "ms", True, 2.0),
]


def latency(run):
    """enqueue→publish per chunk on the route, from pzopt-chunks.out."""
    p = Path(run) / "pzopt-chunks.out"
    vals = []
    marks = {}
    with p.open() as f:
        hdr = f.readline().rstrip("\n").split("\t")
        for line in f:
            if line.startswith("#"):
                _, label, t = line.split()
                marks[label] = int(t)
                continue
            d = dict(zip(hdr, line.rstrip("\n").split("\t")))
            if len(d) != len(hdr):
                continue
            t = int(d["tEnqueueUs"])
            if "route-start" in marks and t < marks["route-start"]:
                continue
            if "route-end" in marks and t > marks["route-end"]:
                continue
            vals.append((int(d["queueWaitUs"]) + int(d["loadUs"]) + int(d["recalcWaitUs"]) + int(d["recalcUs"]) + int(d["publishWaitUs"])) / 1000)
    vals.sort()

    def pct(p):
        k = (len(vals) - 1) * p / 100
        lo, hi = int(k), min(int(k) + 1, len(vals) - 1)
        return vals[lo] + (vals[hi] - vals[lo]) * (k - lo)
    return {"p50": pct(50), "p90": pct(90), "p99": pct(99)}


def get(m, s):
    try:
        return m[1](s)
    except (KeyError, ZeroDivisionError, TypeError):
        return None


def main(argv):
    base_dir = Path("harness/baseline")
    runs = []
    i = 0
    while i < len(argv):
        if argv[i] == "--baseline":
            base_dir = Path(argv[i + 1]); i += 2
        else:
            runs.append(argv[i]); i += 1
    b1 = json.loads((base_dir / "bench-stock-1.json").read_text())
    b2 = json.loads((base_dir / "bench-stock-2.json").read_text())
    runs_dir = Path("harness/runs")
    for b in (b1, b2):
        d = next(runs_dir.glob(b["run"]), None)
        b["latency"] = latency(d) if d else {}
    print(f"baseline: {b1['run']} and {b2['run']} (noise floor = their spread, min absolute floor per metric)\n")
    header = f"{'metric':38s} {'stock':>9s} {'noise':>7s}"
    cands = []
    for r in runs:
        s = summarize(r)
        s["latency"] = latency(r)
        cands.append(s)
        header += f" | {Path(r).name[:22]:>22s}"
    print(header)
    for m in METRICS:
        v1, v2 = get(m, b1), get(m, b2)
        if v1 is None or v2 is None:
            continue
        stock = (v1 + v2) / 2
        noise = max(abs(v1 - v2), m[4])
        line = f"{m[0]:38s} {stock:9.1f} {noise:7.1f}"
        for s in cands:
            v = get(m, s)
            if v is None:
                line += f" | {'-':>22s}"
                continue
            d = v - stock
            better = (d < 0) == m[3]
            if abs(d) <= 2 * noise:
                verdict = "within noise"
            else:
                verdict = ("better" if better else "WORSE") + f" {abs(d)/stock*100:.0f}%" if stock else ("better" if better else "WORSE")
            line += f" | {v:8.1f} {verdict:>13s}"
        print(line)
    print("\nsettings per run:")
    for s in cands:
        print(f"  {s['run']}: {s.get('props','').replace(chr(10), ' ')}")


if __name__ == "__main__":
    main(sys.argv[1:])
