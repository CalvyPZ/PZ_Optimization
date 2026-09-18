## Why

The existing benchmark proves that streamer wake-up and parallel chunk recalc reduce chunk latency, but they do not improve frame time. Its route also removes the player from vehicles and teleports tile-by-tile, so it does not exercise the reported worst case: a real vehicle at the 250% zoom level with driving camera pan enabled. In that mode the main-thread render path expands the offscreen target, repeatedly evaluates translucent objects, and can invalidate cutaway and chunk textures as the player crosses squares.

## What Changes

- Add a reproducible benchmark mode using a fixed save with the player driving a vehicle, the zoom forced to the configured maximum, and a fixed input or route replay instead of teleportation.
- Record the scenario inputs and render state with every run: effective zoom, offscreen dimensions, driving-camera-pan state, chunk-map width, renderer/backend, and resolution.
- Extend attribution with per-frame GameProfiler sections and counters for translucent candidates, dirty FBO levels, cutaway invalidations, lighting updates, vehicle physics, and Lua work. Keep MangoHud as the external frame-time check; do not use the current high-overhead JFR profile as the primary measurement.
- Add A/B coverage for maximum zoom versus 100% zoom, driving camera pan on versus off, and PZDashboard enabled versus disabled. Report p99, p99.9, spike counts, and the noise floor.
- Cache the de-duplicated, world-ordered translucent-square list for each render-chunk level. Rebuild it only when the underlying item, translucent-object, or cutaway lists change; reuse it during ordinary camera movement while preserving stock render order and visibility checks.
- Gate the cache behind a kill switch and parity/visual checks. Do not enable a cutaway, lighting, FBO-rebuild budget, render-resolution cap, or PZDashboard behavior change unless the new benchmark identifies it as a dominant contributor; those remain measured follow-ups.

## Capabilities

### New Capabilities

- `max-zoom-driving-benchmark`: Measures frame-time behavior on a real vehicle route at maximum zoom, with scenario metadata, section attribution, and controlled A/B variants.
- `translucent-render-cache`: Reuses a correctly ordered and de-duplicated translucent render list between cache invalidations without changing visible output.

### Modified Capabilities

None.

## Impact

- **Harness and benchmark save**: `pzopt.Harness`, the Lua/game launch flow, run metadata, and analysis scripts need a vehicle route mode and render-state measurements. Existing teleport and chunk-latency benchmarks remain unchanged.
- **Game rendering code**: the implementation will likely shadow `zombie.iso.fboRenderChunk.FBORenderCell` and add a small cache/versioning helper. This is a high-coupling decompiled class and must be guarded by the game-build checks.
- **Profiling and reports**: GameProfiler recording parsing, frame attribution, and benchmark documentation gain render-specific sections and counters.
- **Correctness risk**: translucent ordering, cutaway visibility, dynamic object membership, lighting changes, and cross-chunk invalidation must invalidate the cache correctly. The cache must be disabled automatically if parity or visual checks fail.
- **Performance trade-off**: the change targets main-thread render work at maximum zoom; it does not change chunk data, vehicle physics semantics, save format, or network payloads.
