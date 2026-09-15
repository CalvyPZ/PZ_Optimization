## 1. Prove the delivery mechanism before optimising anything

- [x] 1.1 Set up a build that decompiles a chosen Build 42 revision with cfr and compiles the result against `projectzomboid.jar` and the bundled JRE; verify the toolchain runs end to end on one trivial class
- [x] 1.2 Round-trip `zombie.iso.IsoChunk` and `zombie.iso.WorldStreamer` unmodified — decompile, recompile, drop the `.class` files into the install directory; verify the game starts, loads a save, and streams chunks with no behavioural difference
- [x] 1.3 Confirm the loose classes are actually the ones loaded, not the jar's copies, by logging a marker from the recompiled class at startup and seeing it in the game log
- [x] 1.4 If 1.2 or 1.3 fails, stop and switch to the `-javaagent` approach from design.md Decision 1 before continuing; record which mechanism was chosen — result: loose-class shadowing works (run harness/runs/roundtrip-20260914-224327); no javaagent needed. Overrides are decompiled with Vineflower rather than cfr because its output for these two classes recompiles with one fix (see scripts/regen-overrides.sh)

## 2. Installer and build guard

- [x] 2.1 Implement install, listing exactly the files written, and verify `projectzomboid.jar`'s checksum is unchanged afterwards
- [x] 2.2 Implement uninstall from the recorded file list, and verify a checksum of the whole install directory matches its pre-install state
- [x] 2.3 Refuse to install when the launcher classpath does not place the install directory ahead of the jar, and verify with a deliberately edited `ProjectZomboid64.json`
- [x] 2.4 Record the game build the overrides were compiled against and refuse to load on mismatch; verify by faking a different build and seeing the overrides disable themselves with a clear log line
- [x] 2.5 Implement the status query reporting installed/not, the file list, and the target build; verify output in both states

## 3. Measure the baseline before changing behaviour

- [x] 3.1 Instrument each chunk recalculation with its duration, queue wait and coordinates; verify records appear for chunks loaded during play
- [x] 3.2 Confirm instrumentation overhead is negligible by comparing frame-time distributions with it on and off; verify the difference is within run-to-run noise
- [x] 3.3 Build the repeatable worst-case benchmark — a fixed save and a fixed driving route that crosses chunk boundaries continuously; verify two consecutive runs agree closely enough to detect a real change
- [x] 3.4 Record the stock baseline: chunk-load throughput and frame-time distribution including worst percentiles; verify the numbers are written to a file the comparison step can read
- [x] 3.5 Report whether recalculation time or disk read time dominates chunk load, answering the first open question in design.md

## 4. World-state parity harness

- [x] 4.1 Implement a capture of full post-load world state for a fixed chunk set — every square property the recalc pass writes, including collision, pathfind, vision-blocking, roof and navigation state; verify two stock runs produce identical captures
- [x] 4.2 Implement the comparison that reports differing squares by coordinate and field; verify it detects a deliberately corrupted capture
- [x] 4.3 Wire parity into the build as the acceptance gate, so a parity failure fails the build

## 5. Audit the recalc path for shared mutable state

- [x] 5.1 Enumerate every static field reached from `RecalcProperties`, `doGridNav` and everything they call transitively; verify the list is complete by cross-checking against the decompiled sources
- [x] 5.2 Classify each static found as pure/read-only, per-chunk scratch, or genuinely shared; record the classification with the evidence for each
- [x] 5.3 Confirm the already-checked result for `ReCalculateAll`, `ReCalculateCollide`, `ReCalculatePathFind` and `ReCalculateVisionBlocked` — that `IsoGridSquare.setMatrixBit` is the only static reached and it is pure
- [x] 5.4 Resolve every static classified as genuinely shared, or stop and revise the design; verify none remain unresolved on the pass the pool will run — resolution: loop 1 stays on the streamer thread (design Decision 5)

## 6. Remove the two known blockers

- [x] 6.1 Split `IsoChunk.loadInWorldStreamerThread()` into loop 1 (streamer thread) and loops 2–4 (pool-able), the latter allocating its own `ChunkGetter` and passing it to `RecalcAllWithNeighbours(boolean, GetSquare)` instead of binding the static; verify the game still loads chunks correctly with the pool width at 1
- [x] 6.2 Confirm nothing else reads `IsoChunk.chunkGetter` by searching the decompiled jar for references; verify the static is now unreferenced on the streaming path
- [x] 6.3 ~~Per-worker `getNew` deque~~ — not needed: workers never construct squares once loop 1 stays on the streamer thread (design Decision 5); verify by checking no `getNew` call is reachable from the pooled loops
- [x] 6.4 Run parity with pool width 1 against the stock baseline; verify captures are identical, proving the de-statication alone changed nothing

## 7. Scheduler and worker pool

- [x] 7.1 Implement in-order publication: a chunk is added to `IsoChunk.loadGridSquare` only after every chunk submitted before it; verify with a unit test that out-of-order completion still publishes in submission order
- [x] 7.2 Wake the streamer on enqueue: replace `threadLoop()`'s two fixed sleeps with a bounded park unparked by `addJob`, switchable with `wake=false`; verify in the instrumentation that queue wait drops from ~150 ms to a few ms and that an idle streamer wakes no more often than stock
- [x] 7.3 Implement the bounded worker pool, width configurable and capped below the processor count, keeping `WorldStreamer`'s thread owning the queue, the disk read and loop 1; verify width 1 behaves as stock and width > 1 shows concurrent passes in the instrumentation
- [x] 7.4 Add the runtime kill switch, pool-width and wake settings, readable and reportable at runtime; verify toggling to single-threaded reproduces stock behaviour without reinstalling
- [x] 7.5 Assert on entry to game-thread-gated pathfinding registration that the caller is the game thread or server main thread; verify a deliberate call from a worker fails loudly in a development build
- [x] 7.6 Handle a worker throwing: log the chunk coordinates and either retry on the streaming thread or leave the chunk unpublished; verify with an injected exception that the world is not left partially recalculated

## 8. Verify the whole thing

- [x] 8.1 Run parity at pool widths 2, 4 and the capped maximum against the stock baseline; verify every capture is identical
- [x] 8.2 Run parity twice at the same width above 1; verify the two captures are identical, proving output does not depend on scheduling
- [x] 8.3 Run the benchmark at each pool width, with and without wake-on-enqueue, and compare against the stock baseline; verify the report states the change in chunk latency, chunk-load throughput and worst-percentile frame time, and whether it exceeds noise
- [x] 8.4 Confirm no regression in worst-percentile frame time from starving the render thread; pick the shipped default pool width from this result, answering the second open question in design.md
- [ ] 8.5 Play a long session on the overridden build — driving, sleeping, crossing cells, loading and reloading saves; verify no crash, no visual artifact at chunk boundaries, and no pathfinding failure
- [x] 8.6 Record the honest result, including if the win is smaller than expected or absent; verify the recorded figures come from the harness rather than from a single observation
