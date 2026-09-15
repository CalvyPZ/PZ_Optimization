# Result: parallel-chunk-grid-recalc

Measured 2026-09-15 on the fixed route (`harness/baseline/comparison-2026-09-15.txt`
is the full table; per-run summaries in `harness/baseline/bench-*.json`; every
figure below comes from the harness, not from observation). Machine: Ryzen 7
9800X3D, RTX 4090, NVMe; game 42.20.4 `b0bbce05d5` under Proton.

## What changed for the player

Chunk latency — the time from the game asking for a chunk to the chunk being
ready for the game thread — on the 100 s car-speed route:

| variant | p50 | p90 | p99 |
|---|---|---|---|
| stock (two runs) | 166 ms | 310 ms | 1024 ms |
| wake-on-enqueue only | 20 ms | 184 ms | 811 ms |
| pool only, W=4 (no wake) | 174 ms (noise) | 293 ms (noise) | 707 ms |
| wake + pool W=2 | 9.7 ms | 103 ms | 556 ms |
| wake + pool W=4 (shipped default) | 9.3 ms | 84 ms | 517 ms |
| wake + pool W=8 | 9.1 ms | 87 ms | 503 ms |
| kill switch (`parallel=false wake=false`) | 175 ms | 337 ms | 923 ms |

Frame time (mean, p99, p99.9; in-game sampler cross-checked against MangoHud)
is within run-to-run noise for every variant, including W=8 — no
render-thread starvation, and no frame-time *improvement* either: the
stutter this change set out to address is not caused by the streamer on this
machine. Chunks per second is unchanged (the route sets it).

## Honest reading

- The proposal's premise was half right. The recalc pass is the larger part
  of the streamer's work (71 %), but the streamer is idle >90 % of the time
  at car speed. What players see as chunk latency was ~90 % the loop's fixed
  140 ms sleeps. Waking the streamer on enqueue (design Decision 8, added
  during implementation) is worth 8× at the median on its own; the pool on
  top brings it to 18×, mostly at p90/p99 where bursts of a full chunk row
  are being processed.
- The pool alone, as originally proposed, would have been a "within noise"
  result at the median. It is kept because with the wake in place it is a
  further 2× at the median and 2.2× at p90, at a cost of ~0.4 ms more CPU per
  chunk (workers contend for cache) — 4 workers is the knee; 8 buys nothing.
- No visible frame-time change. On a 16-thread machine with an NVMe disk the
  game thread's own chunk work (`doLoadGridsquare`, lighting) and rendering set
  the frame-time tail, not the streamer. A slower CPU or disk would gain more
  from both changes, but that is not measured here.
- Parity: identical recalc output to stock at W=1, 2, 4, 15 and across two
  W=4 runs (131,133 squares in 1,653 chunks); an injected worker failure is
  retried on the streamer thread and still matches.
