## 1. Frame log with a wall-clock anchor

- [ ] 1.1 Add an epoch anchor to `pzopt-frames.out` (wall-clock time of the first frame of each flush block plus the nanoTime offsets), so every recorded frame has an absolute start and end; verify by checking the anchors against the route-start/end epoch stamps in `pzopt-bench.out`
- [ ] 1.2 Confirm the anchored log costs nothing measurable: one bench run with it against the stock baseline; verify frame-time distribution within the noise floor

## 2. JFR run and join

- [ ] 2.1 Add `--jfr` to `harness/run.sh`: swap `ProjectZomboid64.json` for a copy with `-XX:StartFlightRecording=settings=profile,filename=<Zomboid>/pzopt.jfr,dumponexit=true` for the run and restore it on exit; verify the recording file appears and `jfr summary` lists `jdk.ExecutionSample` events
- [ ] 2.2 Write `harness/attribute.py`: parse `jfr print --json`, keep game-thread execution samples, map each to a frame by time, aggregate stacks by top frame and by first `zombie.*` frame for frames above the threshold (default 20 ms) and, separately, for ordinary frames; verify on a run that the ordinary-frame ranking is dominated by the render/update loop as expected and that slow-frame samples sum to roughly the slow-frame time
- [ ] 2.3 Confirm JFR overhead is within noise: compare the profiled run's frame distribution (in-game sampler and MangoHud) with the stock baseline; if it is not, lower the sampling rate and record the overhead in the report
- [ ] 2.4 Run three profiled bench runs and pool them for the ≥33 ms and ≥50 ms spike buckets; verify the pooled spike count is large enough to rank (tens, not a handful) or say it is not

## 3. Second opinion and A/B runs

- [ ] 3.1 Enable `GameProfiler.Enabled` for one run, read `GameProfileRecording`'s output format, and produce per-frame section times (`IsoChunkMap.update`, `IsoCell` probes, `logic`, `states.render`); verify the sections of the slow frames agree with the JFR ranking or record where they disagree
- [ ] 3.2 A/B: PZDashboard disabled for the bench (harness removes it from the bench save's `mods.txt` and from `default.txt` for that run, restoring both); verify `compare.py` reports the tail change against noise
- [ ] 3.3 A/B: G1 instead of ZGC (harness swaps in a launcher JSON with `-XX:+UseG1GC` for that run); verify the report includes the tail change and the GC pause events from `gc.log` that fall inside the route window
- [ ] 3.4 Extend `compare.py` with the GC pause count/time inside the route window and the JFR overhead line

## 4. Attribution report

- [ ] 4.1 Write `docs/frame-time-attribution.md`: ranked contributors to the p99 tail and to the spikes with shares, the A/B results, and an "unexplained" line; verify every figure in it points at a run directory under `harness/runs/` or a baseline JSON
- [ ] 4.2 State the verdict on chunk hand-off explicitly: in the top contributors or not, with its share; this decides whether section 5 runs

## 5. Chunk publication budget (only if 4.2 says hand-off is a top contributor)

- [ ] 5.1 Add `zombie/iso/IsoChunkMap` to the overrides (Vineflower via `scripts/regen-overrides.sh`, build, signature check); verify the unmodified round-trip loads and behaves as stock in a bench run
- [ ] 5.2 Replace the count-based drain in `updateInternal` with a time-budgeted one (`publishBudgetMs`, default 2, `0` = stock), same order and same per-chunk work; verify the setting is reported at startup and `0` reproduces stock in the instrumentation
- [ ] 5.3 Run the parity gate with the budget enabled; verify the capture matches the stock baseline
- [ ] 5.4 Benchmark with the budget at 1, 2 and 4 ms against the stock baseline and the wake+pool result; verify the report states the frame-tail change, the chunk-latency change, and whether each exceeds noise
- [ ] 5.5 Decide the shipped default from 5.4 (enabled only on a tail improvement beyond noise without a chunk-latency regression beyond noise) and record it in the attribution report

## 6. Wrap up

- [ ] 6.1 Play a session on the resulting build (with or without the budget) — driving, cell crossings, save/reload; verify no crash, no visual artifact, no pathfinding failure
- [ ] 6.2 Record the honest result in `docs/frame-time-attribution.md`, including if nothing in the game thread's tail is fixable within this change's scope
