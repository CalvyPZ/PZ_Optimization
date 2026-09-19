# Plan: the load after "Continue"

Written 2026-09-19 from the JFR recording of run `waits-uncap-gl-1-20260919-152051`
(1 ms sampling, wait/park/sleep events at 0 ms threshold, uncapped frame rate,
native Linux build, 16 cores). All times are seconds after process launch.

## What happens between Continue and the first world frame

The harness presses Continue at t+10.4. The world is ready (player square
exists, `IngameState` running) at t+20.7, so the load is **~10.3 s**; the game's
own line says `game loading took 10 seconds`. It splits like this:

| phase | thread | wall | what |
|---|---|---|---|
| A. `LuaManager.LoadDirBase("server")`, `GameLoadingState.enter` (FBOs, shaders, latestSave.ini) | MainThread | 0.5 s | t+10.4–10.9 |
| B. `assetLock1`: wait until `OutfitManager.isLoadingClothingItems()` is false | GameLoadingThread waits | 0.3 s | clothing textures still decoding on the 4 file threads |
| C. `IsoWorld.init()` | GameLoadingThread | **5.5 s** | t+11.2–16.7, detail below |
| D. `assetLock2`: wait until `ModelManager.isLoadingAnimations()` is false and the file system has no work | GameLoadingThread waits | **3.6 s** | t+16.7–20.3: 3,990 animation `.X` files (jassimp) until t+18.2, then 218 depth-map tilesets until t+20.3 |
| E. `FBORenderChunkManager.gameLoaded`, `Bullet.initPhysicsMeshes`, `SendDone`, 350 ms fade to black | GameLoadingThread / MainThread | 0.4 s | t+20.3–20.7 |
| F. `IngameState.enter`: `processAllLoadGridSquare` (361 chunks × `loadInMainThread`), `OnGameStart`/`OnLoad` | MainThread | 0.4 s | t+20.7–21.1 |

`IsoWorld.init` (phase C) in order, from the sample spans of its direct callees:

| t+ | wall | step | notes |
|---|---|---|---|
| 11.2 | 0.05 | `IsoMetaGrid.CreateStep1` | spawns 8 `MetaGridLoaderThread`s (lot headers + `ZombieVoronoi`), fine |
| 11.3–12.1 | 0.9 | tile definitions: `LoadTileDefinitionsPropertyStrings` ×7, `LoadTileDefinitions` ×7, `loadedTileDefinitions` | single thread; parse + `IsoSpriteManager.AddSprite` + `Texture.getSharedTexture` (render-context round trips) |
| 12.2–12.9 | 0.7 | `ZomboidRadio.Init`, `WorldDictionary.init`, `PostWorldDictionaryInit`, `CreateStep2`, `VirtualZombieManager.init` | many small steps, all serial |
| 12.9–14.4 | 1.5 | Lua `OnLoadMapZones` (`doMapZones` reads `objects.lua` of every map) and, from Lua, `IsoWorld.checkVehiclesZones` → `IsoMetaGrid.checkVehiclesZones` | `checkVehiclesZones` alone is **0.6 s**: O(n²) over the vehicle zones, and the `DebugType.Vehicle.debugln("..." + a.name + ...)` string is built for every duplicate found whether or not the log is on (800 of its 884 samples are in `String.concat`) |
| 14.4–15.0 | 0.6 | `Basements.beforeLoadMetaGrid`, `BuildingRoomsEditor.load` (`checkBuildingAndRoomIDs` ×2), `IsoMetaGrid.load` (map_meta.bin), 16,992 zones (`loadZone` → `IsoMetaCell.addZone` → `getChunk`/`intersects`), `processZones`, `ItemConfigurator.Preprocess` | serial |
| 15.0–15.5 | **0.5** | `MapCollisionData.init` | for every cell × 32 × 32 chunks calls `LotHeader.getZombieIntensityForChunk`, which does `MapFiles.getLotHeader` (thread-local `TLongObjectHashMap` + `String.format` cache + `HashMap` lookup) per chunk instead of once per cell; 343 samples, 60 % in `HashMap.getNode`/`ThreadLocal.get` |
| 15.5–15.6 | 0.1 | `AnimalPopulationManager.init`, `ZombiePopulationManager.init`, `PathfindNative.init`, `SpawnPoints`, `WorldStreamer.create`, `CellLoader.LoadCellBinaryChunk` | |
| 15.6–16.3 | **0.7** | `while (WorldStreamer.isBusy()) Thread.sleep(100)` | the 361 chunks of the 19×19 chunk map: streamer reads the files, the pzopt recalc pool (4 workers) does the grid recalc. The 4 workers are saturated for the whole window; 12 idle cores |
| 16.3–16.7 | 0.4 | `IsoCell.LoadPlayer` → `IsoPlayer.<init>` → `AdvancedAnimator.init` (`ActionGroup.load` parses the animation-state XML tree, `File.listFiles`) | serial, per player |

Sampling caveat: JFR samples at most 5 Java threads and 1 native thread per
1 ms tick, so during the phases where 8 meta-grid loaders and 4 file threads
were busy the loader thread is under-sampled; the spans above are reliable,
the per-step CPU shares are indicative. Step 0 below adds exact timestamps.

## What phase D is really waiting for

- **Animations.** `ModelManager.create()` (at boot) registers 3,990 `.X`
  animation files as `FileTask_LoadAnimation` at priority 4. Textures are
  priority 7, so the animations only start once the texture packs are decoded
  (t+13.5) and then run on all 4 file threads (`FileSystemImpl`:
  `numThreads = cores <= 4 ? 2 : 4`, at most 16 tasks in flight, one
  completion/submission pass per game-thread frame). ~4.4 s × 4 threads of
  jassimp import + `ProcessedAiScene` work, purely CPU. This is the single
  largest fixed cost and the one that scales directly with thread count.
- **Depth maps.** `TileDepthTextures.loadDepthTextureImages` queues one
  `LoadTask` per `DEPTH_*.png` (218). `LoadTask.call` runs its whole body,
  including the PNG decode and the per-tile GPU upload, inside
  `synchronized (this.textures)`, so the 218 tasks run **one at a time**
  (2 s of one thread; 6.1 s of lock waiting summed over the other threads).
- **Texture decode throttle.** `FileTask_LoadPackImage` sleeps 20 ms whenever
  more than 50 MB (`0x3200000`) of direct buffers are outstanding
  (`TextureIDAssetManager.waitFileTask`), i.e. whenever the render thread is
  behind on uploads. 0.7 s of sleeps per file thread during the texture phase.
- The file-task pump only runs from `GameWindow.logic`, so during
  `GameWindow.init` (t+3–9, before the main menu) the 4 file threads are idle
  although the animation and texture tasks are already queued.

## Boot, before Continue (not the target, listed for completeness)

Launch to main menu is ~9 s on this machine: FMOD bank loading on the main
thread 1.5 s (t+3–4.5), fonts/JSON/Lua expose 1 s, script parsing 1 s
(`Item.DoParam`), `TileGeometryFile.parseFile` **0.7 s** in
`ScriptParser.stripComments` (repeated `StringBuilder.replace` on a large
string: quadratic), Lua `LoadDirBase` (shared + client) 2.6 s. The logo and
photosensitivity screens are already skipped by the shims.

## Plan

Each step: implement behind a `pzopt.properties` key, build, `scripts/pzopt.sh
reinstall`, one harness run with `--record`, check the load-phase table and the
recording (loading screen, first world frames, no missing textures/animations),
then commit. Order is by expected gain per risk; every step is independent of
the ones after it.

### 0. Measure the load precisely

- `pzopt.LoadTrace`: a `System.out` tee installed when the first override
  loads; lines matching the game's own phase markers (`... start`/`... end`,
  `STATE: ...`, `game loading took`, `[pzopt] harness:`) are copied with an
  epoch-ms stamp to `~/Zomboid/pzopt-loadtrace.out`. console.txt is unchanged.
- `AutoStart` records the epoch when it first sees `GameLoadingState`
  (= Continue pressed) and when it leaves; `Harness` already records world
  ready in `pzopt-schedule.out`. Both go into the trace.
- `harness/loadtime.py <run>`: prints Continue → world-ready and the phase
  table (A–F above) from the trace, so every later run is compared the same way.
- Run recipe (drive mode so the harness arms click-to-start, a short route so
  the run is ~45 s): `harness/run.sh --label load-<x> --mode drive --flag route=E:60
  --route-seconds 8 --record --no-dashboard --prop instrument=true`.

### 1. Asset pipeline: use the cores (phase D, B; ~3.5 s)

New overrides (Vineflower, listed in `scripts/build.sh` and
`scripts/regen-overrides.sh`), all guarded by `Overrides.enabled()`:

1. `zombie.fileSystem.FileSystemImpl`: thread count `fileThreads` (default
   `max(4, cores - 2)`, stock 4) and in-flight cap `fileInflight` (default
   `4 × threads`, stock 16). Expected: animations 4.4 s → ~1.3 s on 14
   threads; textures 4 s → ~1.2 s, which also empties `assetLock1` sooner.
2. `zombie.tileDepth.TileDepthTextures`: `LoadTask.call` decodes outside the
   lock; the lock only guards the `tilesets` map (`putIfAbsent`-style guard
   against double loads). Expected: 2 s → ~0.2 s.
3. `zombie.core.textures.TextureIDAssetManager`: direct-buffer budget
   `textureBufferMb` (default 256, stock 50) so decoders do not sleep waiting
   for the render thread while the loading screen is up.
4. Later, if the trace shows the animation queue still starts late: pump the
   file system during `GameWindow.init` too (a `GameWindow` edit; the class is
   already overridden), so the 4 s of texture/animation work overlaps the
   Lua/script loading before the main menu.

### 2. `IsoWorld.init` CPU on the loader thread (phase C; ~1.5 s)

1. `zombie.MapCollisionData.init`: resolve the `LotHeader` of a cell once and
   read its 1,024 intensities directly (same values, same JNI calls, same order).
   Expected 0.5 s → ~0.05 s.
2. `zombie.iso.IsoMetaGrid.checkVehiclesZones`: dedupe by `(x, y, w, h)` with
   a hash set, keep the first zone (stock keeps the earlier index), build the
   debug string only when `DebugType.Vehicle` is enabled. Expected 0.6 s → ~0.
3. Recalc pool at load: `loadWorkers` (default `cores - 2`) workers while
   `GameLoadingState.loader` is alive, shrinking back to `workers` for play
   (a `ThreadPoolExecutor` whose core size is adjusted on submit). Output is
   order-published already, so parity holds. Expected 0.7 s → ~0.25 s.
4. Then, from the trace: `IsoPlayer.<init>`/`AdvancedAnimator.init` (0.4 s),
   `IsoMetaGrid.loadZone`/`addZone` (0.2 s), `BuildingRoomsEditor.
   checkBuildingAndRoomIDs` (0.15 s), tile definitions (0.9 s: seven files
   parsed serially; candidates are parsing the seven files on workers and
   registering sprites in file order, which keeps sprite IDs identical).

### 3. The hand-over (phases E, F; ~0.5 s)

- `IngameState.enter` → `processAllLoadGridSquare` runs `loadInMainThread`
  (the boundary recalc against neighbours) for all 361 chunks on the game
  thread. Candidate: run it on the recalc pool in chunk order, publishing to
  the game thread as today (`Guard.assertGameThread` marks the parts that must
  stay: pathfinding registration, vehicles, animals).
- The 350 ms fade to black in `GameLoadingState.exit` and the 100 ms sleep
  granularity of the two `isBusy` polling loops are small and cosmetic; skip
  unless the rest lands.

### 4. Boot (optional, after 1–3)

`ScriptParser.stripComments` single-pass rewrite (0.7 s), FMOD bank loading on
a worker while fonts and scripts parse (1.5 s), file-task pump during init.

### Verification per step

- `scripts/build.sh`, `scripts/test.sh`, `scripts/pzopt.sh reinstall`.
- One recorded run per step (`--record`); compare `harness/loadtime.py` against
  the step-0 baseline; scrub the recording (ffmpeg contact sheet) at the loading
  screen, the first world frame and a few seconds of driving: textures, depth
  (tile sorting), animations, tile definitions and vehicles must look as before.
- Step 2.3 additionally: `scripts/accept.sh` (recalc parity gate).
- Keep `instrument=true` so `pzopt-*.out` exist for `compare.py`; use
  `--no-dashboard` so the PZDashboard 2 s spike does not confuse the trace.

## Results (2026-09-19, runs `load-*`, `harness/loadtime.py`)

Same bench save, drive mode, 60-tile route, `--no-dashboard`, direct launcher,
one recorded run per step; the contact sheets (`harness/loadsheet.sh`) show the
loading screen, the first world frame and the first seconds of driving
identical to the baseline for every kept step.

| Continue → world ready | run | notes |
|---|---|---|
| **10.08 s** | `load-base` | all overrides at their old defaults |
| 8.90 s | `load-s1` | step 1 with 14 file threads and a 256 MB decode budget: **rejected**, one 5.05 s frame a few seconds into the world (uploads piled up on the render thread; the peer session's `wl-gl60-1` saw the same 3.4 s frame with these classes) |
| 8.32 s | `load-s1b` | step 1, stock 50 MB budget: phase D 3.5 → 0.0 s, but the game's 8 meta-grid loader threads slow from 1.5 to 4.0 s beside 14 file threads |
| 7.73 s | `load-s2` | + loader CPU fixes: `checkVehiclesZones` is called 11× per load over 9,690 zones (0.6 s → 0), `MapCollisionData.init` 0.52 → 0.13 s |
| 7.30 s | `load-s3` | + recalc pool 14 wide while loading: no gain in the chunk phase (per-chunk recalc 6 → 20 ms from contention) |
| 6.65 s | `load-s3f8` | 8 file threads instead of 14: meta-grid loaders back to 2.2 s, assets still done before the loader needs them |
| **6.53 s** | `load-final` | the adopted defaults: `fileThreads = cores/2`, `fileInflight = 4×`, `textureBufferMb = 50`, `parallelDepthMaps`, `loaderCpuFixes`, `loadWorkers = cores/2` |

Phase table of the adopted run against the baseline (seconds):

| phase | base | final |
|---|---|---|
| A Continue → loader thread | 0.72 | 0.53 |
| C1 meta-grid step 1, tile definitions | 1.06 | 1.13 |
| C2 tile defs → meta-grid finished | 0.44 | 1.14 |
| C3 map zones, Lua, `checkVehiclesZones` | 2.24 | 1.45 |
| C4 `MapCollisionData.init` | 0.52 | 0.20 |
| C6 initial chunk load | 0.70 | 0.91 |
| C7 player load | 0.39 | 0.56 |
| D wait for animations / file tasks | 3.51 | 0.10 |
| F `IngameState.enter` → world ready | 0.41 | 0.42 |

What the numbers say: the load is now bounded by the loader thread's own
serial work (C1–C3, ~3.7 s) running beside the asset decode, and every extra
worker thread past half the cores slows that serial path more than it speeds
the assets (16 cores here). C2 and C6 are 0.3–0.5 s worse than stock in the
final run because the meta-grid loaders and the chunk recalc share the cores
with the file pool; the trade is still 3.5 s net. In a real session the player
spends seconds at the main menu, during which the file pool now finishes the
textures and animations, so the load after Continue is shorter than measured
here (the harness presses Continue the instant the menu appears).

Not done from the plan: overlapping the file pump with `GameWindow.init`
(boot), moving `IngameState.enter`'s boundary recalc to the pool (0.4 s), the
tile-definition parse (0.9 s serial), and the boot items (`stripComments`,
FMOD banks). The next thing to attack is C3: the Lua `OnLoadMapZones` handlers
(`doMapZones` reading `objects.lua` for every map) are ~1 s of Kahlua on the
loader thread and are Lua, not Java.

Pitfalls found on the way (also in `docs/override-edits.md`): a load-marker
log in `IsoMetaGrid`'s static initializer kills the game at boot (it runs
inside `IsoWorld`'s own static init and `DebugLog` reads `IsoWorld.instance`
for the frame number), and `FileSystemImpl` loads inside `GameWindow`'s static
initializer, before the log exists, so the harness hooks in
`Overrides.onClassLoaded` now wait for a later override.
