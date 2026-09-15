#!/usr/bin/env python3
"""Summarise one harness run: chunk-load throughput/timings and frame-time distribution.

  harness/analyze.py <run-dir> [--json <out.json>] [--skip-seconds N]

Reads pzopt-chunks.out and pzopt-frames.out written by pzopt.Stats. Frame
samples from the first --skip-seconds (default 20) are dropped: they cover the
initial world load, which is not what the benchmark measures.
"""
import json
import statistics
import sys
from pathlib import Path


def pct(sorted_vals, p):
    if not sorted_vals:
        return 0
    k = (len(sorted_vals) - 1) * p / 100.0
    lo, hi = int(k), min(int(k) + 1, len(sorted_vals) - 1)
    return sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * (k - lo)


def summarize(run, skip_seconds=20):
    run = Path(run)
    out = {"run": run.name}
    chunks = []
    marks = {}
    p = run / "pzopt-chunks.out"
    if p.exists():
        with p.open() as f:
            header = f.readline().rstrip("\n").split("\t")
            for line in f:
                if line.startswith("#"):
                    _, label, t = line.split()
                    marks[label] = int(t)
                    continue
                parts = line.rstrip("\n").split("\t")
                if len(parts) != len(header):
                    continue
                chunks.append(dict(zip(header, parts)))
    # a scripted route brackets the interesting part; keep only chunks enqueued inside it
    if "route-start" in marks and "route-end" in marks and "tEnqueueUs" in (chunks[0] if chunks else {}):
        t0, t1 = marks["route-start"], marks["route-end"]
        chunks = [c for c in chunks if t0 <= int(c["tEnqueueUs"]) <= t1]
        out["route_seconds"] = (t1 - t0) / 1e6
    if chunks:
        recalc = sorted(int(c["recalcUs"]) for c in chunks if c["recalcUs"] != "0" or c["loadUs"] != "0")
        load = sorted(int(c["loadUs"]) for c in chunks)
        wait = sorted(int(c["queueWaitUs"]) for c in chunks)
        rwait = sorted(int(c["recalcWaitUs"]) for c in chunks)
        threads = {}
        for c in chunks:
            threads[c["thread"]] = threads.get(c["thread"], 0) + 1
        total_load_s = sum(load) / 1e6
        total_recalc_s = sum(recalc) / 1e6
        out["chunks"] = {
            "count": len(chunks),
            "threads": threads,
            "load_us": {"mean": statistics.fmean(load), "p50": pct(load, 50), "p90": pct(load, 90), "p99": pct(load, 99), "max": load[-1], "total_s": total_load_s},
            "recalc_us": {"mean": statistics.fmean(recalc), "p50": pct(recalc, 50), "p90": pct(recalc, 90), "p99": pct(recalc, 99), "max": recalc[-1], "total_s": total_recalc_s},
            "queue_wait_us": {"mean": statistics.fmean(wait), "p50": pct(wait, 50), "p90": pct(wait, 90), "p99": pct(wait, 99), "max": wait[-1]},
            "recalc_wait_us": {"mean": statistics.fmean(rwait), "p50": pct(rwait, 50), "p99": pct(rwait, 99), "max": rwait[-1]},
            "recalc_share_of_streamer_time": total_recalc_s / (total_load_s + total_recalc_s) if (total_load_s + total_recalc_s) else 0,
        }
    frames = []
    p = run / "pzopt-frames.out"
    in_route = None  # None = no markers, keep all; else only frames between the markers
    if p.exists():
        for line in p.read_text().splitlines():
            if line.startswith("#"):
                label = line.split()[1]
                if label == "route-start":
                    frames = []
                    in_route = True
                    skip_seconds = 0
                elif label == "route-end":
                    in_route = False
                continue
            if in_route is False:
                continue
            if line.strip():
                frames.append(int(line))
    if frames:
        skip = 0 if in_route is not None else skip_seconds
        # drop the first N seconds of samples (initial load)
        acc, i = 0, 0
        while i < len(frames) and acc < skip * 1e6:
            acc += frames[i]
            i += 1
        used = sorted(frames[i:])
        if used:
            total_s = sum(used) / 1e6
            out["frames"] = {
                "count": len(used),
                "seconds": total_s,
                "fps_mean": len(used) / total_s if total_s else 0,
                "us": {"mean": statistics.fmean(used), "p50": pct(used, 50), "p90": pct(used, 90), "p99": pct(used, 99), "p99_9": pct(used, 99.9), "max": used[-1]},
                "over_33ms": sum(1 for x in used if x > 33333),
                "over_50ms": sum(1 for x in used if x > 50000),
                "over_100ms": sum(1 for x in used if x > 100000),
            }
    if chunks and out.get("frames"):
        out["chunks"]["per_second"] = out["chunks"]["count"] / out.get("route_seconds", out["frames"]["seconds"] + skip_seconds)
    # external MangoHud log (frametime column in ms; elapsed in ns) restricted to the
    # route window using the wall-clock stamps pzopt-bench.out records
    mh = run / "mangohud.csv"
    bench = run / "pzopt-bench.out"
    if mh.exists():
        lines = mh.read_text().splitlines()
        hdr_i = next((i for i, l in enumerate(lines) if l.startswith("fps,")), None)
        if hdr_i is not None:
            hdr = lines[hdr_i].split(",")
            ft_i, el_i = hdr.index("frametime"), hdr.index("elapsed")
            rows = []
            for l in lines[hdr_i + 1:]:
                parts = l.split(",")
                if len(parts) > max(ft_i, el_i):
                    try:
                        rows.append((float(parts[el_i]) / 1e9, float(parts[ft_i])))
                    except ValueError:
                        pass
            # file name carries the log start time to the second: ProjectZomboid64_YYYY-MM-DD_HH-MM-SS.csv
            import datetime, re
            m = re.search(r"(\d{4}-\d{2}-\d{2})_(\d{2})-(\d{2})-(\d{2})", mh.name) if not (run / "mangohud.name").exists() else None
            name = (run / "mangohud.name").read_text().strip() if (run / "mangohud.name").exists() else ""
            m = re.search(r"(\d{4}-\d{2}-\d{2})_(\d{2})-(\d{2})-(\d{2})", name) or m
            t_start = None
            if m:
                t_start = datetime.datetime.strptime(f"{m.group(1)} {m.group(2)}:{m.group(3)}:{m.group(4)}", "%Y-%m-%d %H:%M:%S").timestamp()
            sel = rows
            if t_start is not None and bench.exists():
                kv = dict(l.split("=", 1) for l in bench.read_text().splitlines() if "=" in l)
                if "route_start_epoch_ms" in kv:
                    a = int(kv["route_start_epoch_ms"]) / 1000 - t_start
                    b = int(kv["route_end_epoch_ms"]) / 1000 - t_start
                    sel = [r for r in rows if a <= r[0] <= b]
            ft = sorted(r[1] * 1000 for r in sel)  # -> microseconds
            if ft:
                out["mangohud"] = {
                    "count": len(ft),
                    "us": {"mean": statistics.fmean(ft), "p50": pct(ft, 50), "p90": pct(ft, 90), "p99": pct(ft, 99), "p99_9": pct(ft, 99.9), "max": ft[-1]},
                    "over_33ms": sum(1 for x in ft if x > 33333),
                    "windowed": sel is not rows,
                }
    props = run / "pzopt.properties"
    if props.exists():
        out["props"] = props.read_text().strip()
    return out


def fmt_us(v):
    return f"{v/1000:.1f}ms"


def print_summary(s):
    print(f"== {s['run']}  [{s.get('props','')}]")
    c = s.get("chunks")
    if c:
        print(f"chunks: {c['count']} loaded, {c.get('per_second',0):.1f}/s; threads {c['threads']}")
        for k in ("load_us", "recalc_us", "queue_wait_us"):
            d = c[k]
            extra = f"  total {d['total_s']:.1f}s" if "total_s" in d else ""
            print(f"  {k:14s} mean {fmt_us(d['mean'])}  p50 {fmt_us(d['p50'])}  p90 {fmt_us(d['p90'])}  p99 {fmt_us(d['p99'])}  max {fmt_us(d['max'])}{extra}")
        print(f"  recalc share of streamer time: {c['recalc_share_of_streamer_time']*100:.0f}%")
    f = s.get("frames")
    if f:
        u = f["us"]
        print(f"frames: {f['count']} over {f['seconds']:.0f}s, {f['fps_mean']:.1f} fps mean")
        print(f"  frame  mean {fmt_us(u['mean'])}  p50 {fmt_us(u['p50'])}  p90 {fmt_us(u['p90'])}  p99 {fmt_us(u['p99'])}  p99.9 {fmt_us(u['p99_9'])}  max {fmt_us(u['max'])}")
        print(f"  frames >33ms: {f['over_33ms']}  >50ms: {f['over_50ms']}  >100ms: {f['over_100ms']}")
    m = s.get("mangohud")
    if m:
        u = m["us"]
        print(f"mangohud: {m['count']} frames{' (route window)' if m['windowed'] else ''}")
        print(f"  frame  mean {fmt_us(u['mean'])}  p50 {fmt_us(u['p50'])}  p90 {fmt_us(u['p90'])}  p99 {fmt_us(u['p99'])}  p99.9 {fmt_us(u['p99_9'])}  max {fmt_us(u['max'])}  >33ms: {m['over_33ms']}")


if __name__ == "__main__":
    args = sys.argv[1:]
    out_json = None
    skip = 20
    if "--json" in args:
        i = args.index("--json"); out_json = args[i + 1]; del args[i:i + 2]
    if "--skip-seconds" in args:
        i = args.index("--skip-seconds"); skip = float(args[i + 1]); del args[i:i + 2]
    for run in args:
        s = summarize(run, skip)
        print_summary(s)
        if out_json:
            Path(out_json).write_text(json.dumps(s, indent=1))
