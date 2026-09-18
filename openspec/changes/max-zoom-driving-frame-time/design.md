## Context

The current harness measures a scripted teleport route. It deliberately calls
`ensureNotInVehicle()` and moves the player with `teleportTo()`, so it cannot
measure vehicle physics, driving camera pan, or the renderer's maximum-zoom
behavior. The existing results show that streamer wake-up and parallel recalc
do not move frame time; the next measurement must isolate the main-thread
render path.

At the configured maximum zoom, `MultiTextureFBO2` enlarges the offscreen
render target with the zoom factor. `FBORenderCell` then maintains per-level
translucent source lists and, during every render, merges, de-duplicates, and
sorts them before applying camera visibility checks. The same render path can
invalidate chunk textures when cutaway state changes as the player crosses
squares.

## Goals / Non-Goals

**Goals:**

- Measure a real vehicle route at maximum zoom with enough metadata to compare
  render behavior across runs.
- Establish whether translucent list construction is a dominant contributor to
  the max-zoom driving tail.
- Reuse a valid translucent ordering between invalidations without changing
  object order, visibility, cutaway behavior, or dynamic object membership.
- Keep the cache switchable and make rollback independent of the benchmark
  harness.

**Non-Goals:**

- Replacing the existing teleport route or changing the chunk-latency
  benchmark.
- Fixing vehicle physics, lighting, cutaway traversal, FBO scheduling, GPU
  resolution, or PZDashboard behavior in this change unless a later measured
  decision explicitly expands scope.
- Moving rendering or Lua work to another thread.
- Changing save format, network payloads, or multiplayer semantics.

## Decisions

### Decision 1: Add a separate real-driving benchmark mode

Keep the current route as a stable chunk-streaming control and add a separate
mode backed by a fixed save with a player already in a vehicle. The driving mode
will use the game's vehicle movement/input path or a recorded input replay, not
coordinate teleports. It will force the effective zoom to the configured
maximum before the measured window and fail closed if the player is not driving.

The harness records zoom, offscreen dimensions, camera-pan setting, chunk-map
width, display/backend data, dashboard state, and route validity in the run
metadata. This prevents a high-resolution or renderer change from being
mistaken for a code optimization.

*Alternative considered.* Extending the existing teleport route with a boolean
`inVehicle` flag was rejected because teleporting bypasses vehicle physics and
does not exercise the camera's driving branch.

### Decision 2: Use GameProfiler plus lightweight counters for attribution

The built-in GameProfiler provides per-frame section timing without the large
overhead observed in the existing 10 ms JFR profile. The analysis will combine
its render/logic sections with lightweight counters for visible chunks, dirty
render levels, translucent candidates, cutaway invalidations, lighting updates,
vehicle physics, and Lua activity. MangoHud remains the independent frame-time
measurement.

JFR remains an optional stack-attribution tool for a follow-up investigation,
not the acceptance metric for this change. A profiled run that exceeds the
noise floor is reported as diagnostic only.

### Decision 3: Build the translucent list at cache invalidation time

For each player, chunk, and level, maintain a prepared list containing each
square at most once from the item, cutaway-frame, and translucent-object source
lists. Construct it in the same source precedence as today, then sort it with
the existing world-order key. The per-frame path iterates this prepared list
and retains the existing level, cutaway, and `IsOnScreen` checks.

The cache is invalidated whenever the source lists are cleared or rebuilt, or
when an object changes render classification or cutaway membership. A
defensive source signature check catches invalidation paths that do not pass
through the normal rebuild hook. Cache entries are discarded with their chunk
or level and never retain unloaded world objects.

*Alternatives considered.* Sorting the existing lists every frame was rejected
because it leaves the measured `contains` and sort work intact. A global list
for the whole view was rejected because chunk-level invalidation and cutaway
changes would make its lifetime and ownership harder to prove.

### Decision 4: Roll out behind a runtime switch

Add a `translucentCache` setting with an explicit disabled path that uses the
stock list construction. The benchmark compares disabled and enabled runs on
the same driving save. The cache becomes the default only if frame-tail
improvement exceeds noise and visual validation finds no missing, duplicated,
reordered, or incorrectly hidden objects.

### Decision 5: Validate render equivalence by trace and play session

During development, the cache can emit the ordered square/object identities and
the final visibility decisions for a bounded route window. The uncached and
cached traces must match for repeated runs. This is complemented by a manual
maximum-zoom driving session covering cell crossings, buildings, windows,
items, trees, and cutaways because world rendering has no existing full-state
parity capture.

## Risks / Trade-offs

- **Stale membership** -> invalidate at every source-list rebuild and compare a
  defensive source signature in development builds; disable the cache on any
  mismatch.
- **Dynamic objects change classification between frames** -> retain the
  existing per-frame render-info and visibility checks, and invalidate when
  render-layer membership changes rather than caching final draw calls.
- **Cache memory grows with the loaded chunk map** -> bound entries to the
  existing per-player chunk/level set and clear them when a level or chunk is
  unloaded.
- **A whole decompiled renderer class is fragile to game updates** -> use the
  existing build revision and class-hash guard, compile a round-trip copy first,
  and ship the cache disabled if the guard or signature checks fail.
- **The cache improves mean work but not spikes** -> require p99/p99.9 and
  spike-count improvement beyond noise before enabling it; otherwise keep the
  switch off and use the attribution to scope a follow-up budget change.
- **Vehicle benchmark is not deterministic enough** -> use a fixed save and
  recorded input/replay state, reject invalid setup, and compare multiple runs
  against the existing noise discipline.

## Migration Plan

1. Add and validate the real-driving benchmark while the cache is disabled.
2. Capture the maximum-zoom A/B baseline and the renderer attribution report.
3. Install the renderer override with `translucentCache=false`, run trace and
   visual validation, then benchmark with the cache enabled.
4. Enable the cache by default only after the acceptance criteria pass. Rollback
   is setting `translucentCache=false` or uninstalling the loose override class.

## Open Questions

- Which existing bench save has a stable, reproducible vehicle and route state
  suitable for the fixed driving capture? This can be resolved while building
  the benchmark mode without changing the external requirements.
