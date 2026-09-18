## 1. Real-driving benchmark fixture

- [ ] 1.1 Inspect the available saves and select or create a fixed vehicle fixture with a stable driver, route origin, vehicle, and surrounding chunks; verify the fixture loads repeatedly with the player still driving
- [ ] 1.2 Add a separate harness driving mode that follows vehicle movement or a recorded input replay without calling `ensureNotInVehicle()` or `teleportTo()`; verify the route log identifies vehicle movement and rejects an invalid non-driving setup
- [ ] 1.3 Force the effective zoom to the configured maximum before the route and record zoom, offscreen dimensions, pan-camera setting, chunk-map width, resolution, renderer/backend, and dashboard state; verify the metadata is present in successful and rejected run directories

## 2. Frame attribution and baseline

- [ ] 2.1 Enable and parse the game's GameProfiler output for the driving route, preserving per-frame render and logic sections; verify section frame IDs align with the in-game frame log without relying on JFR timing
- [ ] 2.2 Add lightweight development counters for visible chunks, dirty render levels, translucent source/list sizes, cutaway invalidations, lighting updates, vehicle physics updates, and Lua/dashboard activity; verify counters are bounded to the route window and add negligible overhead in a control run
- [ ] 2.3 Run repeated A/B baselines at 100% and maximum zoom, with driving camera pan on and off, and with PZDashboard enabled and disabled; verify p99, p99.9, spike counts, MangoHud values, and the noise floor are reported for every comparable variant
- [ ] 2.4 Decide from the baseline whether translucent list construction is a dominant contributor; record the decision and its evidence in the attribution report, and leave the cache disabled if it is not

## 3. Renderer cache implementation

- [ ] 3.1 Regenerate and round-trip the renderer override with the existing game-build and class-hash guards; verify the unmodified override builds, loads, and can be removed without changing the game jar
- [ ] 3.2 Add the `translucentCache` runtime switch and uncached fallback; verify startup/run metadata reports the setting and disabled mode follows the stock path
- [ ] 3.3 Build a per-player, per-chunk-level translucent list with the stock source precedence, duplicate elimination, and world-order comparator; verify a development trace matches the uncached ordered square/object identities
- [ ] 3.4 Reuse the prepared list across camera movement while retaining per-frame level, cutaway, and screen visibility checks; verify repeated frames do not rebuild or sort an unchanged list
- [ ] 3.5 Invalidate and release cache entries when source membership, render classification, cutaway membership, level data, or chunk lifetime changes; verify a forced content/cutaway change is visible on the next eligible frame and no unloaded object is retained

## 4. Correctness and performance gate

- [ ] 4.1 Run uncached and cached trace comparisons across repeated maximum-zoom driving routes; verify no missing, duplicate, reordered, or incorrectly hidden translucent objects are reported
- [ ] 4.2 Play the cached build through cell crossings, buildings, windows, items, trees, and cutaways; verify no visual artifact, crash, stale object, or pathfinding failure occurs
- [ ] 4.3 Benchmark the cache disabled and enabled at the selected driving scenario; verify p99, p99.9, spike counts, chunk latency, and MangoHud frame time, and reject the default if the frame-tail improvement does not exceed noise
- [ ] 4.4 If the cache is not the dominant cause or fails its gate, keep it disabled and record the next measured target instead of enabling an unvalidated cutaway, lighting, FBO-budget, render-scale, or dashboard change

## 5. Wrap up

- [ ] 5.1 Record the final attribution, A/B results, cache decision, rollback setting, and run-directory evidence in the project performance documentation
- [ ] 5.2 Run the existing unit tests, build guard, and acceptance checks; verify the new benchmark artifacts do not alter the existing teleport route or chunk-parity gate
