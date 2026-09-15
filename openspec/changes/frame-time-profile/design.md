## Context

See proposal.md — Why. What we can already see in the code and data:

- `IsoChunkMap.updateInternal()` (game thread, every frame) drains
  `IsoChunk.loadGridSquare` and calls `chunk.doLoadGridsquare()` for up to
  `1 + 3·count/chunkGridWidth` chunks — four per frame for a full 19-chunk row —
  with no time bound. `doLoadGridsquare()` does the cross-chunk boundary
  recalcs, lighting flags, and `addChunkToWorld` into pathfinding, collision
  and population managers. With the streamer now delivering a whole row at
  once, those four land in one frame.
- The in-game sampler (`pzopt.Stats`) already records every frame's duration
  with a frame counter; MangoHud logs agree with it to 0.1 ms.
- The game has `GameProfiler` sections (`GameWindow.frameStep`, `logic`,
  `states.render`, `IsoChunkMap.update`, twenty probes in `IsoCell`), enabled
  by `GameProfiler.Enabled=true` in `debug-options.ini`, recording per-frame
  section times to `Zomboid/Recording`. Format to be read from
  `GameProfileRecording`.
- The JRE is GraalVM 25 (Windows build under Proton); JFR is available.
  `vmArgs` in `ProjectZomboid64.json` is user-maintained and the harness
  already swaps `latestSave.ini`, `pzopt.properties` and the MangoHud config
  per run, restoring them afterwards.
- `gc.log` (ZGC) shows major collections of 0.8–1.9 s wall time during runs;
  ZGC's stop-the-world phases are short but allocation stalls are not, so GC
  is a candidate, and the `.stock` launcher JSON uses G1 for a direct
  comparison.
- PZDashboard streams game state to a local server every tick from Lua.

## Goals / Non-Goals

**Goals:**

- Name the contributors to the p99 / p99.9 tail and to the spikes, with
  shares, from data the harness produced.
- Keep the fix (if any) to one more overridden class and to the same
  switchable, parity-gated pattern as the previous change.

**Non-Goals:**

- Optimising `doLoadGridsquare` itself, lighting, or rendering.
- General JVM tuning; one collector comparison only.
- Reducing the mean frame time: at ~150 fps the mean is not the problem.

## Decisions

### Decision 1: JFR execution sampling, joined to per-frame durations by time

Run JFR (`-XX:StartFlightRecording=settings=profile,filename=...,dumponexit=true`)
for the bench route and join its `jdk.ExecutionSample` events (thread = game
thread, timestamp, stack) with `pzopt-frames.out`. To join, `pzopt.Stats`
records, per frame, the wall-clock start (`System.currentTimeMillis()` once
per flush plus the accumulated nanoTime offsets) so each JFR sample maps to a
frame; samples that fall in frames above the threshold are aggregated by top
frames and by "first game-package frame" to give the ranking. `jfr print
--json` (JDK tool, on the host JDK 26) parses the recording; `harness/attribute.py`
does the join.

*Alternatives considered.* The game's `GameProfiler` alone — coarse sections,
no stacks, but cheap; used as the second opinion, not the primary source.
Async-profiler — not available for the Windows JRE under Proton. Adding our
own timers around suspects — biased toward what we already suspect.

### Decision 2: Attribution before any fix, and a fixed threshold

The threshold for "slow" is 20 ms (≈ p99 on the baseline), reported alongside
the ≥33 ms and ≥50 ms buckets. Attribution runs first and is written up
before any override is touched; the publication budget is implemented only if
the attribution ranks chunk hand-off in the top contributors.

### Decision 3: Time-budgeted drain in `IsoChunkMap.updateInternal`

If warranted: replace the count-based loop with one that processes chunks
until `System.nanoTime()` exceeds a per-frame budget (default 2 ms, from
`pzopt.properties` key `publishBudgetMs`, `0` = stock count-based). Order and
work per chunk stay identical, so parity holds by construction; what changes
is that some chunks become visible a frame or two later, which the
chunk-latency metric will show and the report must weigh.

*Alternatives considered.* Moving parts of `doLoadGridsquare` off the game
thread — the parts that dominate would first have to be known and audited,
which is this change's attribution step; a candidate for a later change.

### Decision 4: A/B runs stay code-free

PZDashboard off = removing it from the bench save's `mods.txt` and
`default.txt` for that run (the harness already edits both to enable its own
mod). G1 = swapping `ProjectZomboid64.json` for a copy with `-XX:+UseG1GC`
for that run. Both restored on exit, as the harness does for everything else.

## Risks / Trade-offs

- **JFR overhead on the game thread** → measured against the noise floor
  like the instrumentation was; default 20 ms sampling on one thread is
  expected to be invisible. If not, report it and lower the sampling rate.
- **Clock alignment between JFR and the frame log** → both derive from the
  same JVM; JFR timestamps are epoch-based, and the frame log gets an epoch
  anchor per flush. A frame is 6–30 ms, so sub-millisecond skew cannot move a
  sample by more than one frame; the report ignores samples within 0.5 ms of
  a frame boundary.
- **Spikes are rare** (a handful per run) → several runs are pooled for the
  spike attribution; the p99 tail (≈150 frames per run) is enough from one.
- **A third overridden class** → same build guard and signature check; the
  budget ships disabled if attribution does not justify it.
- **Editing the launcher JSON** → the harness backs it up and restores it on
  exit; a crash mid-run leaves a `.pzopt-orig` copy to restore by hand.

## Migration Plan

Nothing ships unless Decision 3 is warranted; then it ships as a third loose
class with the same install/uninstall/kill-switch mechanism. Rollback is
`pzopt.sh uninstall` or `publishBudgetMs=0`.

## Open Questions

- The exact on-disk format of `GameProfileRecording` — read when wiring the
  second-opinion parser; it affects only that parser.
