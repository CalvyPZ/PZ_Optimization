#!/usr/bin/env python3
"""Replay a recorded run through a model of WorldStreamer to estimate what a
W-wide recalc pool would change.

  harness/simulate.py <run-dir> [W ...]

Model (from WorldStreamer.threadLoop): the streamer drains the job queue at the
top of each loop; with work queued it takes one chunk, loads it (loadUs,
serial), recalculates it (recalcUs) and publishes it, and returns without
sleeping while more work is queued. When the last queued chunk is done, or
when nothing is queued, the loop falls through to a 140 ms sleep; with nothing
queued it sleeps 140 ms *before* that too (280 ms poll period when idle).
With a pool, recalc of up to W chunks overlaps the streamer's next loads;
publication happens in submission order. Arrivals and service times are the
recorded ones, so this isolates the effect of the pool from everything else.
Output: enqueue-to-publish latency percentiles and the streamer's busy fraction.
Pass --wake to model a streamer that is woken on enqueue instead of polling.
"""
import statistics
import sys
from pathlib import Path

POLL_US = 140_000


def pct(v, p):
    v = sorted(v)
    k = (len(v) - 1) * p / 100
    lo, hi = int(k), min(int(k) + 1, len(v) - 1)
    return v[lo] + (v[hi] - v[lo]) * (k - lo)


def load(run):
    rows = []
    with (Path(run) / "pzopt-chunks.out").open() as f:
        hdr = f.readline().rstrip("\n").split("\t")
        for line in f:
            if line.startswith("#"):
                continue
            p = line.rstrip("\n").split("\t")
            if len(p) != len(hdr):
                continue
            d = dict(zip(hdr, p))
            rows.append((int(d["tEnqueueUs"]), int(d["loadUs"]), int(d["recalcUs"])))
    rows.sort()
    return rows


def simulate(rows, W, wake=False):
    t = rows[0][0]
    i = 0
    n = len(rows)
    latencies = []
    busy = 0
    workers = [0] * W          # time each worker becomes free
    last_publish = 0           # in-order publication: a chunk publishes after the previous one
    pending = []               # chunks enqueued but not yet seen by the loop (FIFO by arrival)
    while i < n or pending:
        # drain arrivals up to now (the loop polls its queue once per iteration)
        while i < n and rows[i][0] <= t:
            pending.append(rows[i]); i += 1
        if not pending:
            if wake:
                t = rows[i][0] if i < n else t
            else:
                t += POLL_US  # "jobList empty" sleep, then the end-of-loop sleep below
                t += POLL_US
            continue
        enq, load_us, recalc_us = pending.pop(0)
        t += load_us
        busy += load_us
        if W == 1:
            t += recalc_us
            busy += recalc_us
            done = t
        else:
            w = min(range(W), key=lambda k: workers[k])
            start = max(t, workers[w])
            workers[w] = start + recalc_us
            done = workers[w]
        publish = max(done, last_publish)
        last_publish = publish
        latencies.append(publish - enq)
        if not pending and not wake:
            # busy == false after the last queued chunk: the loop falls through to its end-of-loop sleep
            while i < n and rows[i][0] <= t:
                pending.append(rows[i]); i += 1
            if not pending:
                t += POLL_US
    span = rows[-1][0] - rows[0][0]
    return latencies, busy / span if span else 0


def main(argv):
    wake = "--wake" in argv
    argv = [a for a in argv if a != "--wake"]
    run = argv[0]
    widths = [int(w) for w in argv[1:]] or [1, 2, 4, 8]
    rows = load(run)
    print(f"{Path(run).name}: {len(rows)} chunks, recorded enqueue->publish latency "
          f"p50 {pct([r for r in observed(run)], 50)/1000:.0f}ms p90 {pct(observed(run), 90)/1000:.0f}ms p99 {pct(observed(run), 99)/1000:.0f}ms")
    print(f"model: {'woken on enqueue' if wake else 'stock polling (140 ms sleeps)'}")
    print(f"{'W':>3} {'p50':>8} {'p90':>8} {'p99':>8} {'max':>8} {'mean':>8}  streamer busy")
    for W in widths:
        lat, busy = simulate(rows, W, wake)
        print(f"{W:>3} {pct(lat,50)/1000:>7.0f}ms {pct(lat,90)/1000:>7.0f}ms {pct(lat,99)/1000:>7.0f}ms {max(lat)/1000:>7.0f}ms {statistics.fmean(lat)/1000:>7.0f}ms  {busy*100:5.1f}%")


def observed(run):
    out = []
    with (Path(run) / "pzopt-chunks.out").open() as f:
        hdr = f.readline().rstrip("\n").split("\t")
        for line in f:
            if line.startswith("#"):
                continue
            p = line.rstrip("\n").split("\t")
            if len(p) != len(hdr):
                continue
            d = dict(zip(hdr, p))
            out.append(int(d["queueWaitUs"]) + int(d["loadUs"]) + int(d["recalcWaitUs"]) + int(d["recalcUs"]) + int(d["publishWaitUs"]))
    return out


if __name__ == "__main__":
    main(sys.argv[1:])
