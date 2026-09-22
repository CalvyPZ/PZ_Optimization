# Edits to the overridden game classes

`src/overrides/` is **not in the repository**: it holds decompiled game code.
Regenerate it with `scripts/regen-overrides.sh` (Vineflower output of
`zombie.iso.IsoChunk` and `zombie.iso.WorldStreamer` from the installed jar,
revision `b0bbce05d5`), then re-apply the edits below by hand. Every edit is
marked `// pzopt:` in the working copy. One decompiler fix is needed first:
the `switch` expression on `carSpawnRate` in `IsoChunk.addVehicles` lacks a
`default -> chance;` arm.

## zombie.iso.IsoChunk

1. **Load marker.** A `static {}` block at the top of the class calling
   `pzopt.Overrides.onClassLoaded("zombie.iso.IsoChunk")`.
2. **Split of `loadInWorldStreamerThread()`.** The method becomes
   `recalcLoop1(); recalcPooled();`. `recalcLoop1()` is the original first
   loop (per level/square: create missing squares, `ensureNotNull3x3`,
   `RecalcProperties`). `recalcPooled()` is everything after it — the rain/roof
   column pass, the `RecalcAllWithNeighbours(true, getter)` pass, and the
   `propertiesDirty = true` pass — with one difference: instead of binding the
   static `chunkGetter` (`assert chunkGetter.chunk == null; chunkGetter.chunk =
   this; … chunkGetter.chunk = null;`) it allocates a local
   `IsoChunk.ChunkGetter`, sets its `chunk` to `this`, and passes that. Both new
   methods are `public`. At the top of `recalcPooled()`, a dev-only injected
   failure: if `pzopt.Guard.DEV` and `pzopt.RecalcPool.failChunk` equals
   `"wx,wy"` of this chunk, clear `failChunk` and throw
   `IllegalStateException`.
3. **Frame hook.** First statement of `update()`:
   `pzopt.Stats.frameTick(IsoCamera.frameState.frameCount);`.
4. **Dev-build guards** at the two pathfinding-registration gates: a
   `pzopt.Guard.assertGameThread(...)` call immediately before the
   `if (… Thread.currentThread() == GameWindow.gameThread || … GameServer.mainThread)`
   test in the level-change path, and immediately before the
   `MapCollisionData.instance.addChunkToWorld(this)` block in
   `doLoadGridsquare()`.

Imports added: `pzopt.Guard`, `pzopt.RecalcPool`.

## zombie.iso.WorldStreamer

1. **Load marker + settings line.** A `static {}` block calling
   `pzopt.Overrides.onClassLoaded("zombie.iso.WorldStreamer")` and
   `pzopt.Log.info("settings: " + pzopt.Config.describe())`.
2. **Streamer wake-up.** In `create()`, the worker lambda calls
   `pzopt.StreamerWake.register()` before its loop. In `addJob(...)`, after
   `this.jobQueue.add(chunk)`: `pzopt.StreamerWake.signal()` (preceded by
   `pzopt.Stats.onEnqueued(chunk)`). In `threadLoop()`, the two
   `Thread.sleep(140L)` calls (the one taken when `jobList` is empty, and the
   one at the end of the loop when a player exists) become
   `pzopt.StreamerWake.idle(140L)`. The `Thread.sleep(20L)` and
   `Thread.sleep(0L)` are unchanged.
3. **Retries.** In `threadLoop()`, `pzopt.RecalcPool.runRetries();` just
   before the loop that drains `jobQueue` into `jobList`.
4. **Pool hand-off in `DoChunkAlways(chunk, fromServer)`.** Around the body:
   `pzopt.Stats.Timing timing = pzopt.Stats.begin(chunk);` before
   `chunk.LoadChunk(...)`, and `timing.loadEndNs = System.nanoTime();` after
   `VehiclesDB2.instance.loadChunk(chunk)`. In the non-Convert/non-SoftReset
   branch: if `pzopt.RecalcPool.active()` and the current thread is
   `this.worldStreamer` and `chunk.refs` is non-empty, call
   `chunk.recalcLoop1()` then `pzopt.RecalcPool.submit(chunk, timing)` inside
   the existing `try/catch (Exception) { ExceptionLogger.logException(ex); }`,
   and `return` on success (the pool publishes to `IsoChunk.loadGridSquare`);
   if loop 1 threw, fall through. Otherwise the stock path, with
   `timing.recalcStartNs`/`thread`/`recalcEndNs` recorded around
   `chunk.loadInWorldStreamerThread()` and `pzopt.Parity.capture(chunk)` after
   it. After the stock `IsoChunk.loadGridSquare.add(chunk)`:
   `timing.publishNs = System.nanoTime(); pzopt.Stats.done(chunk, timing);`.
5. **`isBusy()`** additionally returns true when
   `pzopt.RecalcPool.inFlight() > 0`.
6. **`stop()`**: after the streamer thread has ended ("stop 3" debug line) and
   before `this.worldStreamer = null`: `pzopt.RecalcPool.drain();
   pzopt.Stats.flush();`.

## zombie.iso.ChunkSaveWorker (added 2026-09-18)

Regenerated with `scripts/regen-overrides.sh` (now lists this class); the
pristine Vineflower output compiles as is.

1. **Load marker.** A `static {}` block calling
   `pzopt.Overrides.onClassLoaded("zombie.iso.ChunkSaveWorker")`, plus two
   static fields: the nanosecond stamp of the last ancillary hot save and a
   counter of skipped drains.
2. **Hot-save throttle.** In `Update(IsoChunk)`, the branch that calls
   `HotsaveAncilliarySystems()` when the save queue has just drained now runs
   it only if `pzopt.Config.HOTSAVE_INTERVAL_SEC` is 0 (stock), the overrides
   are disabled, or at least that many seconds have passed since the previous
   hot save; otherwise the drain is counted and skipped (logged with the count
   when the next hot save runs). Background: the hot save serialises the whole
   meta grid (`IsoMetaGrid.saveToBufferMap`, building/room counts of every
   meta cell) on the game thread via `MainThread.invokeOnMainThread`, and
   while moving the queue drains about every 0.45 s (JFR, `attr-jfr-1`), so it
   was a 2–5 ms game-thread stall twice a second.

## zombie.core.VBO.GLVertexBufferObject (added 2026-09-18)

Regenerated the same way (no inner classes). All edits are guarded by
`pzopt.Config.PERSISTENT_VBO && pzopt.Overrides.enabled()` and by the
`GL_ARB_buffer_storage` / OpenGL 3.2 capabilities; otherwise every method is
stock.

1. **Load marker** in a `static {}` block.
2. **Persistent mapping for fixed-size buffers.** New private state: a
   `persistent` flag, a per-buffer fence handle, a static list of buffers
   unmapped since the last fence, and dev counters (waits, wait time, stalls).
   The no-argument `map()` (used by the sprite `RingBuffer`, the water
   `SharedVertexBufferObjects` and the world-map VBOs, all constructed with a
   size) takes a new path: the first call allocates immutable storage with
   `glBufferStorage(MAP_WRITE | MAP_PERSISTENT | MAP_COHERENT)` and maps it
   once with the same flags; later calls only reset the buffer position. Before
   handing the buffer out it (a) creates one `glFenceSync` per buffer unmapped
   since the last map — those buffers' draw calls were issued in between — and
   (b) waits on this buffer's own fence with `glClientWaitSync(FLUSH, 1 s)`, so
   the CPU never overwrites a batch the GPU is still reading (the stock ring
   has 128 buffers, which is the depth of the pipeline before a wait happens).
   `unmap()` on a persistent buffer only records it in the pending list;
   `clear()` returns early (no `glBufferData` on immutable storage);
   `doDestroy()` unmaps for real and deletes the fence.
   Stock behaviour was `glBufferData` (orphan) + `glMapBufferRange(WRITE |
   INVALIDATE_RANGE | UNSYNCHRONIZED)` per 64 KB batch, ~25 % of render-thread
   samples at max zoom (`attr-jfr-z25`). Default off since 2026-09-19
   (evening): with the in-game 240 fps limiter back, two 120 km/h drives with
   it on and off had the same frame profile (mean 4.2 ms, p99 7.2 / 7.3 ms), and
   one stationary run with it on drew a whole building lot floor opaque black
   for a minute (`artfix-opt120-2`), which its fence logic is the only edit
   able to cause. Re-enable with `--prop persistentVbo=true` for uncapped runs.
   2026-09-20 afternoon: the black chunk squares seen with it on were the light-info
   chunk gate (see the FBORenderCell entry of that day), not the fence logic: a
   `glFinish` before every map (`persistentVboFinish`) still showed them, and the
   fix in FBORenderCell removes them with the fences untouched.
3. **Diagnostics and variants added during that bisect (2026-09-20), all off by
   default and marked `// pzopt:`:** `persistentVboFrameFence` (one fence per frame
   from `RenderThread` after `SpriteRenderer.postRender`, `pzoptFrameEnd()`, kept in
   an 8-entry ring; a slot is rewritten only after the frame it was drawn in is
   done; `persistentVboFrameLag=N` also waits for the N following frames);
   `persistentVboSlots=K` (K immutable storage buffers per `GLVertexBufferObject`,
   rotated and re-bound on every `map()`, so the 128-buffer ring reuses a slot
   after 128*K batches; slot 0 keeps the original buffer id);
   `persistentVboCoherent=false` (MAP_FLUSH_EXPLICIT plus `glFlushMappedBufferRange`
   at `unmap()` instead of MAP_COHERENT); `persistentVboDelayUs` (CPU-only park per
   map); `persistentVboFinish` (`glFinish` per map). With `instrument=true` a
   "persistent VBO:" line every 5 s counts maps, maps per frame, per-batch and
   per-frame fence waits and stalls.

## zombie.iso.fboRenderChunk.FBORenderCell (added 2026-09-18)

Regenerated with Vineflower; three decompiler fixes were needed before it
compiled: two dropped `boolean` declarations in `renderTilesInternal`
(the `runChecks` / `recalculateGridStacks` result variables), and explicit
comparator types for the three `timSort.doSort` lambdas (world inventory
objects, chunks by lighting counter, translucent squares). Every fix is marked
`// pzopt: decompiler fix`.

1. **Load marker** in a `static {}` block before the private constructor.
2. **Trees in the chunk texture.** `isTreeRenderedEveryFrame(IsoObject)`
   returns `false` when `pzopt.Config.TREES_IN_CHUNK_TEXTURE` is set and the
   overrides are enabled (stock: `object instanceof IsoTree`). With that, a
   tree is classified `Translucent` (drawn every frame) only while it is in
   the player stencil (`isTranslucentTree`), fading, wind-animated or carrying
   render effects; every other tree bakes into its chunk-level texture, and
   the existing `checkTreeTranslucency` pass invalidates the level (flag 4096)
   when a tree changes state. Two additions (2026-09-19, evening): a tree whose
   texture is not ready yet (`Asset.isReady`) stays per frame and
   `checkTreeTranslucency` re-dirties its level when the texture arrives; and
   `renderMinusFloor(IsoObject)` bakes a tree with `FBORenderTrees.current`
   temporarily null (`treeBakeDirect`, default on), so `IsoTree.render` takes
   the plain sprite path. The batch path (`FBORenderTrees` in chunk-texture
   mode) dropped most JUMBO trees around town buildings (they never appeared,
   even after a forced redraw), while the plain path draws every tree.
   Per-frame JUMBO trees cost 8.1 ms mean on the max-zoom route versus 4.2 ms
   baked, so this is the difference between the edit paying off and not. Measured on the max-zoom teleport route
   (`trees-1` vs `pvbo-1`): frame mean 6.2 → 5.2 ms, p99 18.7 → 17.4 ms, GPU
   busy 84 → 61 %.
3. **Windows and glass doors in the chunk texture.** In
   `isObjectRenderLayer_Translucent`, the `object instanceof IsoWindow` and
   the `doorTrans` door clauses are skipped when
   `pzopt.Config.WINDOWS_IN_CHUNK_TEXTURE` is set (they still go per-frame
   through the fading / obscuring-player clause that follows).
4. **`Translucent`-flagged tiles in the chunk texture.** A private static
   helper `pzoptPerFrameTranslucentTile(IsoSprite)` returns
   `depthFlags & 2 != 0` unless `pzopt.Config.TRANSLUCENT_TILES_IN_CHUNK_TEXTURE`
   is set; it replaces the three literal `depthFlags & 2` tests (the early
   `return true` in `isObjectRenderLayer_Translucent`, the early `return
   false` in `isObjectRenderLayer_MinusFloor`, and the `TranslucentSE` /
   `MinusFloorSE` choice in `calculateObjectRenderLayer`). Default off since
   2026-09-19 (evening): the flag is the tileset property `Translucent`
   (road decals, dirt patches, puddles), and baked into the opaque chunk
   texture those tiles come out as opaque black one-tile rectangles on the
   floor (the maintainer's screenshot, walking, not only at speed). The per-frame
   pass draws about 20 of them per frame; not worth it.
5. **Dev counters** (only with `instrument=true`): `renderTranslucent(IsoObject)`
   and `renderTranslucent(IsoGridSquare)` count what the per-frame pass draws
   by kind (window, door, tree, Translucent-flagged tile with a per-tileset
   tally, animating, fading, other, squares); `renderInternal()` logs the
   per-frame averages every 1800 frames as "translucent pass per frame".
6. **Bake budget** (`pzopt.Config.BAKE_BUDGET`, 0 = stock). Two new fields
   (bakes started this frame, the set of textures deferred this frame; both
   reset at the top of `performRenderTiles`) and a block at the top of the
   render decision in `renderOneLevel`, before `beginRenderChunkLevel`: when a
   level is dirty and, at its texture's lowest level, the frame has already
   started `BAKE_BUDGET` bakes, the texture is deferred (its upper level follows
   the decision through the set). Since 2026-09-19 (evening) only a never-baked
   level (`DIRTY_CREATE` still set) can be deferred: a re-bake of a texture that
   is already on screen (obscuring set, trees, cutaways, lighting, object
   changes) always lands in the same frame, because drawing the stale texture
   for a frame while the per-frame translucent list already reflects the new
   state showed windows and glass doors flickering as the car passed buildings. A deferred level whose texture was baked
   before (`DIRTY_CREATE`, 512, no longer set) takes the existing "clean"
   path — the manager's current chunk is pointed at its texture,
   `endRenderChunkLevel(..., false)` queues it for drawing and the cached
   translucent lists are re-registered; a never-baked level returns without
   drawing. Deferred levels are retried next frame in chunk order.
7. **Lighting budget** (`pzopt.Config.LIGHTING_BUDGET`, 0 = stock). With a
   budget `updateChunkLighting` calls a new private
   `pzoptUpdateChunkLightingBudgeted`: in the frame the lighting counter
   changes it still asks `LightingJNI.getChunkDirty` for every on-screen chunk
   level (sorted by chunk lighting counter as in stock) and records the dirty
   levels as a bitmask per chunk in a `LinkedHashMap<IsoChunk, Long>`; then, on
   that frame and the following ones, it refreshes the square light info
   (`cacheLightInfo` over the level's renderable squares) of at most that many
   chunks per frame, oldest entry first, dropping entries whose chunk left the
   on-screen list. The first version (2026-09-18) simply returned after the
   budget and re-entered the loop next frame; the JNI rewrites its dirty bits
   on its next pass, so the chunks it had not reached lost their update and
   stayed with stale light info (unlit tiles and tree silhouettes behind the
   car at 120 km/h, darker chunk-sized patches on grass). The stock branch
   (budget 0) is unchanged, including the debug-only `Lighting.SplitUpdate`.
8. **Occluder-mask replay** (`pzopt.Config.CUTAWAY_FAST`). In
   `calculateOccludingSquares(int)`, a chunk level that is not dirty and whose
   mask was computed before (tracked in a bounded `HashSet<ChunkLevelData>`)
   has its stored `occludingSquares[playerIndex]` bitmask replayed into the
   occluded grid (bit → square x/y, level z, same window test and `max`
   update as the stock per-square loop); dirty or never-computed levels run
   the stock `ChunkLevelData.calculateOccludingSquares`. The replayed mask is
   pzopt's own (`pzoptExactOccluderMask`, computed after the stock call with
   the stock test but without the on-screen clip). Since 2026-09-20 the mask
   lives on the `IsoChunk` override (a `long[64]` indexed by level + 32 plus a
   bit per stored level, cleared in `resetForStore`) instead of a map keyed by
   `ChunkLevelData`: the per-frame map lookup for every on-screen level was
   1.8 % of the game thread. The stock `occludingSquares` mask is built with an `int`
   shift (`1 << x + y * 8`): bits 32-63 wrap and bit 31 sign-extends when cast
   to long, which stock never notices because it only compares the mask with
   its previous value. Replaying it marked whole rows of tiles beside house
   walls as occluding, drawn as black one-tile rectangles (2026-09-19). Every input of that
   test (cutaway flags, vision matrix, square existence) dirties the level
   when it changes, so a clean level's mask is current.
9. **Cutaway visit radius** (`pzopt.Config.CUTAWAY_RADIUS`, chunks). The
   `doCutawayVisitSquares(playerIndex, chunks)` call in `renderTilesInternal`
   receives, instead of every on-screen chunk, the on-screen chunks whose
   chunk coordinates are within the radius of the camera character's chunk
   (a reused list; the stock list when the radius is 0).
10. **Grid-stack interval** (`pzopt.Config.GRID_STACK_INTERVAL`, frames). In
    `recalculateGridStacks`, `CalculatePointsOfInterest`,
    `CalculateBuildingsToCollapse` and `checkHiddenBuildingLevels` are skipped
    while the camera character's square and facing are the same as at the
    last scan and fewer than that many frames have passed (never skipped when
    `player.dirtyRecalcGridStack` is set); `recalculateAnyGridStacks` still
    runs every frame.
11. **Tree translucency check** (2026-09-20). In `checkTreeTranslucency` the
    `HashSet.remove` of the trees-awaiting-texture set is only attempted
    when the set is not empty (it almost always is: 1.9 % of the game thread
    was that remove per tree per frame), and the aim-key state is read once
    per chunk level instead of once per tree: `isTranslucentTree(IsoObject)`
    now delegates to a private overload that takes the aim flag.
12. **Re-bake budget** (`pzopt.Config.REBAKE_BUDGET`, default 4;
    `REBAKE_MAX_FRAMES`, default 3; 2026-09-20). Inside the bake-budget block
    of `renderOneLevel`: a texture that was baked before and whose dirty flags
    are only lighting (32), redraw (1024) and/or cutaways (2048) is deferred
    (previous image drawn through the existing stale-texture path) once that
    many such re-bakes have started this frame, for at most
    `REBAKE_MAX_FRAMES` frames per texture (an identity map from texture to
    the frame it was first held, bounded at 4096). Object, item, tree and
    obscuring changes are never held, for the flicker reason in item 6. On
    the 25 s Rosewood teleport route with the facing spinning, bakes were
    2.4 to 4.3 per frame and 87 % of them re-bakes; this took the p99 from
    15.6 to 11.2 ms (`docs/results.md`, 2026-09-20). Counters
    `budgeted rebakes` / `held` join the instrument line.
13. **Defaults changed 2026-09-20**: `lightingRebakeMs` 0 → 250,
    `cutawayRadius` 0 → 6, `gridStackInterval` 0 → 8 (measured on the same
    route: +8 fps for the two cutaway keys, +7 fps for the lighting hold;
    recordings side by side with the keys off show the same frames).

## zombie.GameWindow

Two edits. In the boot sequence (`init`, between `Translator.loadFiles()` and
`LuaManager.init()`): the call to `doEpilepsyWarningText()` is wrapped in
`if (!pzopt.Overrides.enabled())`, so the photosensitivity warning frame is
not drawn at start-up while the build guard is active. The method itself is
unchanged.

In `mainThreadStep` (edited 2026-09-19, reverted to stock later that day): the
stock limiter is back. `frameStep()` runs once the `accumulator` reaches
`1 s / PerformanceSettings.getLockFPS()` (the `frameRate=` value from
options.ini) unless `isFramerateUncapped()`, exactly as in stock. Earlier that
day the condition had `|| pzopt.Overrides.enabled()` appended, which made every
main-loop iteration a frame whenever the build guard was active; that removed
the player's choice, so it was undone.

In `mainThreadStep` (second edit, 2026-09-19): the two reads of the cap,
`isFramerateUncapped()` and `getLockFPS()`, go through `pzopt.FrameCap.uncappedNow()`
and `lockNow()`. Those return the in-game values while a world is up or loading
(`isIngameState()` or the current state is `GameLoadingState`) and the separate
menu cap otherwise; with the build guard off or the menu cap left at "same as
in-game" they are the stock values, so the loop shape is unchanged. After each
`frameStep()` (both branches) one call to `pzopt.FrameCap.onFrame(now)` counts frames
per phase and prints one console line per menu/game transition
("frame cap: menu phase 3.2 s, 144 frames, 45.0 fps (cap 45 fps)"), which is
how a hands-off run verifies the menu cap.

In `InitDisplay` (added 2026-09-19): one call to `pzopt.FrameCap.afterLoadOptions()`
right after `Core.loadOptions()` (both branches), before the sprite renderer is
created. Stock ships an "Uncapped" entry for the frame-rate combo in
`MainOptions.lua` but it is dead twice over: nothing ever sets the
`SystemDisabler` flag that gates the entry, and `Core.loadOptions` resets a saved
`uncappedFPS=true` to a 60 fps lock. `FrameCap` sets that flag so the combo shows
"Uncapped", then re-reads the `frameRate=` / `uncappedFPS=` lines from
options.ini and re-applies them to `PerformanceSettings`, so what the player
picks in Display options survives a restart. It also loads the menu cap from
`Zomboid/pzopt/framecap.ini`. Config key `uncappedFps`: `auto` (default) honours
options.ini, `true` / `false` force the in-game cap off / on for one run
(`harness/run.sh --prop uncappedFps=true`). No-op when the build guard is off.
Nothing else consults the cap for timing (`GameTime` uses measured deltas);
Lua's `getAverageFPS` clamps the displayed number to the in-game lock value
only when capped. The harness metric "frames below 240 fps cap"
(`harness/analyze.py` `FPS_TARGET`) keeps its meaning as the share of frames
slower than 4.17 ms.

The class is otherwise verbatim Vineflower output (revision
`b0bbce05d5`), which recompiles without fixes. Together with the committed
`src/shims/zombie/gameStates/TISLogoState.java` (logo screens skipped), this
is what gets a run from launch to the main menu with no splash screens.

Boot edits (added 2026-09-19, evening, `docs/plan-instant-load.md`), each
behind a `Config` key and `pzopt.Overrides.enabled()`:

- `mainThreadInit`: `FMODManager.instance.init()` is handed to
  `pzopt.BootAsync.startFmod` (a thread) when `fmodAsync` is on; the
  construction of `SoundManager.instance`, `AmbientStreamManager.instance` and
  `BaseSoundBank.instance` (each still choosing the Dummy variant on
  `Core.soundDisabled`), `VoiceManager.instance.loadConfig()` and the four
  `SoundManager.instance.set*Volume` calls are wrapped together in one
  `pzopt.BootAsync.afterFmod(...)` block, which runs at once when the init is
  synchronous and otherwise at the join. Why: the FMOD system create and the
  twelve bank files are 1.4 s of native work that nothing needs before the
  sound scripts, and the VCAs the volume setters read live in the banks. The
  managers must wait too (issue #3, 2026-09-20): `SoundManager` and
  `AmbientStreamManager` build their `FMODGlobalParameter` fields (MusicState,
  MusicIntensity, TimeOfDay, ...) in field initialisers, and each constructor
  resolves its parameter description from the banks loaded so far. Built
  while the banks were still loading they kept a null description, never
  registered with `FMODManager`, and their values never reached FMOD: the
  menu music never stopped and the in-game music and ambience were dead. This
  was true on Linux as well; nobody had listened to a run. `joinFmod` now logs
  whether `MusicState` is registered.
- `initShared`: `pzopt.BootAsync.joinFmod()` right before
  `ScriptManager.instance.Load()` (whose last step,
  `GameSounds.ScriptsLoaded`, is the first FMOD consumer). After
  `SpriteModelManager.getInstance().init()`, when `earlyModels` is on:
  `ModelManager.instance.create()` and `pzopt.BootAsync.startAnimSets()`;
  `enter()` later finds the manager created and skips (its own guard). Why:
  `create` only needs the scripts and the file system, and registering the
  3,990 animation imports 2 s earlier lets the boot pump finish them during
  the Lua load.
- `init` (first statement): `pzopt.BootPump.start()`; `mainThreadStart` calls
  `pzopt.BootPump.stop()` after `enter()`. Why: the file pool is only pumped
  by `GameWindow.logic` (per frame), so during init its threads idled.
- `init`, after `ZomboidFileSystem.instance.loadModPackFiles()`:
  `pzopt.LuaPrecompiler.start()` (mods are known, so the file list is right).
- `enter`: `pzopt.BootAsync.startAnimSets()` after `ModelManager.instance.create()`
  (a no-op when `initShared` already started it).

## zombie.fileSystem.FileSystemImpl (added 2026-09-19, game load)

Vineflower output needs one fix: in `updateAsyncTransactions` the decompiler
typed the reused local as `boolean priority` (`= (boolean)1`, later
`= (boolean)(16 - inProgress.size())`); the first assignment is dropped and the
second becomes `int canAdd`. Edits (`// pzopt:`):

1. **Load marker** in a `static {}` block; a new `private final int maxInFlight`.
2. **Pool size.** The constructor's `numThreads` (stock: 2 on ≤ 4 cores, else 4)
   becomes `pzopt.Config.FILE_THREADS` and `maxInFlight` becomes
   `pzopt.Config.FILE_INFLIGHT` when the overrides are enabled (stock 4 / 16
   otherwise); one log line reports both.
3. **In-flight cap.** The two literal `16`s in `updateAsyncTransactions` (how
   many in-progress items are checked per frame, and how many pending tasks may
   be submitted at once) read `maxInFlight`.

## zombie.tileDepth.TileDepthTextures (added 2026-09-19, game load)

Pristine Vineflower output compiles. Edits:

1. **Load marker** in a `static {}` block.
2. `tilesets` becomes a `ConcurrentHashMap` (same private field, same uses) and
   a `claimedTilesets` concurrent key set is added.
3. **Concurrent load tasks.** `LoadTask.call`, when
   `pzopt.Config.PARALLEL_DEPTH_MAPS` is set and the overrides are enabled,
   skips the stock `synchronized (this.textures)` block: if the tileset is not
   in the map and this task is the first to claim its name it calls
   `createTileset(tilesetName, true)` directly. Everything `createTileset` does
   is per tileset (cached row count, its own `PNGDecoder`, its own tiles, GPU
   uploads queued on the render thread), so the 218 tasks decode concurrently
   instead of one at a time. Stock path otherwise.

## zombie.core.textures.TextureIDAssetManager (added 2026-09-19, game load)

Pristine Vineflower output compiles. Edits: load marker, and `waitFileTask`'s
literal `52428800L` (50 MB of decoded textures waiting for the render thread
before the decoders sleep in 20 ms steps) becomes `WAIT_BYTES` =
`pzopt.Config.TEXTURE_BUFFER_MB` MB when the overrides are enabled.

## zombie.MapCollisionData (added 2026-09-19, game load)

Pristine Vineflower output compiles. Edits, all under
`pzopt.Config.LOADER_CPU_FIXES && pzopt.Overrides.enabled()`:

1. **Load marker** in a `static {}` block.
2. **Lot header once per cell.** A private static
   `pzoptZombieIntensity(lotHeader, chunkX, chunkY, cache, cached)` is a copy
   of `LotHeader.getZombieIntensityForChunk` whose
   `mapFiles.getLotHeader(cellX, cellY)` result is cached per map-files index
   in two arrays allocated per cell in `init`; the 32×32 chunk loop calls it
   instead of the static. Same loop bounds, same `bgHasCell300` test, same
   returned byte; only the per-chunk thread-local/`String.format`/`HashMap`
   lookups go.

## zombie.iso.IsoMetaGrid (added 2026-09-19, game load)

Pristine Vineflower output compiles (`MetaGridLoaderThread` comes along as an
inner class). Edits:

1. **Load marker** via `pzopt.Overrides.onClassLoadedQuiet` in a `static {}`
   block: `IsoMetaGrid` is constructed inside `IsoWorld`'s own static
   initializer, and `DebugLog` reads `IsoWorld.instance` (still null) for
   the frame number of every line, so logging there kills the game at boot
   (`ExceptionInInitializerError` in `IsoWorld.<clinit>`, nothing in
   console.txt). The marker is printed by the next override that loads.
2. **`checkVehiclesZones` dedupe.** Under the same guard the O(n²) scan is
   replaced by one pass with a `HashSet<Long>` keyed on
   `(getX(), getY(), w, h)`: a zone whose key was seen is removed, so the
   first zone of each key survives exactly as in stock (stock removes the
   later index). The stock debug string is only built when
   `DebugType.Vehicle.isEnabled()`; one `[pzopt]` line reports the counts.

## org.lwjglx.opengl.Display and org.lwjglx.input.Mouse (added 2026-09-19)

These two are The Indie Stone's LWJGL 2 compatibility shim over GLFW 3.4 (the
window and mouse the whole game talks to), not `zombie.*` code. They are
overridden for one reason: native Wayland on a scaled desktop. The game selects
the Wayland GLFW platform only when the JVM property `zomboid.wayland=1` is set
(otherwise the shim forces X11). On Wayland GLFW hands the window size out in
screen coordinates while the framebuffer is scaled (`GLFW_SCALE_FRAMEBUFFER`
is on by default), so on a 5120x2160 panel at KDE's 125 % scale the stock shim
told the game the display was 4096x1728 and the game drew that viewport into
the bottom-left of a 5120x2160 buffer, leaving black bands on top and right.

Edits in `Display` (all marked `// pzopt:`):

- `getWidth()` / `getHeight()` return the framebuffer size whenever it is known
  (the stock code returned the screen-coordinate size). On X11 and XWayland the
  two are identical, so nothing changes there.
- two new helpers, `getFramebufferScaleX()` / `getFramebufferScaleY()`, give
  framebuffer pixels per screen coordinate (1.0 when GLFW does not scale).
- the cursor-position callback multiplies GLFW's screen coordinates by those
  scales before handing them to `Mouse.addMoveEvent`, so clicks land where the
  cursor is drawn.
- the lock-cursor-to-window clamp in `updateMouseCursor` clamps to the
  screen-coordinate size (GLFW's space), not the framebuffer size.

Edit in `Mouse`: `setCursorPosition` divides the game's framebuffer pixels by
the same scales before calling `glfwSetCursorPos`.

Third edit in `Display` (added 2026-09-19, evening): **MangoHud on native
Wayland.** `swapBuffers()` first calls a private `pzoptHudSwap()`. On the first
call it checks that the overrides are enabled, the GLFW platform is Wayland,
`MANGOHUD=1` is set and `/proc/self/maps` shows `libMangoHud_opengl.so` already
loaded; if so it resolves that library's exported `eglSwapBuffers` with the JDK
foreign-function API (`SymbolLookup.libraryLookup` + `Linker.downcallHandle`,
signature `int (void*, void*)`). Every swap then calls it with
`GLFWNativeEGL.glfwGetEGLDisplay()` / `glfwGetEGLSurface(window)` (cached per
window handle) and returns; MangoHud draws the HUD and forwards to the real
`eglSwapBuffers`. Any failure (no handle, `EGL_FALSE`, exception) logs one
warning and falls back to `glfwSwapBuffers` for the rest of the process. Why:
GLFW resolves EGL entry points with `dlsym` on its private `libEGL` handle, so
the `LD_PRELOAD` hook never sees the swap on Wayland (on X11 the Steam overlay's
own `dlsym` hook chains to MangoHud, which is why it works there);
MangoHud's `dlsym` shim library deadlocks the game's JNI launcher. Verified
with `tools/GlfwSwapProbe.java` (the same LWJGL build, "hud" mode) and the
`wl-gl-mh-*` runs.

Both classes are otherwise verbatim Vineflower output (revision `b0bbce05d5`)
and recompile without fixes; `Display`'s inner classes `$Window` and
`$Callbacks` come along as loose classes. Verified 2026-09-19 with
`harness/run.sh ... --env JAVA_TOOL_OPTIONS=-Dzomboid.wayland=1`: the console
logs "Display mode changed to 5120x2160", the recording is full-screen and the
route numbers match the XWayland runs.

## zombie.scripting.ScriptParser (added 2026-09-19, evening, boot)

`stripComments` first tries `pzopt.ScriptText.stripComments` (one forward pass
with a nesting depth) when `scriptParserFast` is on and falls back to the
stock backward `StringBuilder.replace` loop when that returns null
(unbalanced comment markers). `parseTokens` returns
`pzopt.ScriptText.parseTokens`, the same split with an index instead of a
new substring per block, including the stock quirks (searches start one
character in; a brace-less remainder is a token of its own). Why: the stock
stripper is quadratic in the number of comments and cost 1.5 s on
`tileGeometry.txt` alone. `tests/pzopt/ScriptTextTest` compares both
functions against the jar's class on every `.txt` under `media/` (1,501
files identical). Otherwise verbatim Vineflower output (revision `b0bbce05d5`).

## zombie.fileSystem.FileSystemImpl (second edit, 2026-09-19, evening)

`updateAsyncTransactions` now takes a `ReentrantLock` around its whole body
(the body moved to a private method), and a `pzoptExecutor()` accessor
exposes the pool. The constructor sizes the pool to
`max(fileThreads, bootFileThreads)` when `bootPump` is on. Why: the boot pump
thread (`pzopt.BootPump`) pumps concurrently with the main thread's own calls
during font loading, and `pending`/`inProgress` are plain lists.

## zombie.iso.IsoMetaCell (added 2026-09-19, evening, game load)

`getChunk(int)` resolves the zombie intensity through
`pzopt.LotHeaders.zombieIntensity` with a per-cell memo (`pzoptLotHeaderCache`,
rebuilt if the cell's `info` changes) when `loaderCpuFixes` is on. Same
loop and tests as `LotHeader.getZombieIntensityForChunk`, but
`MapFiles.getLotHeader` (a `String.format` plus two hash lookups) runs once
per map layer per cell instead of once per chunk. Why: 0.8 s of the loader
thread in `IsoMetaGrid.load` → `loadZone` → `addZone` → `getChunk`.

## zombie.buildingRooms.BuildingRoomsEditor (added 2026-09-19, evening, game load)

`checkBuildingAndRoomIDs(IsoMetaCell)` builds an `IdentityHashMap` from
`roomList` (walked backwards so the first occurrence wins, as `indexOf`
does) and uses it for the two `roomList.indexOf(roomDef)` lookups when
`loaderCpuFixes` is on. Same checks and messages. Why: O(rooms²) per cell,
and `Basements.beforeLoadMetaGrid` calls it three times per load (0.85 s).

## zombie.gameStates.GameLoadingState (added 2026-09-19, evening, game load)

`exit`: `screenFader.startFadeToBlack()` is skipped when `noLoadFade` is on,
so the `while (isFading)` loop with its 33 ms sleeps ends at once (the world's
own 2 s fade-in through `UIManager.FadeOut` is untouched). `enter`, first
statements: `pzopt.BootPump.onLoadStart(executor)` shrinks the file pool to
`fileThreads`, `pzopt.BootAsync.joinAnimSets()` waits for the boot preload of
the animation sets, and the Lua precompile statistics are logged. Why: F
dropped from 0.41 to 0.05 s; the other hooks are the load-side ends of the
boot threads.

`render` (2026-09-20): the click-to-start gate for a new game (`newGame &&
time < 33`) is also satisfied when `noIntroWait` is on, so the prompt shows,
and `showedClickToSkip` lets `update` accept the click, as soon as `done &&
playerCreated`. The three intro lines keep their timers and still fade behind
the prompt. Why: stock makes a new save wait the full 33 s intro ("This is how
you died") even when the world finished loading in 4 s.

## se.krka.kahlua.luaj.compiler.LuaCompiler (added 2026-09-19, evening)

`loadis(Reader, String, KahluaTable)` (the overload `LuaManager.RunLuaInternal`
uses) reads the whole chunk into a string when `luaPrecompile` is on, asks
`pzopt.LuaPrecompiler.lookup(name, content)` for a prototype compiled during
boot, and returns `new LuaClosure(prototype, env)` on a hit; on a miss it
compiles the same characters through the stock path (a `StringReader`). The
other overloads are untouched. Why: Kahlua compiles ~1.7 s of Lua serially
across boot and load; the boot pool does it in parallel. The precompiler
stamps `Prototype.file`/`filename` with what the stock compile would have
written (`FuncState.currentFile`/`currentfullFile`).

## zombie.core.skinnedmodel.advancedanimation.AnimationSet (added 2026-09-19, evening)

`GetAnimationSet` and `Reset` keep their signatures and now run their bodies
inside `synchronized (setMap)`. Why: `pzopt.BootAsync.startAnimSets` parses
the player and zombie sets on a boot thread while the game may ask for them
(`IsoPlayer`/`IsoZombie` constructors, main-menu previews), and the map is a
plain `HashMap`.

## zombie.core.skinnedmodel.model.AnimationAssetManager (added 2026-09-19, evening)

`startLoading` creates a `pzopt.CachedAnimationTask` (a `FileTask_LoadAnimation`
subclass) instead of the stock task when `animClipCache` is on, and remembers
it per asset. `loadCallback` has a new first branch for the task's
`CachedClips` result (sets `anim.animationClips`, `onLoadingSucceeded`,
`ModelManager.animationAssetLoaded`, exactly what the `ProcessedAiScene`
branch does after `onLoadedX`), and the `ProcessedAiScene` branch ends with
`pzoptWriteCache(anim)`, which hands the freshly imported clips to
`pzopt.AnimClipCache.writeAsync`. Why: 2,209 jassimp imports (14–17
thread-seconds) per boot for data that is a map of keyframes; the cache
(one file per source, keyed by path, size, mtime and skinning mesh) replaces
them with 2.9 thread-seconds of reading.

## zombie.fileSystem.FileSystemImpl (third edit, 2026-09-19, evening)

`runAsync(FileTask)` wraps the task's `call()` in a timing lambda that reports
to `pzopt.FileTaskStats` (count and summed run time per task class; logged
when the boot pump stops and when the loading screen starts). Why: to size
the asset work per class (animations 14 s, texture pages 12 s of which most
is the upload-budget sleep, meshes 0.3 s).

## zombie.fileSystem.TexturePackDevice (added 2026-09-19, evening, boot)

`initMetaData` opens a `pzopt.PackIndex` for version-0 packs when `packIndex`
is on, and `readPage` looks the page's PNG end offset up in it: on a hit the
stream skips to the end instead of the stock loop that reads the PNG bytes
one at a time through the synchronized `PositionInputStream` looking for the
end marker; on a miss the stock loop runs and the end offset is recorded, and
the index is saved after the last page. Why: 0.5–0.6 s of boot scanning
526 MB of packs byte by byte (`TexturePackPage.readIntByte`). The index is
keyed by the pack file's size and mtime and lives in `~/Zomboid/pzopt/packs/`.
Nothing else changes; `PositionInputStream` already counts skips.

## zombie.scripting.objects.Item (added 2026-09-19, evening, boot)

`DoParam(String, String)` starts with a guard: when `itemParamSwitch` is on it
calls the new private `pzoptDoParam` and returns. That method is the stock
method with its 367-branch `else if (param.trim().equalsIgnoreCase("..."))`
chain rewritten as a `switch` on `param.trim().toLowerCase(Locale.ROOT)`;
every case block is the stock branch body verbatim, the attribute check that
precedes the chain stays first, the chain's tail (the negated
`GameEntityScript` test with the unknown-parameter handling) is the `default`
block, and the one key that appears twice in the chain (`SwingAnim`) keeps
its first branch, as in stock. The rewrite was generated mechanically from
the Vineflower output (the script is not kept; re-run the transformation if
the class changes). Why: 0.9 s of boot in a linear chain of up to 367
case-insensitive comparisons per item parameter. Both callers trim the key
before calling, so the comparison semantics are the same. Verified with the
`dumpItems` field dump (`pzopt.ScriptDump`) of every item script: identical
with the switch on and off.

## zombie.core.PerformanceSettings (added 2026-09-19, frame limiter)

Three public instance methods added, nothing else touched: `getMenuFramerateIndex`,
`setMenuFramerateIndex(int)` and `getMenuFramerateChoices`, each a one-line
forward to `pzopt.FrameCap`. The class is exposed to Lua (`getPerformance()`),
so the added methods are what the "Menu framerate" combo calls; the combo itself
is `src/lua/client/pzopt/pzopt_framecap_options.lua`, installed loose into the
game dir's `media/lua/client/pzopt/` by `scripts/pzopt.sh` (build.sh copies
`src/lua/` under `build/classes/media/lua/`). The Lua wraps `MainOptions:addCombo`
and, right after the stock "Framerate" combo is added, adds a second one whose
entries are "Same as in-game", "Uncapped" and the fps table; index 1 / 2 /
3.. is the convention `FrameCap` stores in `Zomboid/pzopt/framecap.ini`
(`menuFramerateIndex=`), written the moment the option is applied because
`Core.saveOptions` only writes keys it knows.

Extra caps (added 2026-09-19, later): both combos list 500, 430, 400, 330 and
300 fps above the stock 244 (`FrameCap.FPS_TABLE` and the Lua's copy of it must
agree; the framecap.ini index for the stock entries shifted by five). The stock
"Framerate" combo is built with the extended list from the same `addCombo`
wrapper, and the stock `'framerate'` GameOption, which hard-codes the stock
indices in `toUI`/`apply`, is caught on `gameOptions:add` and given
replacements that look the value up in the combo and apply it through
`PerformanceSettings.setFramerateUncapped` / `setFramerate` instead of
`Core.setFramerate` (which only knows indices 1..14). `Core.saveOptions` writes
`frameRate=` from `getLockFPS()` so the value persists, but `Core.loadOptions`
feeds it through an `IntegerConfigOption` clamped to 24..244 that rejects
anything higher and leaves the lock at the option's 60 default; `FrameCap.applySaved`
therefore also re-applies a saved capped value above 244 (it already re-read
the raw lines for the uncapped case). No Core edit.

Correction the same night: that re-read never worked, because the live
`Core.loadOptions` ends with `saveOptions()`, so by the time `afterLoadOptions`
ran the file already held the clamped 24..244 value and `uncappedFPS=false`
(a saved `uncappedFPS=true` becomes `frameRate=60`, which is how forced
`--prop uncappedFps=true` runs left the player's options.ini at 60 fps on
2026-09-19). `Core.saveOptions` also refuses a lock above 244 (the fake
`IntegerConfigOption` rejects it), so the file can never hold the new caps.
Now: `InitDisplay` calls `pzopt.FrameCap.beforeLoadOptions()` right before
`Core.loadOptions()`, which snapshots the raw `frameRate=` / `uncappedFPS=`
lines; `afterLoadOptions` re-applies them and any cap above 244 from
`framecap.ini` (`gameFps=`). The extended combo applies through the new
`PerformanceSettings.setGameFramerate(fps)` (0 = uncapped), which persists
the above-244 value. A forced `uncappedFps=true|false` run writes a
`restore=lock,uncapped,gameFps` line to framecap.ini and the next boot
re-applies that instead of whatever the forced run saved on quit, so harness
runs no longer change the player's frame-rate choice. Verified with two short
boots: forced run logs "game uncapped", next auto boot logs "game 300 fps". Menu means every state that is not
in-game or loading: logo, main menu, options, character creation.

Optimizations tab (added 2026-09-20): six more public instance methods, again
one-line forwards and nothing else touched: `hasPzoptOptions` (true when the
build guard is on; since the master switch of 2026-09-20 evening it returns
`pzopt.Overrides.buildMatches()`, so the tab is offered when the build matches
even if the player switched every optimization off, otherwise nothing could
switch them back on), `isPzoptOptionKnown(key)`, `getPzoptOption(key)` (the value
in force since boot, from `pzopt.Config.value`), `getPzoptOptionDefault(key)`,
`getPzoptOptionSaved(key)` (the player's saved value or ""),
`getPzoptOptionPinnedBy(key)` ("" or `pzopt.properties` / `-Dpzopt.<key>`) and
`setPzoptOption(key, value)` ("" removes the key). They serve
`src/lua/client/pzopt/pzopt_optimizations_options.lua`, which wraps
`MainOptions:addDisplayPanel` and adds an "Optimizations" page right after
Display: every `Config` key that is an optimization (not `instrument`, `dev`,
`devRedrawFrame`, `dumpItems`, `translucentCache`, `uncappedFps`, the last is
the Display combo) as a tick box (booleans) or a combo whose first entry is
"Default (value on this machine)" (integers), grouped as rendering, chunk
streaming, boot and load, with a tooltip per control. The choices go to
`Zomboid/pzopt/options.ini` through `pzopt.UserOptions` the moment Apply is
pressed, and `Config` reads that file at class init below `-Dpzopt.<key>` and the
install dir's `pzopt.properties` (harness runs write that file per run, so a run
never depends on a menu choice; a key set there shows disabled in the tab with
the pinning source in its tooltip). Choosing "Default" removes the key instead of
writing the default's value, because defaults differ per machine (worker
counts). Everything applies on the next launch: the GameOption's `apply`
compares the boot value with the new one through the stock
`GameOption:restartRequired`, so the stock "restart required" dialog appears
exactly when a change matters. Verified by a verify run (`opttab-smoke`,
`--prop bakeBudget=8`): the console logs "options tab: 35 controls, 1 pinned",
no Lua errors; the click path was not exercised hands-off.

Master switch (added 2026-09-20 evening): one more forward, `isPzoptEnabled`
(`pzopt.Overrides.enabled()`, the value since boot). The switch itself is
`Config.enabled` (default true), folded into `Overrides.ENABLED` next to the
build check: `enabled=false` makes `Overrides.enabled()` false, which is the
same stock fallback every override already takes on a build mismatch, so the
other keys are ignored and no override needs a change. `Overrides.buildMatches()`
exposes the build check alone. The tab shows the switch as a tick box above the
sections, with a "since this boot: on / OFF" note in its heading and two
buttons: "Enable all (recommended defaults)" ticks the switch and puts every
other control back to "Default", "Disable all (stock game)" unticks it and
leaves the other controls alone. Both only change the controls and mark the
options changed; Apply / Accept saves them through the same `apply` handlers,
so the restart dialog and `options.ini` behave as for any single change. A
pinned `enabled` (pzopt.properties / `-Dpzopt.enabled`, e.g. a harness
`--prop enabled=false` stock run) disables both buttons.

Preview clips (added 2026-09-21 night): five more forwards, one line each,
for the tab's preview panel: `getPzoptGifFrame(path, nowMs)` (the frame of an
animated GIF under the game dir, as a `Texture`, or null while it decodes / if
the file is missing), `getPzoptGifState(path)` ("loading" / "ready" / "missing" /
"error"), `getPzoptGifWidth` / `getPzoptGifHeight(path)` and `releasePzoptGifs()`.
They call `pzopt.GifTextures`, which decodes the GIF with ImageIO on a daemon
thread (compositing the frame deltas per their disposal rule, thinning to 96
frames and scaling to 512 px wide), and makes one game `Texture` per frame on the
game thread the way a Steam avatar is made (`ImageData` from RGBA rows ->
`TextureID` -> `Texture`, uncompressed, at most four per call). The tab (Lua)
draws, right of the control list, the stock and the optimized clip of the same
route side by side for the setting under the mouse, the setting's description,
its value since boot and at the next launch, and the effect bars (game thread,
render thread, other cores, GPU, VRAM, RAM, disk, load time, chunk arrival, from
a table in the Lua); the clips are `media/ui/pzopt/compare/<clip>-{stock,opt}.gif`
(`harness/menu-gifs.py`). The two clips in use are the only textures held (96 x
512x256 RGBA each at most); `MainOptions:setVisible(false)` releases them.
Verified in game on 2026-09-21 (queue job `menu-check4`: hover, scroll, wheel
over the preview, clips playing, panel sized to its content).

Main-menu update item (added 2026-09-22): eleven more one-line forwards to
`pzopt.Updater` for `media/lua/client/pzopt/pzopt_mainscreen_update.lua`:
`pzoptUpdateCheck()` (starts the release check once per boot; a no-op with
`updateCheck=false` or in a harness run), `getPzoptUpdateState()` ("idle",
"checking", "up-to-date", "available", "downloading", "installing", "installed",
"error"), `getPzoptUpdateTag` / `Notes` / `Published` / `PageUrl` /
`InstalledCommit` / `Message` / `Progress`, `canPzoptUpdateInstall()` (a
`pzopt-installed.txt` or `pzopt-files.txt` exists to replace) and
`pzoptUpdateInstall()`. The check lists the GitHub releases on a daemon thread
and picks the newest one (publish date) that carries
`pzopt-<revision>-classes.zip` for the running game; it is an update when the
tag's commit (`win-<revision>-<commit>`) differs from build-info's `commit=` and
its publish date is after build-info's `built=` (both stamped by `build.sh` since
this change, so a from-source build newer than the last release stays quiet).
The install downloads the zip next to the game folder, checks the zip's
revision, unpacks it into `pzopt-update.tmp/`, moves every file over the
installed one, deletes what the previous manifest listed and the zip no longer
has, and rewrites `pzopt-installed.txt` in the installers' format. The Lua adds
an `ISLabel` styled like the stock items (`UIFont.Large`, the hover fade of
`MainScreen.prerenderBottomPanelLabel`, the menu sounds) between Credits and
Exit (Exit and the panel move down one row); it is always there like the stock
items, greyed out and inert (no fade, no click) while the check runs, when the
build is current or when the check failed, enabled once a newer build is
offered; its text follows the state ("UPDATING... 43 %", "RESTART TO FINISH THE
UPDATE"), and
the dialog (`PzoptUpdateDialog`) shows the installed and offered builds, the
release notes, a progress bar and Update now / Later, then Quit game / Later:
classes the JVM already loaded stay the old ones until a restart. Never in the
pause menu; no joypad entry (the stock list is hard-coded).

## zombie.core.skinnedmodel.model.Model (added 2026-09-19, night, game load; GitHub issue #1)

`CreateShader(name)`: the stock method always posts a lambda to the render
thread and waits for it, even when `ShaderManager` already holds the shader.
Every `Model` constructor calls it, and the render thread only drains that
queue once per render step, so each model built off the render thread costs
one loading-screen frame. On the desktop that is ~1 ms; on the laptop
the laptop of issue #1 the loading-screen step is ~220 ms and the 73 animal
models `AnimalDefinitions.loadAnimalDefinitions` builds (all `animalEffect`)
were 16.5 s of the 31 s load. Now, when `shaderCache` is on, the method first
asks `pzopt.ModelShaders` for a shader an earlier model already created for
the same name and static flag and takes it without the round trip; only the
first model per shader still posts to the render thread, and that call's wall
time is recorded. `ModelShaders.summary()` ("model shaders: N cached, M
render-thread round trips (x s waited), K served from the cache") is logged
when the boot pump stops, when the load starts and at the harness's "world
ready", so the trace shows the stall on any machine. The cache is safe
because `ShaderManager` never removes shaders and a shader reloaded by the
debug file watcher recompiles in place. Config key `shaderCache` (default
true). The class-load marker goes in a static initializer like the others.

## zombie.core.textures.ImageData (added 2026-09-20, texture load; GitHub issue #2)

A file-pool worker (`pool-1-thread-18`, `FileTask_LoadPackImage.call` →
`initMipMaps` → `generateMipMaps`) crashed the JVM on the laptop
the issue #2 laptop with a SIGSEGV inside the C2-compiled
`scaleMipLevelMaxAlpha`. Nothing at the Java level can produce it: the
`ImageData` is local to the task, its `MipMapLevel` buffers are freshly
malloc'd and only ever read through bounds-checked `ByteBuffer.get(int)` /
`put(int, byte)`, and no other thread sees the object until `call` returns.
Reading the `hs_err` (copied from the laptop over SSH) settles it: the
faulting instruction is a plain reload of a spill slot from the thread's own
stack, `mov r14d, [rsp+0x88]`, with no address-size prefix; the reported
fault address is exactly that stack address truncated to 32 bits, and the
stack page was mapped (the crash log dumps it a few lines later). Eight
seconds after the JVM died, `systemd-coredump` (compressing the core, in
libzstd) segfaulted on the same laptop with a 32-bit-truncated address too,
and fifteen minutes earlier the same laptop's previous game process had
aborted inside the C2 register allocator (`PhaseChaitin::Simplify`, a
`SIGABRT` coredump with an empty `hs_err_pid3802.log`), after which the
machine was rebooted. Three faults in three unrelated code bases within
fifteen minutes, two with truncated addresses, is the machine (CPU, memory
or its `7.2.4-1-cachyos-custom` clang-built kernel, while the packaged
7.2.6 kernels are installed but not booted), not the game or the overrides.
A 5-minute, 18-thread hammer of the stock mipmap loops on the laptop's own
Zulu 25 JRE (`tools`-style reflection driver, see the issue) did not
reproduce it.

The override still exists because it was written before the crash log was
readable and is harmless: when `mipmapArrays` is on and the
`worldMipmapColors` debug option is off, `scaleMipLevelMaxAlpha`,
`scaleMipLevelAverage` and `performPreMultipliedAlpha(MipMapLevel)` return
early into `pzopt.MipMaps`, which reads each parent row pair with one bulk
`get`, builds the sub row in a thread-local `byte[]` with plain array
indexing, and writes it with one bulk `put`; the compiled loop has no
per-byte direct-buffer access left. The sub buffer is still rewound first,
as in stock. Output is byte-identical (`tests/pzopt/MipMapsTest` drives the
jar's own private methods by reflection over 40 size/alpha variants) and
the speed is the same (2048² → level 1: stock 14.3 ms, ours 13.5 ms,
warmed). Config key `mipmapArrays` (default true); off, or with the debug
colours on, the stock loops run untouched. Class-load marker in a static
initializer. It is not a fix for the laptop: if the crash recurs there, boot
the packaged kernel and run a memory test before touching the code.

## zombie.iso.weather.fx.WeatherFxMask (added 2026-09-20, game thread)

Regenerated with Vineflower; one decompiler fix (the "Calc Bounds" profile
area local shared its name with the `bRender` boolean captured by the two
rasterize lambdas; renamed, marked `// pzopt: decompiler fix`).

1. **Load marker** in a `static {}` block.
2. **Idle skip** (`pzopt.Config.WEATHER_MASK_IDLE_SKIP`, default true). At the
   top of the masked branch of `renderFxMask`, a private `pzoptMaskIdle`
   returns when nothing of the pass could reach the screen: the player is
   exterior (no interior tint layer), no cloud layer, no fog layer at fog
   quality 2, no precipitation layer at the precipitation option 1, and no
   debug mask view. Then neither `scanForTiles` nor `drawFxMask` runs; the
   mask state is left as it was, so the next active frame continues it.
3. **Scan gate.** `scanForTiles` returns at once when the scan could not add a
   mask: the player mask needs no update or has nothing to draw, or the
   player is in no building and no fog-mask region (`isInPlayerBuilding` can
   then never be true). Stock rasterizes the whole view every frame in that
   state (tens of thousands of squares at max zoom, `isInteriorLocation` per
   exterior tile: 1.5 to 3.7 % of the game thread, 70 % of the pass in slow
   frames).
4. **Building-only scan.** When the player stands in a building and not in
   a fog-mask region, a private `pzoptScanBuildingOnly` visits the squares of
   the building's `BuildingDef` bounds plus one tile of margin (a square
   outside the building only consults its N, W and NW neighbours) that pass
   the class's own `isOnScreen`, calling `addMaskLocation` for each, instead
   of rasterizing the view; `scanForTiles` then returns. The mask contents
   are the same squares stock would have found. Same Config key. The whole
   pass went from 2.6 to 0.5 % of the game thread outdoors and from 6.6 to
   2.4 % on the spinning route through Rosewood.

## zombie.iso.objects.IsoLightSwitch (added 2026-09-20, game thread)

1. **Load marker** in a `static {}` block.
2. **Electricity check cache** (`pzopt.Config.LIGHT_SWITCH_CHECK_FRAMES`,
   default 15; 0 = stock). `hasElectricityAround()` keeps its last answer and
   the frame it was computed (`IsoWorld.getFrameNo`) in two new fields and
   returns it while fewer than that many frames have passed; the stock body
   moved to a private `pzoptHasElectricityAroundNow`. `LightingJNI.checkLights`
   asks every light source's first switch for power every frame (grid power,
   generators, the 3x3x2 neighbourhood): 2.3 % of the game thread on the
   Rosewood route, 0.3 % after. A power change shows on a lamp up to 15
   frames late.

## se.krka.kahlua.j2se.KahluaTableImpl (added 2026-09-20, Lua VM)

1. **Load marker** in a `static {}` block.
2. **Single lookup in `rawget(Object)`.** Stock does `containsKey` and then
   `get` on the delegate map (two hash lookups per table read; the Lua UI and
   `OnTick` handlers do millions per second). Now one `get`; a null result
   means the key is absent, which is exactly the stock `containsKey` test
   because `rawset` removes the key on a nil value and never stores null.
   The metatable fallback is unchanged. The reload-replace and data-breakpoint
   code before it is untouched.

## zombie.core.opengl.RenderThread (added 2026-09-20, performance overlay)

Three one-line hooks in `lockStepRenderStep`, all into `pzopt.Overlay`, plus the load
marker `static {}` block. Inside the `spriteRendererPostRender` probe,
`pzopt.Overlay.gpuBegin()` precedes `SpriteRenderer.instance.postRender()` and
`pzopt.Overlay.gpuEnd()` follows it: a `GL_TIME_ELAPSED` query around the frame's
draw-command replay, which is the frame's GPU work (the swap is outside it). After the
`displayUpdate` probe closes, before the stock `FPSGraph.addRender` call,
`pzopt.Overlay.onSwap()` records the presented-frame time, the same instant MangoHud
logs from. With the build guard off every hook is a static boolean test.

## org.lwjglx.opengl.Display (second edit, 2026-09-20, performance overlay)

First statement of `imguiEndFrame()`: `pzopt.Overlay.draw();`. `Core.EndFrameUI` calls
this method on the game thread after the UI FBO has been composited onto the screen and
immediately before `IndieGL.glDoEndFrame()` / `RenderThread.Ready()` hand the frame to the
render thread, so sprites queued here are the last thing on top of every state (menus,
loading screen, world). A draw at the end of `GameWindow.renderInternal` was tried first
and never showed: the hand-off has already happened by then. The overlay draws through
`TextManager` and `SpriteRenderer`, so it needs no Lua and no external HUD. See
`pzopt.Overlay` for what it shows and the `overlay*` Config keys. The other two callers of
`imguiEndFrame` (exception paths in `GameWindow.logic`, ImGui only) just draw one more
overlay frame.

## zombie.iso.fboRenderChunk.FBORenderCutaways (added 2026-09-20, game thread, 400 fps pass)

Loose copy with the load marker `static {}` block and one edit in
`doCutawayVisitSquares`, behind `pzopt.Config.CUTAWAY_INVALIDATE_CHANGED` (and only when
`PerformanceSettings.fboRenderChunk` is on). Stock clears the cutaway flag of every square in
last visit's result sets, re-adds the flags from this visit's sets, and invalidates (flag
2048, "cutaway changed") the chunk level of every square touched in either step, so every chunk
holding a cut-away wall re-bakes its texture on every visit; while moving through a town the
visit runs almost every frame. With `fboRenderChunk` the texture only reads the square's
*target* cutaway flag (`getPlayerCutawayFlag` returns it directly, there is no fade), so the
texture is unchanged when the flags end the visit as they began. The edit remembers each
touched square's target flag before the clear (an identity map, reused), lets the stock flag
updates run unchanged, then invalidates only the chunks where a square's flag differs (with the
stock off-screen seen-rooms mask reset for those). The edge-wall neighbour invalidation loop
after it iterates the same reduced set, which is exact: it only reads flags of that chunk.
Counters `pzoptCutawayVisits`, `pzoptCutawayChunksInvalidated`, `pzoptCutawayChangedSquares`.

## zombie.audio.parameters.ParameterZone (added 2026-09-20, game thread, 400 fps pass)

Loose copy with the load marker and a memo in `calculateCurrentValue`, behind
`pzopt.Config.SOUND_ZONE_CACHE`. Seven of these parameters (forest, deep forest, farm, nav,
town, trailer park, vegetation) each scan the meta grid's zones in an 80x80 window around the
listener every frame (`IsoMetaChunk.getZonesIntersecting`, with an `ArrayList.contains` per
zone) and take distances from `fastfloor(x)`, `fastfloor(y)`. The value is therefore a function
of the listener's integer square; it is reused while that square is unchanged, for at most 30
frames. The stock body is unchanged, moved into `pzoptCalculate`.

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-20 evening, light info once per frame)

`cacheLightInfo` (one JNI call per square) is now routed through `pzoptCacheLightInfo` at the
three per-frame sites: `prepareChunkForUpdating` (texture-dirty chunk levels),
`pzoptCacheChunkLevelLightInfo` and `updateChunkLevelLighting` (lighting-dirty levels). A chunk
level that is both texture-dirty and lighting-dirty in one frame asked twice per square. The
helper keeps a frame-number stamp per player, level and square on the `IsoChunk`
(`pzoptLightInfoFrame`, lazily allocated) and skips a square already refreshed this frame,
behind `pzopt.Config.LIGHT_INFO_ONCE_PER_FRAME`. Lighting results only change in
`LightingThread.update`, which runs before the render. Counter `pzoptLightInfoSkipped`.

## zombie.iso.IsoChunk (edit of 2026-09-20 evening)

One field: `pzoptLightInfoFrame`, the stamp rows used by the FBORenderCell edit above.

## zombie.iso.ChunkSaveWorker (second edit, 2026-09-20 evening, staged hot save)

(A ten-stage variant splitting `map_meta.bin` in two was tried and dropped the same evening.)

`HotsaveAncilliarySystems` behind `pzopt.Config.HOTSAVE_STAGED`: instead of serialising the
meta grid (map_meta, zones, animal zones, meta cells), the animal population, game time, the
world map, visited map and the entity manager in one `invokeOnMainThread` block (one 55 ms
frame per hot save on the bench route), the first call starts a staged save and every following
`Update` call from the streamer runs one part on the game thread (nine stages), accumulating
into the same `SaveBufferMap`; after the last stage the players are saved and the buffers are
written to disk exactly as stock does. The stock path is kept for the flag off.

## zombie.iso.weather.fx.WeatherFxMask (second edit, 2026-09-20 evening, FX buffer scale)

Behind `pzopt.Config.WEATHER_FX_SCALE_PCT` (100 = stock and the default: 50 % measured as a wash, u400-it3-fx50-2; single player only):
`checkFbos` creates the mask and particle FBO textures at that fraction of the screen size;
`drawFxMask` and `drawFxLayered` queue a `glViewport` to the texture size right after their
`glDoStartFrameFx` (the projection stays in world units, `glDoEndFrameFx` pops the viewport
attribute); the mask composite (`rendershader2` texel rectangle) and the final particle composite
(texture coordinates) sample the scaled texels. Clouds, fog, rain and the interior mask are soft
content; at 5120x2160 the full-size pass was ~11 % of the uncapped frame (CPU and GPU). Also a
measurement-only `Config.DEV_WEATHER_FX_OFF` that returns from `renderFxMask` at once.

## zombie.iso.IsoChunkMap (added 2026-09-20 evening, chunk hand-off budget)

Loose copy with the load marker and one edit in `updateInternal`, behind
`pzopt.Config.CHUNK_HANDOFF_DIVISOR` (8; 0 = stock): the number of freshly loaded chunks handed
to the game thread this frame (`doLoadGridsquare`: loot roll, erosion, recalc, pathfind, 1 to
5 ms each) is capped at 1 + queue / divisor on top of the stock 1 + 3 * queue / gridWidth, so a
chunk row arriving at once is spread over a few frames instead of one 10 to 25 ms frame.

Edit of 2026-09-21 (mid-scroll guard): `getGridSquareDirect` returns null when the chunk found at
the indexed slot is not the chunk that slot should hold (`c.wx != getWorldXMin() + chunkX` or the
same for y). `LoadLeft/Right/Up/Down` move `worldX`/`worldY` (the origin every caller subtracts)
before `SwapChunkBuffers` publishes the shifted grid, so a lookup from the streamer or a recalc
worker inside that window indexes the old grid with the new origin and gets a square one chunk
off. Stock `IsoGridSquare.isWallTo(other, depth)` then asks for the orthogonal intermediate,
receives that same off-by-a-chunk (still diagonal) square and recurses on it until the stack
overflows (the `depth > 100` branch is an empty debug hook). Seen in a Windows user's console,
chunk 1071,1434: the worker's pass and the streamer retry both overflowed. A square that is not
where the index says reads as not loaded, exactly what the map edge returns; two int compares on
fields already in cache.

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-20 evening, occlusion grid on lighting-only frames)

`renderTilesInternal` decides whether to rebuild the occluded-squares grid through
`pzoptHasDirtyChunkTexturesForOcclusion` instead of `hasAnyDirtyChunkTextures`: behind
`pzopt.Config.OCCLUSION_SKIP_LIGHTING_ONLY`, a frame whose only dirty on-screen chunk levels are
dirty for lighting (flag 32) keeps the previous grid and does not set `occlusionChanged` (so
the per-level rendered-squares counts are not recomputed either), exactly as a frame with no
dirty level behaves in stock. Lighting drift changes no square, vision matrix or cutaway flag,
which are the only inputs of the occluder test. Counter `pzoptOcclusionRebuildsSkipped`.

## GPU section timing (2026-09-20 evening, `pzopt.GpuSections`, measurement only)

Behind `pzopt.Config.GPU_SECTIONS` (off by default): `begin`/`end` calls on the game thread
queue a generic draw command whose render step writes a `GL_TIMESTAMP` query, so the GPU time of
a named span of the sprite stream is known a few frames later; the periodic FBORenderCell log
line prints the average GPU microseconds per frame per section. Sites: `FBORenderCell`
(`chunks` = the per-chunk draw/bake loop, `bake` = one chunk-level texture bake,
`translucentFloor`, `translucent`, `items`, `moving`, `water`) and `WeatherFxMask.renderFxMask`
(`fx`, the whole weather pass; the stock body moved to `pzoptRenderFxMask`). With the flag off
every site is a static boolean test.

## zombie.iso.fboRenderChunk.FBORenderCutaways (second edit, 2026-09-20 evening, visit prefilter)

Behind `pzopt.Config.CUTAWAY_VISIT_PREFILTER`. `cutawayVisit` walks every cutaway wall of the
on-screen chunks at the player's level and, per wall square, does a grid-square lookup, two hash
set operations, a level-data lookup and `IsCutawaySquare`; on the spinning route that is ~10 %
of the frames above 3 ms. `IsCutawaySquare` can only be true when the wall occludes one of the
player's cutaway rooms, or (with no cutaway rooms) is part of a building in
`buildingsToCollapse`, or the player is peeking through a window (a per-square garage-door
test, kept as is). Those are wall-level facts, so `pzoptWallCanCut` tests them once per wall and
walls that fail are skipped before their squares are touched. The visited sets are then no
longer complete; their only reader is the point-of-interest loop in `doCutawayVisitSquares`,
where stock's first visit marks every wall square visited so later points of interest never
add a square: with the prefilter the loop simply stops after the first visit, which is the same
result. The wall's own `ChunkLevelData` is used when the square lies in the wall's chunk instead
of a hash lookup per square. Counters `pzoptWallsVisited`, `pzoptWallsSkipped`.

## zombie.iso.fboRenderChunk.FBORenderCutaways (fourth edit, 2026-09-21, carport roof debounce)

Behind `pzopt.Config.ROOF_HIDE_DEBOUNCE_FRAMES` (`roofHideDebounceFrames`, default 8; 0 = stock;
Options > Optimizations "Carport roof hide/show settle time"). In `checkOrphanStructures`, per
chunk level with orphan structures (a carport / pergola roof: a building whose only room is
`emptyoutside`), the answer of `OrphanStructures.shouldCutaway()` is compared with the current
`PlayerInRange` state; when they differ, a per-player counter on the `OrphanStructures` object
(`pzoptPendingFrames[4]`, reset by `calculate` and `resetForStore`) counts consecutive frames of
the new answer and the level is skipped (`continue`, current state kept, nothing invalidated)
until the counter reaches the setting. The first answer after `Unset` is applied at once, as in
stock. Stock applies every change immediately and invalidates the level (2048) each time, so a
decision that changes every frame (the maintainer's 2026-09-21 video: player on the SE edge of a
detached carport, zombies around, the roof toggling every frame until the game was paused)
re-bakes four chunk levels per frame and the roof flickers. Counter `roof flips held` on the
instrument line.

## zombie.iso.fboRenderChunk.FBORenderCutaways (third edit, 2026-09-21, dev log of roof hide/show decisions)

Diagnostic only, behind `pzopt.Config.DEV_CUTAWAY_LOG` (`devCutawayLog`, default false): a
private static `pzoptDevCutawayLog(String)` prints at most 400 `cutaway dev f<frame>: ...` lines.
It is called (a) at the end of `CalculateBuildingsToCollapse` when the buildings-to-collapse list
changed (old and new size, the new defs' bounds, `cell.occludedByOrphanStructureFlag`, the number
of points of interest), (b) in `checkOrphanStructures` on each `PlayerInRange` flip (`orphan
HIDE` / `orphan SHOW`, chunk and level), and (c) in `shouldRenderBuildingSquare` when the
adjacent-chunk counter forces an `OrphanStructures.calculate`. No decision is changed. Added for
the carport-roof per-frame flicker report (user video, 2026-09-21).

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-20 evening, light info chunk gate)

`prepareChunkForUpdating` refreshes the light info of every square of every level of a chunk
about to be re-baked; each refresh is a JNI "is this square dirty" question (and the lighting
data fetch when it is). Behind `pzopt.Config.LIGHT_INFO_CHUNK_GATE` the level first asks
`LightingJNI.getChunkDirty` (the same chunk-level question stock's own lighting refresh,
`updateChunkLevelLighting`, uses as its gate) and skips the 64 square refreshes when the level
has no dirty square; the `squareFlags` bookkeeping of the loop is unchanged. Counter
`pzoptLightInfoLevelsGated`.

**Black chunk squares fixed (2026-09-20 afternoon).** With the gate, a square whose light info had
never been cached (a freshly streamed chunk whose lighting pass had already run and consumed the
JNI dirty bit before the level's first bake) kept `lightInfo == null`; the loop's
`getLightInfo(playerIndex) != null` test then left it out of `squareFlags` and the whole 8x8
level baked black. Stock never hits it because it refreshes every square unconditionally. The
gated branch now refreshes a square whose light info is null regardless of the chunk-level answer
(`pzoptRefresh || sq.getLightInfo(playerIndex) == null`). This was the "black squares on the left
of the screen" that had been attributed to `persistentVbo`: the persistent mapping only changed the
render/lighting thread timing enough to expose it (bisect runs `bs-*`, screenshot rig
`run.sh --shot-at`, metric `harness/blacktiles.py`; 0 black 32 px tiles after the fix in
`bs-gatefix-*`, 225-303 before).

**Staged hot save off by default (2026-09-20 night):** `hotsaveStaged` defaults to false. The
parts are serialised a few frames apart while chunks keep loading, so `map_meta.bin` and the
`metacell_*.bin` files could disagree on room metaIDs; the "invalid room metaID" load errors seen
that night turned out to be pre-existing in the bench save (present in every run), but the
consistency risk stands and the gain was one 55 ms frame per 30 s, so it stays opt-in.

## zombie.core.opengl.VBORenderer (added 2026-09-20 evening, thunderstorm pass)

Two edits in the immediate-mode line/quad renderer that every VBORenderer user shares (weather
particles, model atlases, shadows, trees, the vision polygon, debug lines). Both behind Config
keys; with `enabled=false` the stock values apply.

**Batch buffer size** (`vboBatchKb`, default 1024, 4 = stock). The element buffer, its index
buffer and the two GL buffer objects are created at the configured size instead of 4 KB / 1 KB,
and `setFormat` derives the element count from that size. Stock flushed (a `glBufferData` and a
draw) every 113 vertices, i.e. every 28 textured quads; the rain FX at 5120x2160 add ~100k
particle quads a frame (104 tiles of a 512x512 cell of 1024 particles), which was 73 % of the
render thread's busy time in a thunderstorm (`storm-jfr` JFR, `gametree.py --thread main`). The
size is capped at 1.5 MB so every vertex index still fits the 16-bit index buffer.

**Single-advance quad** (`vboFastQuads`, default true). The 4-vertex branch of the textured
`addQuad` (the one that is not lines and not triangles) writes the four vertices, the four
indices and one buffer-position advance in a private helper instead of four `addElement` calls
that each re-check `isFull`, look up the current run and read the buffer position. Same bytes at
the same format offsets (vertex, colour, uv1; other slots left as `addElement` leaves them),
same flush condition, same vertex count bookkeeping.

Result on the storm route (uncapped, direct launcher): render thread submission
(`buildStateDrawBuffer`) 8.7 -> 6.1 ms; 108 -> 131 fps once the game thread stopped being the
wall (runs `storm-rec-vbostock` / `storm-rec-cur`).

## zombie.iso.IsoPuddles (added 2026-09-20 evening, thunderstorm pass)

No behaviour change in the existing methods. Four public `pzopt*` methods expose the pieces of
`render(grid, z)` separately for `pzopt.PuddleCache`: the guard chain (`pzoptCanRender(z)`:
debug option, shader enabled, shaders in use, puddle quality, level clamp, wet-ground /
puddle-size non-zero), the packing loop without the draw (`pzoptPack`, the identical
shouldRender / updateLighting / addSquare sequence), the draw (`pzoptDraw`), and
`pzoptAppend(packed, count, z)` which grows the per-state RenderData like `addSquare` does and
copies pre-packed squares in, keeping the per-level counters. `pzoptNumSquares` / `pzoptData`
read the current main-state RenderData.

## zombie.iso.IsoChunk (third edit, 2026-09-20 evening, puddle cache slot)

One public field `pzoptPuddles`, a `pzopt.PuddleCache.Slot` holding the packed puddle batches
of this chunk per player and level; lazily created by the cache, dropped with the chunk.

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-20 evening, puddle cache)

`renderPuddles(playerIndex)`: after the stock guards (puddles enabled, no snow, level clamp for
the medium/low quality) and before the per-level loop, when `Config.puddleCache` is on the
method hands the on-screen chunk list and `maxZ` to `pzopt.PuddleCache.render` and returns; the
stock loop is untouched below it. In the bake path, right after `clearCachedSquares(level)` is
called for a chunk level, `pzopt.PuddleCache.invalidate(chunk, level)` marks that level's
batches for a rebuild (the puddle square list is refilled by the same bake). The periodic
`[pzopt] FBORenderCell` log line gets the cache counters (built / reused / rebuilt by bake,
cutaway change, expiry).

What the cache does (`pzopt.PuddleCache`, committed): stock re-filters, re-lights and re-packs
every wet square of every on-screen chunk level every frame (`FBORenderCell.puddles` 4.5 ms of a
13 ms thunderstorm frame at max zoom). Of the 32 floats per square only the four vertex lights,
the camera's sub-pixel jiggle on x/y and the depth change between frames, and the depth depends
on the camera's chunk only (`IsoDepthHelper.getSquareDepthData` floors the camera position to a
chunk), shifting by one constant for every square when the camera crosses a chunk edge. So a
chunk level is packed once with the stock code, the block is kept on the chunk, and later frames
copy it into RenderData and patch those three slots (lights from `getVertLight`, jiggle delta,
depth delta from two `getChunkDepthData` calls). Rebuilt when the bake clears the square list,
when the level's cutaway `squareFlags` visibility bits change, or after `puddleCacheFrames`
frames (default 60, staggered per chunk). The per-square `IsOnScreen` cull is not applied to
cached batches (the GPU clips the squares outside the viewport; same picture). Result: puddles
4.5 -> 0.96 ms, storm route 70 -> 109 fps with the profiler on (`storm-vbo` -> `storm-puddle`).

## zombie.iso.weather.fx.ParticleRectangle (added 2026-09-20 night, rain tiles)

`render()`: after the stock cell arithmetic and `StartShader`, when `Config.rainTiles` is on and
the debug bounds are off, the method renders every particle with `renderAlpha > 0` once at the
origin into the rectangle's drawer between `pzoptBeginTile` / `pzoptEndTile`, adds one origin per
screen cell (the same `-1..cellsW` x `-1..cellsH` grid the stock loops walk) to that tile, and
returns; the stock per-cell loop with its per-particle `isOnScreen` cull is below it, untouched.
Every subclass `render(offsetx, offsety)` (rain, snow, cloud, fog) adds its quad at the offset
plus the particle's own position, so a cell's picture is the origin picture translated by the
cell origin.

## zombie.iso.weather.fx.WeatherParticleDrawer (added 2026-09-20 night, rain tiles)

Keeps a list of tiles for the frame (cleared in `startFrame`): a tile records, per texture
index, the range of particle-list indices added between `pzoptBeginTile` and `pzoptEndTile`, and
its origins. `render()` (render thread) first hands the tiles, the particle buffer, the texture
list and the per-texture index lists to `pzopt.RainTiles.Gl.draw` (one buffer object and staging
buffer per drawer), then runs the stock VBORenderer loop only over the particles no tile covers
(a fully tiled texture list is skipped). `pzopt.RainTiles.Gl.draw`: uses VBORenderer's own
`vboRenderer_PositionColorUV` shader (new accessor `VBORenderer.pzoptShaderPositionColorUv`),
sets its ModelViewProjection from the current matrix stacks like `VertexBufferObject` does,
packs the template quads once in the same 36-byte position/colour/uv layout, uploads them with
`glBufferData` (stream), binds the texture, and issues one `glDrawArrays(GL_QUADS)` per origin
with the ModelViewProjection uniform translated by the origin; then restores the uniform,
unbinds the buffer, re-enables the attribute arrays 0..4 and the depth test as
`VBORenderer.flush` leaves them, and sets the sprite ring buffer's restore flags. Depth test
off and `userDepth` 0 as VBORenderer's default run. The per-particle on-screen cull of the stock
loop becomes GPU clipping. Counters (tiles, template quads, draws) in the periodic
`[pzopt] FBORenderCell` line.

## zombie.core.opengl.VBORenderer (second edit, 2026-09-20 night)

Public accessor `pzoptShaderPositionColorUv()` returning the lazily created
`vboRenderer_PositionColorUV` shader the class already uses for that format.
## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-20 evening, per-frame lists survive a held re-bake)

The maintainer reported objects inside buildings, doors, windows and corpses flickering
(appear / disappear) in normal play. Reproduced on the end square of the `S:450` route with the
player spinning (`run.sh ... --flag hold=10 --flag turn=90 --flag zoom=1 --record`, runs
`flick-*`; metric `harness/flicker.py`): items on the desks, the table beside the player, the
doors and the wall objects blink out for 1-3 frames; stock shows nothing but the spinning player.

Cause: stock `FBORenderLevels.NLevels.invalidate()` does not only set the dirty bits. Outside
`performRenderTiles` (`FBORenderLevels.clearCachedSquares == true`, set at the top of
`renderInternal` and after the tile pass) it also empties the level's per-frame square lists
(items on tables, obscuring furniture, cutaway window frames, corpses, flies, animated
attachments, puddles, translucent floor), because in stock the bake that follows in the same
frame rebuilds them. Every pzopt hold that draws the previous texture instead of re-baking
(`lightingRebakeMs`, `rebakeBudget`) therefore drew a texture whose per-frame objects were
neither in the texture nor in the lists. `lightingRebakeMs=0` alone took the metric from 26 to
7.8 transient px/frame (stock 3.8); `rebakeBudget=0`, `bakeBudget=0`, `lightingBudget=0`, the
cutaway keys and the texture-content keys changed nothing on their own.

Edit: the two `FBORenderLevels.clearCachedSquares = true` assignments in `renderInternal`
become `= !pzoptKeepPerFrameLists()`, a private static helper that is true when the overrides
are enabled and any of `LIGHTING_REBAKE_MS`, `REBAKE_BUDGET`, `BAKE_BUDGET` is set. The lists
then only change at a bake (`clearCachedSquares(level)` at its start rebuilds them), so they
always describe the texture that is on screen, held or fresh. Stock's own flow is unchanged
(every bake rebuilds them anyway). The re-bake budget no longer holds cutaway (2048) re-bakes:
their per-frame draws re-test the live cutaway flags (`isTableTopObjectSquareCutaway`, the
window-frame flags), so a stale texture could show an object neither baked nor per frame; the
held set is now `32 | 1024` only. Result: 0.1 transient px/frame at object scale (`--scale
1280`; broken build 3.4, stock 0.0); uncapped spinning route 488.7 fps mean, p99 7.2 ms
(`flickfix-u-1`, reference `jvm-zulu-g1-1` 508.7 / 7.3) with ~15 % more bakes per period.

## zombie.iso.IsoChunk (fourth edit, 2026-09-20 evening, per-frame lists cleared on reuse)

`resetForStore` calls a new private `pzoptClearPerFrameLists()`: `clearCachedSquares(z)` for every
level of every player's `FBORenderLevels`. Stock relied on the load-time invalidation to empty
those lists; with the FBORenderCell edit above that invalidation keeps them, so a chunk object
going back to the pool drops them here instead (the corpse and flies lists are iterated for every
on-screen level, baked or not).

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-20 night, lighting-only re-bake spread)

In the re-bake budget block (`REBAKE_BUDGET`), a texture whose only dirty reason is lighting
(flag 32 alone: daylight drift, the ramp of a lightning flash) now uses its own per-frame start
budget `lightingRebakeBudget` (default 8) and its own longest hold `lightingRebakeMaxFrames`
(default 30) instead of `rebakeBudget` / `rebakeMaxFrames` (4 / 3), which stay for the redraw
reason (1024: light switches must answer within a few frames). Why: a lightning strike dirties
every on-screen chunk texture; with the 3-frame cap they all landed in one frame, five times per
strike (flash on, flash off, then every `lightingRebakeMs` of the fade), a 50-90 ms stall each
that the maintainer saw as the rain freezing and jumping every ~6 s (`docs/findings-scene-presets-2026-09-20.md`
§6). With the spread the same bakes land over ~25 frames: storm drive p99.9 57 -> 12.5 ms, max
88 -> 19 ms, no frame over 33 ms, at the price of a faint chunk checkerboard for ~90 ms while a
flash ramps (chunks baked at different points of the ramp). `lightingRebakeMs=100` was tried
with budgets 8 and 16 and is worse on the tail (chunks become eligible about as often as the
cap, so the cap keeps dumping the backlog).

## zombie.iso.LightingJNI (2026-09-20 night, harness see-all view)

`updatePlayer` passes `pzopt.Scene.seeAll()` to the native `playerSet` where stock passes a
constant false (B41 passed the player's dead state there: a dead player's visibility pass marks
every square seen and visible, the spectator view). The flag comes from the harness flag file
(`see_all=true`, read once at world-ready by `Scene.apply`; false in normal play, so the class
behaves as stock outside a run). Why: the Louisville preset walks through downtown blocks whose
tall buildings stop the vision cone, and the never-seen squares behind them draw black, so the
recordings were mostly black. With the flag every square is lit and drawn on both sides of an
A/B (more visible tiles and characters than a normal view; compare only same-flag runs). No
Config key: it is a scene flag, not an optimization. The class also gains the usual
`pzopt.Overrides.onClassLoaded` static initializer.

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-21, curtains in front of baked windows; GitHub issue #4)

Two pieces. (a) In `calculateObjectRenderLayer`, after the Vegetation test and before the
MinusFloor one, the new private `pzoptCurtainLayer(IsoObject)` decides the layer of a curtain
that hangs in front of the window or door on its own square: it returns the layer already
computed for that attached object in this pass (MinusFloor = bakes, Translucent = per frame),
so the curtain is drawn in the same pass as its window whatever the reason the window landed
there (the `windowsInChunkTexture` key, or a baked window sent per frame while it fades or
obscures the player). The attached object comes first in the square's object list
(`IsoCurtain.getObjectAttachedTo` searches backwards from the curtain's index), and
`calculateObjectRenderInfo` walks the list in order, so its layer is final. Null (stock rules)
when both bake keys are off, for `curtainS` / `curtainE` (which hang on the far side of the
next square's wall and are meant to be seen through the glass), for sheet-door curtains (3D
models at the door's `CurtainOffset`), and when nothing is attached. (b) At the end of
`renderMinusFloor_NotDoorOrWall(IsoObject)`, before the final `object.render(...)`, a curtain
drawn by a bake (`!renderTranslucentOnly`) for which `pzoptCurtainDepthNudge(IsoCurtain)`
returns a positive distance goes through `pzoptRenderCurtainNudged(...)`: its world position is
moved that many tiles into the room (south for `curtainN`, east for `curtainW`, i.e. toward the
camera, a smaller depth in `IsoDepthHelper.calculateDepth`) and `offsetX` / `offsetY` are moved
back by `IsoUtils.XToScreen` / `YToScreen` of the same delta for the duration of the call, so
the pixels land where they always did and only the depth the tile depth shader writes changes.
The nudge is `pzopt.Config.CURTAIN_DEPTH_NUDGE` (`curtainDepthNudgePct`, default 5 = 0.05
tile; 0 = off) and applies under the same conditions as (a).

Why: a Windows user reported windows drawn over closed curtains with the default settings,
correct only with both bake keys off. Stock never depth-tests a curtain against its window:
both are per-frame translucent objects (`IsoWindow`, and the curtain tiles are
`Translucent = true`), drawn in object order with `glDepthMask(false)`, so the curtain (after
the window on its square) simply paints over the glass. With both baked the chunk texture's
depth buffer decides (`DepthTestAll`, `GL_LEQUAL`, depth writes on): the north window glass and
the north curtain use the same wall depth texture (`setupWallDepth`; the few tiles with
geometry boxes have the glass 0.017 tile in front), and the glass came out on top. With one
baked and the other per frame the per-frame sprite is tested against the composited chunk
texture, and the two sample the 8-bit wall depth texture at slightly different sub-pixel
positions: the glass z-fights through the curtain as a dither (the reporter's
"semi-transparent" curtain). Hence (a) removes every cross-pass comparison and (b) settles the
one that remains, inside a single bake where both sprites map to the same texels and a nudge
of about 3 depth-texture steps (one step is about 0.016 tile) is an exact margin. Verified on
the bench save with the `find=curtains` / `close_curtains=true` harness dev flags at
8147,11507 (Rosewood living room, `fixtures_windows_01_9` + `fixtures_windows_curtains_01_50`):
runs `i4-repro` (nudge 0, no (a): glass over the curtain), `i4-fix` ((b) alone, defaults:
curtain covers the glass, same pixels), `i4-wpf` / `i4-tpf` ((b) alone with one key off:
dithered glass), `i4-fix2` / `i4-wpf2` / `i4-tpf2` ((a) + (b): curtain covers the glass in all
three settings).

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-21, tree pass; GitHub issue #5)

Trees baked into the chunk-level textures (`treesInChunkTexture`) get their own draw pass
(`pzopt.Config.TREE_BAKE_PASS`, `treeBakePass`, default true; the drawer is `pzopt.TreeBake`,
described in `src/CLAUDE.md`). Edits, all marked `// pzopt: issue #5`:

1. **`renderMinusFloor(IsoChunk, IsoGridSquare, PZArrayList)`** skips `IsoTree` objects while the
   pass is active (`pzoptTreePassActive()`: the two keys, the overrides enabled and the
   wind-sprite-effects option off, under which stock never bakes a tree anyway).
2. **`renderOneLevel`**, on the bake path just before `endRenderChunkLevel(c, level, zoom, true)`,
   calls `pzoptBakeTrees(c, playerIndex, zoom)` when `level` is the texture's top level, i.e. after
   every level of the texture has been drawn.
3. **`pzoptBakeTrees`** walks the 5x5 chunk neighbourhood of `c` (the chunk itself and the
   neighbours whose lighting has been done). For every square at a level of this texture that
   carries a tree which would bake (`pzoptTreeBakes`: not highlighted, animating, wind- or
   hit-affected, fading, awaiting its texture or translucent under the player; for the chunk's own
   trees also the `MinusFloor` render layer this bake computed), that the cutaway data lets render,
   that is not occluded and not a force-render square, the sprite's full rectangle in this texture's
   space is computed (`pzopt.TreeBake.spriteRect`, from the square, the chunk corner, IsoTree's
   offset rule for the JUMBO sizes and the texture's untrimmed size). The chunk's own trees are
   drawn when the rectangle touches the texture; a neighbour's tree only when the part of it inside
   this texture is not entirely inside the tree's own chunk texture (`needsCopy`), so a tree that
   fits its own texture is never duplicated. The colour is the square's light info (a neighbour's
   square gets `cacheLightInfo()` first, as the stock bake does for the north and west squares it
   draws); `unlit` sprites draw white. The depth is the one the sprite path would write for the
   square's south corner (`getSquareDepthData` minus this chunk's `getChunkDepthData`), and a
   neighbour's tree with a negative result (a nearer chunk, below this texture's depth range) is
   skipped because its own and nearer textures hold it. Each texture of the tree (main sprite and the
   attached foliage overlays) is handed to the drawer with its trimmed rectangle and the depth at
   its top and bottom rows (`depthAtRow`: the base at the ground row, one level's depth nearer per
   level of height). The chunk's own baked trees also get `renderFlag = false`, what the stock bake
   records for `checkTreeTranslucency`.
4. **Export fingerprints.** While walking its own trees, the pass sums an identity hash of every
   tree that needs a copy in each of the 24 neighbours' textures (`IsoChunk.pzoptTreeExportFp`, one
   int per slot). A slot whose value changed since the chunk's last bake re-bakes that neighbour's
   texture (`invalidateLevel(minLevel, DIRTY_TREES)`) if it has one, so a tree chopped, grown, gone
   per frame or back on this chunk never leaves a stale copy elsewhere.
5. **`checkTreeTranslucency`** calls `pzoptInvalidateTreeCopies(tree)` where it already invalidates
   the tree's render square (state change, texture arrived): every neighbour texture that needs a
   copy of the tree re-bakes in the same frame as the tree's own chunk.
6. The periodic counters line gets `tree bake: passes= trees= copies= quads= neighbours re-baked=`.

Why: a chunk-level texture covers its chunk's footprint plus two levels and the JUMBO_L
allowance above it (`extraHeightForJumboTrees`), but a tree sprite is anchored on one square and
is up to seven tiles wide and sixteen tile heights tall. Baked through the plain sprite path
into its own chunk's texture (the previous `treeBakeDirect` path) a JUMBO tree is clipped at the
texture border: the maintainer's report of crowns cut by straight edges and of a chunk-sized
black rectangle (a tree drawn black, as stock draws trees on never-seen squares in heavy fog,
clipped the same way). The plain path also writes one flat depth for the whole sprite while the
walls it overlaps write per-pixel depths that get nearer with height (`zDepthBlendZ` to the
front corner one level up), so an upper-storey wall behind a tree cut a vertical strip out of
its crown. Stock's own chunk-texture tree batch (`FBORenderTrees` with `renderThreadCurrent`
set) draws the crown dark and behind the house (run `trees-ab-batch`), which is presumably why
stock never bakes trees. The pass draws the quads through VBORenderer's position/colour/uv/depth
format (`vboRenderer_PositionColorUVDepth`, the fragment shader writes the interpolated depth),
under `GL_LEQUAL` with depth writes and the alpha test on, last in the texture, so content in
front already in the depth buffer occludes the tree and content behind is painted over, like
the stock per-frame tree billboard against the composited textures. Verified on the issue's
capture (`--source-save Apocalypse/2026-09-20_22-30-24 --flag start=11023,6720 --flag fog=heavy`
`--shot-at 1`): run `trees-fix1` differs from the per-frame reference `trees-ab-off` in 0.08 % of
the pixels (crown outlines), the previous bake `trees-shot` in 3.43 %; on the Rosewood capture
(`route=S:450 zoom=1 --shot-at 12`, runs `trees-town-on` / `-off` / `-plain`) the pass matches the
plain path's brightness and the per-frame reference drawn without `vboFastQuads`. Spinning
route (`gt` route, 25 s): 280 fps with the pass, 281.5 with the plain path, bakes +9 % (the
neighbour re-bakes), flicker rig 0.3 px/frame at scale 2560 (stock 3.8).

## zombie.iso.IsoChunk (fifth edit, 2026-09-21, tree export fingerprints)

A public `int[] pzoptTreeExportFp` (25 slots, allocated by `FBORenderCell.pzoptBakeTrees` on the
chunk's first tree pass) and its reset to null in `resetForStore()`, so a reused chunk object
starts without another chunk's fingerprints.

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-21 afternoon, trees per frame while chunks churn; `treeBakeMaxChunksPerSec`)

A per-frame mode bit `pzoptTreesPerFrameNow`, set once per frame by `pzoptUpdateTreeMode()` at the
top of `renderTilesInternal` (frame number stamp `pzoptTreeModeFrame`): true when
`Config.TREE_BAKE_MAX_CHUNKS_PER_SEC` is above 0 and `pzopt.ChunkRate.perSecond()` (chunk hand-offs
to the game thread, exponential average over half-second windows) is above it.
`isTreeRenderedEveryFrame` treats every tree as per-frame while the bit is set (the stock answer),
and `pzoptTreePassActive()` — now an instance method — is false, so a chunk level baked in that
frame gets no trees and no tree pass, and the per-frame path draws them. Counter
`pzoptTreesPerFrameFrames` and the current rate on the instrument line.

Why: on a 4-core i5-6300HQ (Dell, `docs/results.md` 2026-09-21 low-end section) baking trees
while driving at 120 km/h was a net loss — a chunk texture lives a second or two, and baking its
trees (own texture plus the neighbour copies of the tree pass, plus the tree pass's 5x5-chunk scan)
cost more game-thread time than drawing them per frame for that long: `workers=1` 19.5 fps, with
`treeBakePass=false` 28.6, with `treesInChunkTexture=false` 42.7 (stock 29.1). Walking through
Rosewood at max zoom the bake amortises over hundreds of frames and the default set is 47.3 fps
against stock's 31.5. So the choice follows the chunk rate instead of a fixed key. No re-bake burst
on a mode flip: a tree's layer is stored in its `ObjectRenderInfo` at bake time and read per frame,
so textures baked with trees keep drawing them from the texture until they re-bake for their own
reasons, and the tree export fingerprints (`pzoptTreeExportFp`) re-bake a neighbour whose copy of a
tree went stale. During the seconds a neighbour still holds a copy of a tree now drawn per frame,
that tree is drawn twice in the overlap (slightly denser alpha edges), the same transient the
fingerprint re-bake already covers. Default 0 (always bake, the desktop behaviour); the Options
tab's "Low-end hardware" profile sets 24.

## zombie.iso.IsoChunk (sixth edit, 2026-09-21 afternoon)

One line at the top of `loadInMainThread()`: `pzopt.ChunkRate.loaded()`, the hand-off counter the
edit above reads.

## zombie.iso.weather.fog.ImprovedFog (added 2026-09-21, fog pass)

`endRender()`: when `Config.fogPass` is on (`pzopt.FogPass.enabled()`), the remaining rows of the
layer are walked by a private `pzoptRenderAllRows` instead of `renderRowsBehind(null)`. It is the
same loop over the same iterator, `lastRow`, `lastIterPos` and open-rectangle statics, producing
the same rectangles, but the square lookup is hoisted per chunk: the chunk is fetched from the
chunk map once per eight squares (`getChunkForGridSquare`, accepted only when loaded and when its
`wx`/`wy` are the expected ones, the mid-scroll guard of the IsoChunkMap override) and the square
read straight from the chunk's level array. A missing or unloaded chunk, or a level the chunk has
no squares for, counts as fog exactly as the stock null square does. Stock resolved the chunk map
for every one of the ~10k squares per level per frame at max zoom.

With `Config.fogMaskFrames` > 0 (default 20) a second private walk, `pzoptWalkMasks`, replays
the RectangleIterator's geometry itself (rows of alternating lengths ceil(rowlen/2) and +1, row
starts stepping (0,0) (0,1) (1,1) (1,2) ..., the last position of the last row never delivered,
as the stock `next()` returns false before it is used; `startRender` records the `rows` /
`rowlen` it passed to the iterator) and reads the fog test of up to eight squares of a row from
one short of the chunk's per-diagonal masks (`pzopt.FogPass.ChunkFog` on `IsoChunk.pzoptFog`:
bit lx of `diag[level][lx + ly]` = the square takes fog, i.e. no square, or exterior and not in a
room; levels 0 and 1 only, the ones ImprovedFog draws). A chunk's masks are recomputed when older
than `fogMaskFrames` frames, the first computation stamped up to `fogMaskFrames - 1` frames in the
past by a hash of the chunk position so the refreshes spread over the frames; a new room or wall
therefore reaches the fog within `fogMaskFrames` frames (stock: the next frame). The walk touches
one chunk object per up to eight squares and no square objects, and runs of up to eight all-fog
or no-fog squares advance in one step: 215 → 49 µs per level per frame at 1920x1080 max zoom on
the laptop, with the same 92.7 segments per level. The segments found (start and end square per
segment, world coordinates) are kept per level with the diamond they were found in (minX, minY,
maxX, maxY) and the frame; while the diamond is the same and fewer than `fogMaskFrames` frames
have passed, the next frames replay them through `renderFogSegment` (which recomputes the screen
rectangle and depths from this frame's camera) instead of walking: on the desktop at max zoom
(22k squares per level) the walk runs about one frame in four while driving, 59 → 28 µs per level
per frame averaged. Both walks record their time, segment count and square count for the
`fog pass:` counters. Everything else in the
class (`startRender`, `renderRowsBehind`, the segment maths and depths, `startFrame`) is
untouched.

## zombie.iso.weather.fog.ImprovedFogDrawer (added 2026-09-21, fog pass)

`render()` (render thread): when the fog pass is on, the drawer copies its 28 uniform floats
into an array and hands them, its rectangle buffer and the noise texture to its
`pzopt.FogPass.Gl` instance (one per drawer, like the drawers themselves per player and
sprite-renderer state); when that returns true the stock body is skipped, otherwise the buffer
is rewound and the stock body runs (the fall-back once the driver refused the depth copy or a
shader did not compile). `pzopt.FogPass.Gl.render`, per frame:

1. Reads the viewport and the bound draw framebuffer. Keeps a fog buffer (RGBA8 colour texture
   plus a depth texture) at `fogScalePct` % of the viewport per axis (clamped to 25..100) and,
   below 100 %, a full-size depth texture for a copy of the scene depth. The depth textures use
   the scene attachment's own internal format, read from the framebuffer, so the depth blit is
   format-compatible whether the scene depth is a renderbuffer, a texture or the window's.
2. Gets the scene depth. When the scene framebuffer's depth attachment is a texture (the
   `MultiTextureFBO2` edit below makes the offscreen buffer's one a texture) it is read in place:
   at 100 % that texture is attached to the fog buffer as its depth (the rectangles never write
   depth), below 100 % a reduction pass writes each fog texel the *nearest* (smallest) scene depth
   of the block of screen pixels it stands for (up to 4x4, `texelFetch` loop, `gl_FragDepth`, depth
   func ALWAYS, colour writes off). When the attachment is a renderbuffer (some other FBO, or the
   swap failed) the depth is first copied with a nearest `glBlitFramebuffer` (the first eight
   frames after a (re)creation check `glGetError`): at 100 % straight into the fog buffer, below
   100 % into a full-size depth texture the reduction reads. A nearest-sampled *scaled* blit picked
   one arbitrary pixel per block, so around a one-pixel power line the fog was decided by the
   ground behind it in most blocks and the wire came out dotted; with the block's nearest depth
   every block that holds a thin near object keeps that object's depth.
3. Clears the fog colour (the clear colour is saved and put back), packs every rectangle as one
   quad in a 36-byte layout (position; the corner's position in the rectangle, the stock side-fade
   width as a fraction of the rectangle width, the row noise offset; the rectangle depth and the
   layer alpha) through a ring of three stream buffers and draws them all with one `glDrawArrays`
   under its own programs compiled from strings: the vertex shader takes the depth from the
   attribute into `gl_Position.z` (no `gl_FragDepth`, so early depth rejection works) and the
   fragment shader is the stock `fog.frag` maths with the per-rectangle uniforms replaced by the
   interpolated attributes and `gl_FragCoord` mapped from fog-buffer to viewport pixels. Depth
   test GL_LESS with the depth mask off, `glBlendFuncSeparate(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE,
   ONE_MINUS_SRC_ALPHA)` so the buffer accumulates premultiplied colour and correct coverage
   (identical to sequential "over" because every rectangle covering a pixel has the same colour),
   scissor and stencil off. The noise texture (`media/textures/weather/fognew/fog_noise.png`,
   loaded by the game without mipmaps) is sampled through a sampler object with trilinear
   filtering after a one-time `glGenerateMipmap` on the game's texture object (the levels survive
   the game's per-bind filter juggling; the sampler never changes the texture's own parameters; if
   the driver refuses the mipmaps the sampler is not used): in a scaled buffer the seven fetches
   per fragment are texels apart, so without mipmaps every fetch missed the texture cache and the
   draw was memory-bound (1.2 ms at 25 % on a Radeon 890M, the same at 50 %) and the noise aliased.
4. Restores the scene framebuffer and viewport and draws the fog buffer over the scene once as a
   clip-space quad with `(ONE, ONE_MINUS_SRC_ALPHA)`. At 100 % that is a texel copy. Below 100 %
   every screen pixel reads the four nearest fog texels and their depths; when those depths
   straddle an edge (spread > 0.0003) it also reads its own scene depth and weighs the texels by
   bilinear distance (floored at 0.05 so a neighbour can still win) divided by the distance between
   the texel's depth and its own: the fog texel that was decided at this pixel's surface dominates,
   so a wire keeps the fog decided at its depth and the ground next to it its own, instead of a
   bilinear smear of the two. Away from edges it is a plain bilinear blend.
5. Puts back the texture units, the buffer binding, the attribute arrays 0..4, the depth test,
   mask and blend function, runs `GLStateRenderThread.restore()` as in the stock body, and sets
   the sprite ring buffer's restore flags.

Counters (frames, rectangles, fall-back, the game-thread walk split) in the periodic `[pzopt]
FBORenderCell` line; the buffer sizes and the noise mipmap result are logged when created. With
`gpuSections=true` the sub-sections `fog.blit` (copy + reduction), `fog.rects` and
`fog.composite` are timed from the render thread (`GpuSections.markNow`). Measurement switches
`devFogNoDraw` (everything but the rectangle draw) and `devFogFlat` (a flat fragment shader).

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-21, fog pass)

`renderFog`: with the fog pass on, the per-level loop over every on-screen chunk, its squares and
their objects (which only called `ImprovedFog.renderRowsBehind(square)` on the first floor object
of each square) is replaced by `startRender` / `endRender` per level: the FBO renderer draws all
rectangles from the drawer at `endFrame` in any case, so the painter's-order interleaving the walk
provided did nothing. Both paths are bracketed by the GPU section `fog`, and the fog pass counters
are appended to the periodic log line.

## zombie.iso.IsoChunk (sixth edit, 2026-09-21, fog masks)

A public `pzopt.FogPass.ChunkFog pzoptFog` slot (the per-diagonal masks of the squares of levels
0 and 1 that take fog, see the ImprovedFog entry) and its reset to null in `resetForStore()`.

## zombie.core.textures.MultiTextureFBO2 (added 2026-09-21, fog pass)

`createTexture` (the real branch): after the stock `new TextureFBO(tex)` it calls
`pzopt.FogPass.sceneDepthAsTexture(fbo, tex)`, which on the render context (when `fogPass` is on)
replaces the FBO's DEPTH24_STENCIL8 depth+stencil renderbuffer with a DEPTH24_STENCIL8 texture of
the texture's hardware size on the `GL_DEPTH_STENCIL_ATTACHMENT`, checks completeness, and either
deletes the renderbuffer (the later `TextureFBO.destroy` deletes the name again, which GL
ignores) or, on any failure, re-attaches it and keeps the stock state. Rendering into a depth
texture is the same as into a renderbuffer (same format, same stencil bits); the point is that the
fog pass can sample the scene depth where it is instead of copying the whole depth buffer every
frame (44 MB at 5120x2160). Textures of FBOs that no longer exist (zoom-level or resolution
changes recreate the offscreen buffer) are deleted at the next call. The FBO's private id is read
by reflection. Logged as `offscreen buffer WxH (fbo N) depth+stencil is texture T`.

## zombie.iso.IsoPuddles (second edit, 2026-09-21, storm parity pass)

Two additions, no behaviour change with the keys off. `pzoptTruncate(numSquares, z)` drops the
squares packed after index `numSquares` from the main-state RenderData (count and per-level
counter): `pzopt.PuddleCache.renderVbo` packs a batch with the stock `pzoptPack` and then uses
the RenderData only as scratch space. `applyPuddlesQuality()` builds the `PuddlesShader` from
`pzoptShaderName("puddles_lq|mq|hq")`, which with `Config.puddleEarlyZ` (default on) returns the
`pzopt_` copy shipped under `media/shaders/` (`src/shaders/`): same `#include`s and colour
math, but the vertex shader sets `gl_Position.z` from the depth attribute and the fragment
shader no longer writes `gl_FragDepth`, so the GPU's early depth test drops the wet-ground
pixels hidden behind walls, roofs and objects before the ~200-op puddle shader runs. The files are
generated by `scripts/build.sh` from the installed game's puddle shaders (the game's `#include "x"`
pulls `x.h` for the prototypes and compiles `x.glsl` as a second shader unit of the program, so the
variant needs its own copies of both): the entry files with the include renamed, the `.h` stubs
copied, the vertex unit with one added line after `vDepth = aFragDepth;` and the fragment unit
with its three `gl_FragDepth = vDepth;` lines removed. Two things learned the hard way: an
assignment to `gl_FragDepth` anywhere in a fragment program, even in a function that is never
called, makes the depth shader-written (undefined where not executed, and no early test), and the
depth attribute is relative to the camera's chunk and negative for nearer chunks, which the stock
write clamps per fragment but the vertex path would clip, so the draws (`PuddleVbo.Gl.draw` and
`renderSome`) enable `GL_DEPTH_CLAMP` around them: same "interpolate, then clamp to the range".
`applyPuddlesQuality` logs the live program and whether it compiled (`[pzopt] puddles: shader`).
Depth values are otherwise the same (orthographic projection, w = 1, window depth = the attribute).
Storm drive 120 km/h on the desktop: puddles GPU section 0.29 -> 0.10 ms, 357 -> 390 fps.

## zombie.iso.LightingJNI (second edit, 2026-09-21, puddle light hook)

In `JNILighting.updateFBORenderChunk`, right after the eight cached vertex lights are refreshed
from the native result, when any of the lower four (the puddle corner colours) changed the
square's chunk level is reported to `pzopt.PuddleCache.lightsChanged(chunk, z)`, which marks the
level's cached puddle batches for a light patch and re-upload (`Config.puddleVbo`). Stock's own
`invalidateLevel(z, 32)` calls are unchanged.

## zombie.iso.IsoChunk (seventh edit, 2026-09-21, puddle batches on reuse)

`resetForStore()` calls `pzopt.PuddleCache.chunkReused(this)`: every cached puddle batch of the
slot is marked invalid, so a chunk object reused for another position rebuilds (and re-uploads)
its batches instead of trusting the list-size check alone. The GL buffers stay with the slot and
are refilled.

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-21, storm parity pass)

`renderRainSplashes(playerIndex, z)`: with `Config.rainSplashesFast` (default on) each on-screen
chunk level goes through `pzopt.RainSplashes.update` / `render` instead of
`IsoChunkLevel.updateRainSplashes` / `renderRainSplashes`. Same fields, same advance, same flags
and the same `IsoGridSquare.renderRainSplash` call; only the per-idle-square `Rand.NextBool(n)`
(one call through the CellularAutomatonRNG per idle square of every on-screen level per frame,
2.6 % of a laptop thunderstorm frame) is replaced by geometric skipping with a local xorshift
generator: one draw per splash start gives the number of idle squares to skip, which is the same
Bernoulli(1/n)-per-square process. Two GPU sections were added for the storm profile:
`puddles` (both puddle draws) and `splashes`. The periodic log line gets the splash counters.

What `pzopt.PuddleVbo` does (`Config.puddleVbo`, default on, on top of `puddleCache`): stock, and
the cache alone, copy every on-screen puddle batch into IsoPuddles' RenderData each frame and
patch the vertex lights, jiggle and depth of every vertex (`PuddleCache.reuse`, 11.7 % of a laptop
thunderstorm frame), and the render thread streams that block through the 64 KB ring buffer in
~7 map / draw cycles (`IsoPuddles.renderSome`, 8 % of its frame). Now a batch is packed once with
the stock code, its jiggle normalised to zero, and uploaded to its own GL buffer (`Batch.vbo`,
render thread); later frames only re-upload it when a square's light changed (the LightingJNI
hook above), the camera crossed a chunk edge (one depth constant for every vertex) or it was
rebuilt. The game thread lists the on-screen batches (~90 items) into a `PuddleVbo.Frame` per
level, handed to the render thread through `SpriteRenderer.drawGeneric` with an immutable
snapshot of every batch due for upload; the render thread repeats `ModelManager.RenderPuddles`'
state (projection, `PuddlesShader.updatePuddlesParams`, blend, depth) and draws one indexed range
per batch from its buffer with the frame's jiggle folded into the ModelViewProjection uniform.
Same vertex bytes, same shader, same order.

A reduced-resolution puddle layer (the puddle shader once per screen pixel, composited depth-aware
like the fog pass) was built and measured on the way and dropped: the puddle draw's viewport is the
5120x2160 screen, not the zoom-out buffer, so "100 / zoom" was only 2048x864, and the reduction and
composite passes cost more than the early-Z draw saves (376 vs 389 fps, run `lz-d-storm-scale-1`).

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-21, tree copies appended; storm parity pass)

`Config.treeAppend` (default on), marked `// pzopt: treeAppend`. In `pzoptBakeTrees`' export
fingerprint loop a slot that goes from 0 to a value (the chunk exports trees into that
neighbour's texture for the first time: the usual case is a newly loaded chunk next to already
baked ones while driving) no longer invalidates the neighbour's texture. `pzoptQueueTreeAppend`
lists the chunk's trees that reach into that texture, placed in *its* space (`pzoptTreeRect`
with the neighbour's chunk coordinates and its texture's `xoff` / `yoff` as
`beginRenderChunkLevel` computes them, `needsCopy` against its rectangle, the depth relative to
its chunk, the same light and the same texture quads as the pass), into a pooled
`TreeBake.Drawer` that remembers the target texture. Before `FBORenderChunkManager.endFrame`
composites the textures, `pzoptFlushTreeAppends` draws each queued drawer into its texture
inside the bake's own begin / end sequence (`glDoStartFrameFlipY`, `FBORenderChunkStart` without
a clear, the generic draw, `FBORenderChunkEnd`, `glDoStartFrame`): the render thread binds the
texture with its depth attachment, the quads go through the pass's depth test (`GL_LEQUAL`,
depth writes, alpha test) on top of the finished bake, and `endRenderThread` regenerates the
mipmaps. The drawer refuses to draw when the bound framebuffer's colour attachment is not the
expected texture (`expectTexture`). A neighbour texture that is dirty, off screen, not the level
group's current texture or not among the frame's composited textures at flush time is
invalidated as before, and every later fingerprint change (a tree chopped, grown, gone per frame
or back) still re-bakes. Same picture: the pass draws trees last in a bake anyway, so drawing
them last into the finished texture is the same sequence of draws. Counters: `appends= (queued=
fell back= refused=)` in the tree bake line. Why: on the 120 km/h desktop drive 60 % of all bakes
(`trees=5217` of 8571 per 1800 frames, 2.9 a frame at ~300 µs of GPU each) were these neighbour
re-bakes, in clear weather and storms alike.

## zombie.iso.LightingJNI (third edit, 2026-09-21 evening, strong light changes)

The "blocky lights" report (maintainer video, 2026-09-21: a hand torch swept in a garage at
night showed the beam as a patchwork of tile-stepped, stale pieces): the held lighting-only
re-bakes of FBORenderCell (`lightingRebakeMs`, `lightingRebakeBudget` / `lightingRebakeMaxFrames`)
were tuned for sky drift and lightning flashes, but a moving light source dirties the few chunk
levels in its beam every frame and each held level kept showing the beam at a past angle, one
angle per chunk. Stock re-bakes them every frame.

`JNILighting.updateFBORenderChunk`: in both branches that call `invalidateLevel(z, 32)` the
square now reports the size of its change to the new `pzoptLightChanged(...)`: the light-info
channels' differences added together, a quarter of the dark-multiplier move (in 1/1000), a
light-level change counted as 255, and the largest channel difference of the eight vertex lights.
The square sums those deltas since its level was last baked (`pzoptLightAcc`, reset when
`IsoChunk.pzoptLightBakeFrame[level]` is newer than the square's last accumulation) and past
`Config.lightingStrongDelta` (6) marks the level strong for the frame through
`pzopt.LightDirt.markStrong`.

`LightingJNI.update`: before `stateEndFrame` for player 0 the global light handed to the engine
(rmod, gmod, bmod, ambient, night, sky level) goes to `pzopt.LightDirt.globalLight`; a move past
`Config.lightingGlobalDeltaPct` (2 %) in one frame is a flash or a fast-forwarded dusk and keeps
the re-bake spread on for `lightingRebakeMaxFrames + 2` frames. (A count of strong levels a frame
was tried first and rejected: turning moves the vision cone across the whole screen, so a sweep
marked as many levels as a flash.)

## zombie.iso.IsoChunk (eighth edit, 2026-09-21 evening, strong light stamps)

Two per-level frame arrays (index z + 32): `pzoptLightStrongFrame`, the frame a square of the
level accumulated a strong light change, and `pzoptLightBakeFrame`, the frame the level's
texture was last baked. `resetForStore()` calls `pzopt.LightDirt.chunkReused(this)`, which
fills both with -1.

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-21 evening, strong light changes)

In the bake-decision block of `renderChunkLevel` (the `lightingRebakeMs` hold and the
`rebakeBudget` spread): a level whose lighting-only dirt is strong
(`pzopt.LightDirt.rebakeNow(chunk, level, frameNo)`: marked strong since its last bake and no
global light event in progress) skips the `lightingRebakeMs` hold and gets an unlimited frame
budget in the spread, so it re-bakes this frame like stock; it still counts as a started re-bake
for the weak ones behind it. Where the texture is (re)baked, `pzopt.LightDirt.baked` stamps the
level's bake frame. Counters `strong now=`, `strong marks=`, `global light events=` on the
periodic log line. Verified on the night-torch spinning bench (`bl-torch-*` runs, zoom 1,
recorded): hard-jump pixels per frame between consecutive recording frames stock 7.7 k, holds
off 7.9 k, this fix 7.9 k, before 9.9 k (p90 16.7 / 17.5 / 17.9 / 22.3 k).

**Strong budget (2026-09-22 night).** The strong path had no cap, and turning marks far more
than a beam does: the out-of-sight fade (`darkMulti`, a quarter of its 1/1000 move in the
accumulator) flips on every exterior square the vision cone crosses, so a spinning player marked
about every on-screen level strong every frame. Cheap on the Rosewood spin at 400 fps (`strong
now=` ~2 a frame), but on the Louisville horde preset (`--preset louisville`, downtown, eight
levels a chunk, `see_all`) it was 100+ tall-chunk bakes a frame: 10.8 fps, GPU 97 %, chunk
bakes 30 % of the game thread, and the never-baked levels behind them starved black (the
"black squares" seen during run `lou-base`; `lightingStrongDelta=100000` gave 26.9 fps, GPU
35 %, bakes 4 %). New `pzoptStrongNow(chunk, level)` grants the strong path to at most
`Config.lightingStrongBudget` (8) levels a frame (`pzoptStrongThisFrame`, reset in
`renderInternal` with the other per-frame budgets); a strong level past the budget takes the
ordinary `lightingRebakeMs` hold and the spread, i.e. it still re-bakes within 250 ms. A torch or
headlight beam touches a handful of levels a frame, so the blocky-lights fix above keeps its
whole budget; only sweeps that mark dozens are spread. Counter `strong past budget=` on the log
line; `lightingStrongBudget=0` restores the uncapped behaviour for A/Bs.

**Creation first (2026-09-22 night, second pass).** With the strong budget in place the black squares
still showed under any extra load: the never-baked budget (`bakeBudget`, 8) was counted against
`pzoptBakesThisFrame`, which every bake incremented, so eight re-bakes of any kind (strong, lighting
spread, redraw, object changes, all allowed before it) used the budget up and the never-baked levels
of the chunks late in the draw order were deferred again and again, black on screen while re-bakes
kept flowing every frame. Now the budget counts creations alone (`pzoptCreatesThisFrame`), and while a
creation was deferred in the previous frame (`pzoptCreatesDeferredLastFrame`) the optional re-bakes
wait: no strong grant, every lighting-only level is held regardless of `lightingRebakeMs`, and the
spread budget is zero (its `lightingRebakeMaxFrames` / `rebakeMaxFrames` caps still apply, so nothing
stays stale for long). Object, tree, cutaway and obscuring re-bakes are never held, as before. Counter
`creations deferred=` on the log line.

## zombie.iso.LightingJNI + FBORenderCell (2026-09-21 evening, lighting-budget flush)

The second half of the "blocky lights" report, the 120 km/h night drive: with `lightingBudget`
(8 chunks a frame) the chunks past the budget are refreshed on a later frame, and if the next
lighting pass lands first it rewrites every per-square dirty bit, so their squares read "not
dirty" and keep the light of the previous pass (chunk-sized dark patches inside the headlight
beam; `--prop lightingBudget=0` was clean). Reading a non-dirty square anyway is no cure: the
engine answers with the previous pass (tried as a forced read, it produced a checkerboard).

`LightingJNI.update`: before `stateBeginUpdate` for each player it calls
`FBORenderCell.pzoptFlushPendingLighting(playerIndex)`, which refreshes every chunk level still
in the budget's pending queue (on-screen chunks only, the player index pinned in
`IsoCamera.frameState` for `cacheLightInfo`) and clears the queue. The budget therefore spreads
the refreshes over the frames between two passes (2-4 at 60 fps with the 15-30 Hz lighting
thread) and whatever is left lands in the frame the pass arrives, never lost. Counter
`flushed=` on the log line. Verified on the SportsCar night drive (`bl-drive-*` runs, race cars
have no headlight beam by script).

## zombie.network.NetChecksum (added 2026-09-21, multiplayer Lua checksum)

A user joining a community server was refused with `File doesn't exist on the server:
media/lua/shared/pzopt/pzopt_keybinding.lua`. When a client connects, `LuaManager.LoadDirBase`
feeds every file under `media/lua/shared` and `media/lua/client` (game and mods) into
`NetChecksum.Checksummer.addFile`: an MD5 over all of them that the server compares with its
own, plus groups of per-file checksums the server walks to name the odd file when the totals
differ. Stock leaves `SandboxVars.lua` out of the list. The three pzopt Lua files (the
Optimizations tab, the frame-cap combos, the F9 key binding) are installed into the client's
`media/lua/{shared,client}/pzopt/` and exist on no server.

One edit, at the top of `Checksummer.addFile`: with `Config.luaChecksumExempt` (default true)
a path that `pzopt.LuaChecksum.exempt` recognises (`media/lua/<sub>/pzopt/...`, either slash
style, any case) returns before the file is read, so it enters neither the total nor a group.
Everything else is byte-identical stock. The exemption is deliberately not tied to `enabled`:
the files are on disk either way, and a server that has the overrides installed skips them
too, so both sides agree whichever of them carries the files. Unit test `LuaChecksumTest`.

Verified 2026-09-21 on the MacBook against a stock dedicated server (an APFS clone of the game's
`Contents/Java` with every pzopt file removed, `zombie.network.GameServer -nosteam -no-worldgen`
in its own `-cachedir`, `DoLuaChecksum=true`), the client with the overrides installed, started
with `+connect 127.0.0.1:16261 -nosteam`, its own `-Ddeployment.user.cachedir`, a throw-away
`media/lua/client/pzopt/pzopt_devjoin.lua` that presses Connect / spawn / character Next on
`OnFETick`, and a `mode=verify` flag file so `AutoStart` presses click-to-start. Default:
`client: DoLuaChecksum start` → `end`, `OnGameStart` in the world. `luaChecksumExempt=false`:
`force-disconnect checksum-File doesn't exist on the server: media/lua/shared/pzopt/pzopt_keybinding.lua`,
the reported message. A killed client leaves "User is already connected" on the server;
`kickuser` on its console clears it.

## zombie.WorldSoundManager (added 2026-09-22, world-sound hitch)

Loose copy with one edit in the full `addSound`, behind `pzopt.Config.WORLD_SOUND_FAST`. Stock
attaches a new sound to every chunk in the square of side `2 * radius * hearingMultiplier` around it
by asking the cell for each of those world chunk coordinates, so a 600-tile house alarm (or a 500-tile
helicopter pass, a 600-tile meta gunshot) asks for 22,500 chunks at normal hearing and 202,500 at
pinpoint hearing, of which only the loaded grid (a few hundred) can answer. The edit intersects the
radius rectangle with the union of the active players' loaded chunk ranges before the walk; the cell
returns null outside that union, so the chunks that receive the sound are exactly stock's. The rest of
the method (the sound object, the manager list, the population manager call, the network send) is
untouched. Why it matters: `zombie.iso.Alarm.update` adds its 600-radius sound every frame for the
~49 s an alarm rings, so every per-call cost here is a per-frame cost during an alarm.

## zombie.iso.FishSchoolManager (added 2026-09-22, world-sound hitch)

Loose copy with a memo in `addSoundNoise`, behind `pzopt.Config.WORLD_SOUND_FAST`. Every world
sound (from `WorldSound.init`, single player and server) scares the fish: the stock method walks
every square of the disc of radius `soundRadius / 6` around the sound (40,000 squares for the house
alarm, with a square root, the procedural fish-point roll, the no-fish-zone list and a boxed
hash-map probe per square) and writes "disabled until now + 180 game minutes" for each fish or chum
point in it. A repeat of the same call in the same game minute rewrites the same keys with the same
value, i.e. does nothing, and the house alarm makes exactly that call every frame. The edit keeps
the last eight (centre, radius, game minute) calls and returns at once for a repeat; the memo is
dropped whenever the noise or chum maps are cleared, purged of expired entries, replaced by the
server's copy, or a chum point is added, so a call that could write something new always runs.
Saved and transmitted data are unchanged (the maps themselves are never touched by the memo).
Same key, same day, the walk itself: stock tests every square of the box against all fourteen no-fish
rectangles, takes a square root for the disc test and probes the chum map. The override's walk selects
the rectangles that meet the box once per call (none, almost always), compares the squared float distance
with the squared radius (the same float arithmetic as the stock helper; the square root is monotonic, so
the test is identical), probes the chum map only when chum points exist, reads the game clock once, and
handles a missing `Fishing.NoFishZones` table as stock does (every square a no-fish zone). Same keys, same
values: `tests/pzopt/FishNoiseWalkTest.java` compares it with the stock loop over 420 walks. The
helicopter's moving 500-radius sound goes 0.27 → 0.08 ms per call.

## zombie.core.skinnedmodel.animation.AnimationPlayer (added 2026-09-22 night, zombie bone math on the other cores)

Louisville horde profile (`lou-budget`, game thread 98 % busy): the zombies' postupdate is 17 % of the
game thread and 9 % of that is `updateModelSlot`, i.e. `AnimationPlayer.Update` blending every live
track's keyframes into the bone transforms, the body-angle steps, the twist bones, the model-space and
skin matrices. That math only touches the player's own arrays, the read-only clips, the thread-safe
pools (`Pool`, `ObjectPool`, `HelperFunctions`' locked matrix stack) and a set of static scratch
objects, so it can run on any thread once the scratch is per thread.

Edits: the seven static scratch holders (`L_applyTwistBone`, `L_getBoneModelTransform`,
`L_getTrackTransform`, `L_getUnweightedBoneTransform`, `L_getUnweightedModelTransform`,
`L_updateBoneAnimationTransform`, the deferred-movement bone-index array) hold instance fields now,
one instance per thread through a `ThreadLocal`, and every use goes through it; the static `tempo`
vector is a `ThreadLocal` too. In `updateInternal`, after the multi-track tick (which fires the
animation events and stays on the game thread) and the non-visual / shared-skeleton branches, the
standard-animation branch first offers the player to `pzopt.AnimBatch.submit`; when accepted the
method returns and the batch calls the new `pzoptRunDeferred(deltaT)` (the standard animation plus
`postUpdateRagdoll`) later. `pzoptBatchable()` says no for a child player (copies its parent's
bones), a ragdoll or a recording player. Key `animBonesParallel` (true); `animBonesThreads` (8)
worker threads, the game thread joins in. Nothing changes in the order of a single player's work,
only where the second half runs.

`isBoneReparented(boneIdx)` is a plain loop over the reparented-bone list instead of
`PZArrayUtil.contains` with a pooled `Lambda.predicate`: stock allocated and released one pooled
predicate per bone per character per frame (~30k a frame on the horde), and every pool alloc /
release bumps shared atomic statistics counters, which the batch's worker threads all contended on
(5 % of the game thread waiting inside `PooledObject.release` in run `lou-rec-fix`). Same answer,
no allocation, inline or batched.

## zombie.MovingObjectUpdateScheduler (added 2026-09-22 night, zombie bone math on the other cores)

`postupdate()`: `pzopt.AnimBatch.begin()` before the bucket loop and `flush()` after it (in a
`finally`), client side only. The batch runs after the loop rather than overlapping it because
`IsoGameCharacter.updateAnimPlayer` (the model-less path, most of a horde) flips
`PerformanceSettings.interpolateAnims` around each call and the keyframe sampling reads that flag; the
join is before anything reads a bone (attachments, the render data). Zombies being grappled or
grappling, or that reanimated a dead player, are refused by the batch (the other side reads their
bones in the same loop) and run inline. Counters on the periodic FBORenderCell log line:
`anim batch: frames= batched= inline= max= work ms= wait ms=`; a failure inside a deferred update is
logged once and turns the batch off for the rest of the session.

## zombie.characters.IsoZombie (added 2026-09-22 night, vehicle cull for the line-of-sight test)

`isVehicleBetween`: for every loaded vehicle stock transforms the zombie-to-target segment into the
vehicle's local space (two matrix multiplies, three pooled vectors) and runs the exact box test — per
zombie that could see the player, per frame. Downtown Louisville has hundreds of parked cars, so
`BaseVehicle.getIntersectPoint` was 6 % of the game thread. Now `pzopt.VehicleCull.mayIntersect`
runs first: the vehicle's bounding circle (half the horizontal diagonal of its script extents plus the
centre-of-mass offset plus a 1-tile margin (getX/getY follow the physics origin a tick behind)) against the segment's nearest point; a miss skips the
exact test, a hit runs it unchanged. Key `vehicleCull` (true); a vehicle without a script always runs
the exact test.

## zombie.iso.fboRenderChunk.FBORenderCell + IsoChunk + MultiTextureFBO2 (edit of 2026-09-22, chunk textures across zoom changes)

Stock frees a chunk level's textures the frame the level leaves the screen (`checkNewlyOnScreenChunks`,
`renderOneLevel`: `freeFBOsForLevel`) and creates them again, dirty, when it returns. Zooming in
shrinks the screen, so it frees most of what was visible; zooming back out bakes every level that
reappears in the frame it appears, and the bake budget never caught those: `DIRTY_CREATE` is only set
by `createFBOForLevel`, inside `beginRenderChunkLevel`, after the deferral decision, so a level with
no texture yet always baked at once. On the south route at 5120x2160 a 0.25 → 2.5 wheel spin was 320-410
bakes in one frame, 77-109 ms, then a 375 ms frame (the render thread allocating the textures); one notch
out at wide zoom 45-51 ms frames (stock runs `zs-out-jump9`, `zs-out-wheel`; rig `--flag zoom_cycle=`
`zoom_span=` `zoom_jump=`, `harness/zoomsteps.py`).

Now (`pzopt.ZoomRetain`, keys `zoomRetain` true, `zoomRebakeBudget` 12, `zoomFrameMs` 10, `zoomPlaceholder`):

- Off-screen levels go through `ZoomRetain.releaseOffScreen`: the textures stay while the chunk lies
  inside the screen rectangle the widest zoom would show (centred on the camera character like
  `PlayerCamera.center`, one chunk of margin; the high-res texture, a debug option, inside the widest
  zoom below 0.75), else they are freed as before. That is the set stock holds at the widest zoom, so
  the texture memory stays within stock's own maximum. Chunk unload still frees everything.
- A level coming back on screen that was seen before (`prevMinZ` set) gets a bit in
  `IsoChunk.pzoptZoomReturned[player]` (cleared in `removeFromWorld`); stock's `invalidateLevel(1024)`
  stays. In `renderOneLevel` such a level, and every first-sight level while a zoom flood lasts (the zoom
  changed this frame or zoom work was deferred last frame), bakes only when the frame's plan allows it;
  otherwise it is held with its kept texture on screen (the stale path), its other-scale texture, or
  nothing, and returns. Object / item / obscuring dirt still bakes at once; lighting, redraw, tree and
  cutaway dirt is held.
- The plan (`pzoptZoomPlan`, once per player per frame before the chunk loop): the pending levels of
  the loaded grid sorted by their chunk's distance to the camera character, the first N allowed
  (`IsoChunk.pzoptZoomAllowed`), so the picture fills from the player outwards and the cutaway-relevant
  chunks land first. N follows the last game-thread frame: over `zoomFrameMs` it halves (a fresh
  chunk texture costs the render thread ~1 ms of GL allocation on top of the bake, which shows up as a
  hand-off wait), under 3/4 of it grows by two, within [4, `zoomRebakeBudget`]. Credits the plan did
  not give to a pending level go to first-sight flood levels in chunk order; in the frame the zoom
  changes nothing new starts at all (the returned set is only known after that frame's on-screen scan).
- `MultiTextureFBO2.pzoptWidestZoomBelow(limit)`: the widest selectable zoom under a limit (the high-res
  rectangle).
- Two faults of the first build (`3441a1c`, found by the parity watch on the Louisville walk the same
  night): a level re-entering the screen by camera motion carried its kept texture into the ordinary re-bake
  hold (`rebakeBudget`, up to 3 frames of the stale texture, read as roof flicker), and a returned level that
  was then occlusion-culled or freed kept its pending bit, which the plan took for a running zoom flood, so
  every first-sight chunk level was budgeted for the rest of the session (black chunk levels downtown). Now a
  camera-motion return is allowed in the frame it appears (the texture is redrawn before it is shown, as
  stock's fresh one was), the bits clear on the occlusion and off-screen paths, and only an actual zoom
  change or a real deferral keeps the flood on.
- A Workshop player reported the first build's stall as it looks in play (2026-09-22): chunk textures stop
  appearing past a fixed radius and waiting never fills the rest; `zoomRetain` off cures it. Two guards on top
  of the previous fix, in the second build: the plan now honours the on-screen scan's "allowed at once" marks
  outside a flood without charging them to the budget (`checkNewlyOnScreenChunks` runs before `pzoptZoomPlan`,
  which used to zero every allowed bit, so the camera-motion returns of the previous fix still competed
  nearest-first inside the 4-12 budget; after a slow frame a pan over seen ground drew stale textures again),
  and `pzoptZoomSettle`, after the chunk loop, drops every allowed bit still pending: the level never reached
  the gate (chunk skipped while its lighting is not done, level index not visited after a `minLevel` change,
  clean level with a texture), so a bit no path clears can take at most one credit and the plan can never
  starve again. Counter `dropped=` in the periodic `zoom kept=` log line; 0 on the bench route.

Results (240 cap, south route, `zoomsteps.py --window 1.0`): 0.25 ↔ 2.5 instant jumps, worst frame per
jump 375 / 86 / 59 / 52 ms (stock) → see `docs/results.md` for the adopted build's numbers.

## zombie.characters.IsoPlayer (added 2026-09-22, player line-of-sight pass)

Loose copy with three edits behind `pzopt.Config.PLAYER_LOS_FAST` (`playerLosFast`, true), plus the
class-loaded marker. Profile: on the Louisville preset (2,433 zombies loaded, spectator view) the
player was 16 % of the game thread and `updateLOS` 15 of it, 12 in its own body.

- `updateLOS`: stock keeps every object the player spotted since the last quiet frame in the
  `lastSpotted` Stack and asks it `contains` once per object spotted this frame, plus once more per
  zombie within a few tiles: a linear walk of a synchronized Vector. The list only empties on a frame
  that spots no zombie at all, so in a horde it holds every zombie ever seen and the walk is spotted ×
  remembered identity compares a frame. The override keeps an identity set beside the stack
  (`pzopt.PlayerLos`): `sync` at the top of the pass rebuilds the set whenever the stack object or its
  size is not what the set last mirrored (a mod adding through `getLastSpotted` or replacing it through
  `setLastSpotted` is picked up), the two `contains` become one probe each, the end-of-pass add goes
  through the set (add unless remembered, then push), and the periodic clear empties both. The stack
  keeps exactly the stock contents and order. The end-of-pass loop also reads each spotted object once
  instead of three synchronized `get`s.
- `getSneakSpotMod` (new override of the `IsoGameCharacter` method): every zombie that could see the
  player asks for it in `spottedNew`, and stock walks the perk list for the sneak level each time; the
  answer cannot change between two zombies of one frame, so it is memoised per `IsoWorld` frame number
  (a level gained mid-frame shows to the next frame's zombies).

## zombie.characters.IsoZombie (second edit, 2026-09-22, spot roll early-out and nearby vehicles)

- `spottedNew`, behind `pzopt.Config.ZOMBIE_SPOT_FAST` (`zombieSpotFast`, true): the vision-radius
  update moves above the look-vector / facing block (neither depends on the other), and a chance that is
  already zero (the player beyond this zombie's vision radius, or in the dark) skips the look-vector
  trig, since the facing block only scales the chance. After the facing block a zero chance (not
  forced) skips every remaining modifier (movement, sneak, traits, shelter, the vehicle test, worn
  items, the pow and the roll): each one multiplies or divides the chance, so the roll could not
  succeed, and the code goes straight to the one early exit a zero chance still takes (a nearer current
  target) and to the failed-roll bookkeeping, which is unchanged: the could-be-seen flag needs a chance
  above 20, and the sneak / lightfoot XP rolls never read the chance. The only difference to stock is
  one fewer `Rand.Next(10000)` draw from the shared generator per skipped zombie.
- `isVehicleBetween`, under the existing `vehicleCull`: instead of walking every loaded vehicle, the
  walk covers `pzopt.VehicleCull.near(...)`: the per-frame list of the vehicles whose bounding circle
  reaches the disc around the target (the player) of radius max(view distance, this zombie's distance)
  — every zombie asking in a frame asks about the same player position, and `spottedNew` only asks
  within the view distance, so the list is built once per player per frame (rebuilt on a new frame, a
  moved target or a larger reach). A vehicle without a script is always a candidate. `nearBuilds()`
  counts the builds.

## zombie.iso.LightingJNI (2026-09-22, dead end: clean squares asked once per frame — removed)

Tried and removed the same night: a per-square stamp in `JNILighting.updateFBORenderChunk` so a square
the native called "not dirty" was not asked `getSquareDirty` again in the same frame under the same
`updateCounter`. A dev rig re-asked on every skip: 0 misses in 1,500 frames (the stamp was sound), but
only 5,636 skips against 19.1 M dirty answers and 173 k clean ones — the native reports nearly every
visible square dirty on nearly every frame, so the whole on-screen set is re-fetched each frame
(`getSquareDirty` 4 % + `getSquareLighting` 3 % of the game thread on the Louisville preset). That is
the shape of the "lighting jni" cost; a per-frame memo cannot touch it. Also learnt: `getChunkDirty`
is only legal between `stateBeginUpdate` / `stateEndUpdate` (it throws `missing stateEndUpdate?`
elsewhere), so a chunk-level gate cannot live in the square accessors either (run `lou-los4`: the
exception on every frame left the zombies invisible and a meaningless 91 fps).

## zombie.core.skinnedmodel.animation.AnimationPlayer (third edit, 2026-09-22, shadow ellipse and palette on the worker)

- `updateInternal` clears a "shadow valid" flag at its start; `pzoptRunDeferred` ends (after the skin transforms)
  with `pzoptPrecomputeShadow` behind `pzopt.Config.SHADOW_PREP` (`shadowPrep`, true): the head and both feet bone
  indices (through the cached `getSkinningBoneIndex`), then `pzopt.ShadowPrep.compute` — stock's
  `IsoGameCharacter.calculateShadowParams(player, 1, false, sp)` arithmetic step for step with thread-local scratch
  (the stock static `L_renderShadow` holder cannot be shared by workers), packed into a long on the player and the
  flag set. `pzoptShadowParams()` returns it while the flag holds (0 otherwise). Out-of-range bone indices leave the
  flag clear (stock path). Counters `shadow computed= served= fallback=` on the `anim batch:` log line.
- `SkinTransformData` carries a `FloatBuffer` palette and a valid flag (`skinPalettePrecompute`, true, needs
  `skinTransformsPrecompute`): `pzoptPrecomputeSkinTransforms` stores each set it computed into the set's own buffer
  (`Matrix4f.store` per bone, the shader's column order, flipped); `getSkinTransforms` clears the flag whenever it
  recomputes a dirty set; `pzoptSkinPalette(skinnedTo)` (game thread) returns the buffer, rewound, when the set is
  clean and its palette valid, else null.

## zombie.core.skinnedmodel.advancedanimation.AnimatedModel (added 2026-09-22, palette hand-off)

Inner class `AnimatedModelInstanceRenderData.initMatrixPalette`: with `skinPalettePrecompute` the draw data asks the
player for the precomputed palette of the model's skinning data and, when it gets one, sizes its own buffer to it and
copies it in a single bulk `put` (then flips and marks the palette valid, as stock); otherwise the stock loop
(`getSkinTransforms`, sixteen puts per bone) runs. Stock's `init()` on the render thread and the shader upload read
the draw data's buffer as before.

## zombie.characters.IsoZombie (fourth edit, 2026-09-22, shadow from the worker, thread-local facing test)

- `calculateShadowParams(ShadowParams)` override (`shadowPrep`): when the animation player holds a pair from its last
  deferred update and is ready, `sp.set(0.45, fm, bm)` from it (`served++`); else the inherited computation
  (`fallback++`). Sits in the tail block with the cached-component accessors.
- `isFacingTarget` (the `isFacingTarget` animation variable, read by transition conditions that now evaluate on the
  frame workers): the two vectors are a thread-local pair instead of the inherited static `tempo` / `tempo2`; same
  arithmetic.

## zombie.characters.IsoGameCharacter (added 2026-09-22, postUpdateAnimating split for the parallel transition evaluation)

`postUpdateAnimating` (private) is split at the point after `setTurningAround`: everything from
`getActionContext().update()` to the end (network AI post-update, the animator update, the three
`ActiveAnim*` event clears, `applyDeltas`, `updateAnimPlayer` or `updateModelSlot`, `updateLightInfo`, the
animation recorder, the finishing event) moved verbatim into a new public `pzoptPostUpdateAnimatingRest()`. The
original method computes the forward direction, the vertical aim angle and the three turning flags as before, then
asks `pzopt.ActionEval.submit(this)`: true (an eligible zombie inside a batch) returns at once, the rest runs from
`ActionEval.flush()` in loop order after the parallel evaluation; false calls `pzoptPostUpdateAnimatingRest()`
directly, i.e. the stock sequence. Note for the regen: CFR renders the model-less / model branch of this method
wrongly (as two sequential blocks); the bytecode and Vineflower have `if (!hasActiveModel()) updateAnimPlayer else
updateModelSlot`.

## zombie.characters.action.ActionContext (added 2026-09-22, evaluate on a worker, apply on the game thread)

`actionEvalParallel` (`pzopt.ActionEval`). `updateInternal` is stock's set / evaluate / transfer, with a first check:
when this context was evaluated by the current batch (a generation stamp) and the batch is being applied, only the
transfer runs. New methods: `pzoptEvaluate()` (worker: set + evaluate into the "next" container, stamp the
generation), `pzoptOffThreadSafe()` (the root state and every sub-state have only `CharacterVariableCondition`,
`EventOccurred` and `EventNotOccurred` conditions — anything else, i.e. `LuaCall`, keeps the zombie inline; cached
per `ActionState` in an identity map, game thread only; reads `ActionTransition.conditions`, package-private, which
is why the check lives here). With `devActionEvalCheck=true` the apply step first evaluates again on the game thread
into a scratch container and counts a mismatch when the two containers differ (`ActionEval.checks / mismatches`,
first 20 logged with the state names): the determinism rig of this phase.

## zombie.MovingObjectUpdateScheduler (third edit, 2026-09-22, two-phase postupdate)

`postupdate()` begins both batches (`ActionEval.begin()` after `AnimBatch.begin()`), runs the bucket loop, then in
the finally `ActionEval.flush()` (the parallel evaluation, then every queued zombie's
`pzoptPostUpdateAnimatingRest()` in order, which is where their bone math gets queued) and last `AnimBatch.flush()`.
Log line `action eval:` next to `anim batch:` (FBORenderCell).

## pzopt.FrameBatch (2026-09-22)

The worker pool both batches share (`frameThreads`, default 8 = the old `animBonesThreads`, clamped to cores - 1):
`run(count, runner)` executes indices 0..count-1 on the daemon workers `pzopt-frame-N` plus the calling thread and
returns when all finished, with the first exception a task threw. `AnimBatch` lost its own pool and threads.

## zombie.core.textures.MultiTextureFBO2 (edit of 2026-09-22, zoom motion as a cubic Bézier)

`update()`: stock moves the zoom towards the target by a fixed 0.03 per frame (0.004 × 1.5 × 5 for a manual
change; auto-zoom without the ×5) and snaps onto it, so a wheel notch takes 8 frames whatever the frame rate
(16 ms at 500 fps, 130 ms at 60), at constant speed with an abrupt stop. With `zoomEaseMs` > 0 (300) a
manual change (`autoZoom` off for the player) takes `pzoptEase`: the first frame that sees a new target
(any entry point: `doZoomScroll`, `setTargetZoom`, `setZoomAndTargetZoom`) records the current zoom and the
time; every frame after that sets `zoom = from + (target − from) × curve(elapsed / zoomEaseMs)` with
`pzopt.ZoomEase`, a CSS-style cubic Bézier through (0,0) and (1,1) with control points from `zoomEase`
(x1,y1,x2,y2; default 0.25,0.1,0.25,1.0 = CSS "ease"; the solver is Newton steps on x(t) with a bisection
fallback, `tests/pzopt/ZoomEaseTest`). A new target during the motion restarts the curve from the current
zoom, so nothing jumps; `dirtyRecalcGridStackTime` is set while it moves as stock does. `zoomEaseMs=0` is
the stock step. Auto-zoom keeps the stock step (it retargets every frame with a distance term, a restarted
curve would never leave its slow start).

## zombie.characters.IsoPlayer (second edit, 2026-09-22, experiment: the LOS pass in C++)

Asked for after the player pass: the same logic rewritten in the lighting engine's language. Key
`pzopt.Config.PLAYER_LOS_NATIVE` (`playerLosNative`, **false**, experiment). `pzoptUpdateLosNative` is
the stock loop split in three: Java packs every object that passes the cheap filters (position, the
could-see / can-see bits read from its square's `JNILighting`), one call into
`natives/libpzopt_los64.so` (`src/native/pzopt_los.cpp`, `pzopt_los_pass`: the distance with
`IsoUtils.DistanceTo`'s float semantics, the "close" count and the branch per object) through the FFM
linker with `Linker.Option.critical(true)` (heap arrays read in place, no JNI copy; `pzopt.NativeLos`),
then Java applies the side effects in stock order (alpha targets, spot tests, stats, the spotted list).
The library is only built with `PZOPT_NATIVE=1 scripts/build.sh` and never ships; with the key on and
the library missing the Java loop runs.

Result: no difference. Micro-benchmark over 2,433 objects on the same JIT: Java 3.6 µs a pass, C++
through one FFM call 3.7 µs (C2 emits the same `sqrtss`; a whole pass is ~0.01 % of a 30 ms frame);
per-object native calls 102 µs a frame (42 ns a crossing), thirty times worse. In game, an isolated
pair from a worktree of HEAD + this pass only (`lou-losn4-on` / `-off`, 05:12 / 05:16, library
confirmed loaded): player 3.86 % vs 3.52 % of the game thread, 1.01 vs 0.99 ms a frame in absolute
terms, `NativeLos.run` one sample in 2,600, the loop's own self time 0.19 vs 0.27 % (two samples);
the 38 vs 35 fps gap is the route's run-to-run noise (two Java-only runs the same hour were 53 and 60). What is left of the player's cost is reads of Java object state (visibility bits,
zombie state, rooms) and the spot tests' side effects, none of which a native pass can take over
without Java packing it first — the packing loop is the loop. Everything worth moving out of Java was
the algorithm (the `lastSpotted` walk), not the language.

## zombie.iso.LightingJNI (fifth edit, 2026-09-22, square reads on the frame workers)

`lightingReadParallel` (`pzopt.LightingBatch`). In `JNILighting`: the static `lightInts` scratch the native fills
became a thread-local array (`pzoptLightInts`, both `update` paths take a local from it); the room-seen block at the
end of `updateFBORenderChunk` (`checkRoomSeen`, then `Meta.dealWithSquareSeen` for a square seen for the first
time) asks `LightingBatch.current()` first: on a worker the square is recorded in the task's effects list (only
when the hooks would do something: first time seen, or its room unexplored) and the game thread runs the hooks after
the batch; on the game thread the block runs as stock. New `pzoptRecheck()` (dev): asks `getSquareLighting` again
and compares with the stored visibility bits (bit 2 excluded: the facing rule may force it), light colour, dark
multipliers, light level and vertex lights. Everything else the read does — the square's fields, the level's
`invalidateLevel`, `LightDirt.markStrong`, `PuddleCache.lightsChanged`, `FBORenderCutaways.squareChanged` — is per
square, per chunk level or an idempotent write, and the natives are pure reads (disassembly of libLighting64.so:
`getSquareDirty` is an index computation and a byte load; `getSquareLighting` reads the lighting arrays and copies
into the Java array), so per-level tasks do not race.

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-22, the pre-pass lighting drain on the frame workers)

`pzoptFlushPendingLighting` hands over to `pzoptFlushPendingLightingParallel` when `lightingReadParallel` is on: the
on-screen (chunk, level) pairs of the pending queue are collected into two arrays while the game thread creates
what a level's read would create lazily (`getRenderLevels(player)`, `getCutawayDataForLevel(z)`, the
once-per-frame stamp row through the new `pzoptTouchLightInfoRow`); one level alone reads inline, otherwise
`FrameBatch.run` executes `pzoptCacheChunkLevelLightInfo` per level on the workers and this thread with the task's
effects sink installed (`LightingBatch.withEffects`), then `LightingBatch.applyAll` runs the deferred room / meta
hooks in level order. With `devLightingReadCheck` one square in sixteen of every level is re-read on the game
thread through `JNILighting.pzoptRecheck` and mismatches counted. The `lightingBudget` path of the render phase
(`updateChunkLighting`) stays serial. Log line `lighting batch:` next to `action eval:`; the harness summary line
`zombie_batches=` in `pzopt-bench.out` carries all batch counters at route end.

## zombie.core.textures.TextureDraw (added 2026-09-22, characters draw pre-pass)

`drawModel(TextureDraw, ModelSlot)`: after the type and slot id are set, the method asks `pzopt.CharDraw.take(slot)`
for draw data the frame workers already built and initialised for this slot (`charDrawPrep`); when it gets some,
that object becomes the sprite's drawer, the future stays null (nothing left for the render thread to wait on) and
the method returns. Otherwise the stock path runs unchanged: allocate, `initModel`, and `init` on the game's own
eight-thread executor (the `Threading.ModelSlotInit` debug option, on by default) with the future the render thread
waits on. The class also carries the load marker.

## zombie.core.skinnedmodel.model.ModelInstance (added 2026-09-22, characters draw pre-pass)

- `updateLights()`: returns at once when `pzopt.CharDraw.lightsDone(this)` says the pre-pass already ran it for the
  current frame and player (a public int stamp field on the instance, set right after that call). Otherwise stock:
  allocate the per-player data, run its update. Without the pre-pass the stamp never matches. The pre-pass runs it
  on its worker, after `CharDraw.start` has performed, on the game thread, the lazy per-square refresh the method's
  reads sit behind (`square.lighting[p].lightInfo()` for the character's square and the one above it on stairs —
  `JNILighting.update` with its JNI reads and dirty-tracking hooks; stock's `renderShadow` refreshes the same
  square just before the stock call); after that every read in the method is a plain field read.
- Inner class `PlayerData.updateLights`: the two static `ColorInfo` scratch objects the ambient interpolation wrote
  through are a thread-local pair now, and the two `IsoGridSquare.interpolateLight` calls (which write through the
  square class's static `Color` scratch) go to the new `pzoptInterpolateLight`: the same four `getVertLight` reads,
  the same `Color.abgrToColor` conversions and the same three `interp` lerps into the given `ColorInfo`, with seven
  thread-local `Color` objects. The arithmetic, the order of the reads and the smoothing steps are stock. The
  static fields stay declared and unused.

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-22, characters draw pre-pass)

- `renderInternal`, right after the player index is read: with `charDrawPrep` on, `pzopt.CharDraw.walk(objectList,
  this)` hands the walk of the cell's object set (stock iteration order; the on-screen objects, and the zombies that
  pass the model tests independent of this frame's cutaway / lighting passes) to one task of the pass's pool.
- `renderTilesInternal`, after `checkBlackedOutRooms` and before `performRenderTiles` (the cutaway checks, the
  lighting refresh and the blacked-out passes are done, i.e. everything the draw data reads): `pzopt.CharDraw.start`
  takes the walk's result, applies the square light-info / cutaway visibility test on the game thread, runs
  `checkUpdateModelTextures` and the lazy lighting refresh of the zombie's square (and the one above it on stairs)
  for the zombies kept, and hands them to the pass's own pool (`charDrawThreads`) chunk by chunk — `updateLights`
  (plain reads after that refresh), `initModel`, `init`, the camera record; the chunk bakes, the composite, the players, the corpse shadows, world items and
  puddles that follow overlap that work.
- `renderMovingObjects`: `pzopt.CharDraw.join(...)` waits for the tail of those tasks (or runs the whole pre-pass when
  `start` did not) and returns the on-screen list; the loop then runs the stock `renderMovingObject` over that list
  only, and `CharDraw.finish()` releases anything prepared and not drawn. A null (the pass failed this frame) or
  the key off = the stock loop over the whole set.
- New `pzoptShouldRenderSquare(square)`: the private `shouldRenderSquare` for the pre-pass's predicate.
- `renderMovingObject`: the `renderShadow` call is skipped for a scene-culled character without an active model that
  is not a fake-dead zombie (`pzoptShadowIsNoOp`): since the zombie session's reorder that call returns before
  drawing anything for exactly that case, and every earlier test in it returns too, so nothing changes; ~1,100 of
  the 1,600 on-screen objects of the horde are such zombies.
- The periodic log line gets `char draw:` (frames, on-screen objects per frame out of the walked set, batched per
  frame, max, taken, leftovers, walk ms on the worker, walk waits / ms and start ms on the game thread, join waits / ms).

## zombie.characters.action.ActionContext + conditions.CharacterVariableCondition (second edit, 2026-09-22, the callback snapshot)

The first parallel build ran every transition condition on the workers and tripped on the variables whose callback
is not a read: `blunge` runs `PolygonalMap2.lineClearCollide` (whose `LineClearCollideMain` keeps a plain
`ArrayDeque` point pool — corrupted from two threads, then `NoSuchElementException` / `NullPointerException` in
every later line test on the game thread, wrong lunge / attack answers and, in the parity session's run, the
god-mode ghost dying), `bHasTarget` clears the target, `bthump` drops the thump target, `beatbodytarget` scans the
corpses nearby, `turndirection` uses the inherited static vectors. So, behind the same `actionEvalParallel`:

- `CharacterVariableCondition` exposes its two lookups (`pzoptLhsLookup` / `pzoptRhsLookup`) and a static
  `pzoptSnapshotValue(lookup, owner)`: null when the owner's slot for it is a stored value or a callback whose key
  is in `pzopt.ActionEval.PURE_CALLBACKS` (the audited pure reads: field getters, the facing test, `canRagdoll`,
  the animation angles...), else the typed value read now (a `NULL` marker for null). The classification is
  cached on the lookup (`pzoptPure`). `CharacterVariableLookup.pzoptGetValue` first consults
  `ActionEval.currentSnapshot()` (a thread-local `IdentityHashMap<lookup, value>`) and returns the snapshot value
  when present.
- `ActionContext` keeps one snapshot map; `pzoptSnapshot()` (game thread, called by `ActionEval.submit`, i.e. at the
  point in the zombie's postupdate where stock would have evaluated) reads every non-pure variable of the current
  state's and sub-states' conditions into it — their side effects happen there, in order, as in stock; a callback
  stock would have short-circuited past runs once more than stock. The per-state cache (`pzoptStateLookups`)
  holds the lookup array of a safe state or an UNSAFE marker. `pzoptEvaluate()` installs the map for the worker
  evaluation, and the `devActionEvalCheck` re-evaluation installs it too.

## zombie.characters.IsoGameCharacter (second edit, 2026-09-22, shadow params only where drawn; ragdoll test order)

Both pure reorders, suggested by the characters-draw session from the Louisville profile:
- `renderShadow`: stock computed `calculateShadowParams` (three bone projections, three nearest-point tests) before
  the branch that returns for a scene-culled character, i.e. for the ~1,100 culled zombies of a 1,600-object horde
  frame whose shadow is never drawn (2.5 % of the game thread). The culled return is tested first (same condition:
  no model path, not a fake-dead zombie, scene-culled) and the params are computed after it. Same numbers on every
  drawn shadow.
- `render`: `getRagdollController() != null && canRagdoll()` instead of the reverse (`canRagdoll` walks the
  state per zombie per frame; both are pure reads).

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-22, adaptive strong re-bake budget)

`pzoptStrongNow` takes its per-frame budget from `pzoptStrongBudget()`: with `pzopt.Config.LIGHTING_STRONG_FRAME_MS`
(`lightingStrongFrameMs`, default 0 = the fixed `lightingStrongBudget` since 06:45; 20 in the runs of 06:00-06:40) the budget follows the previous game-thread
frame step (`FrameCap.lastStepNs`, the limiter's wait excluded) — over the threshold it halves (down to 1), under
three quarters of it grows by one, up to `lightingStrongBudget`. Why: the out-of-sight fade (`darkMulti`) moves per
unit of game time, so a slow frame moves every exterior square further, marks more levels strong, bakes more chunk
textures (GPU work) and slows the next frame; on the Louisville preset about a third of the 2026-09-22 runs sat in
that loop at 27-30 fps with the game's own GPU time doubled (30 vs 13-14 ms per frame, GPU 82 %, the parity
session's per-process log showed no other GPU client). Held strong levels still re-bake within `lightingRebakeMs`
or the spread. Counter `strongBudgetCuts=` in the harness summary's `bake_counters=` line (the bake / re-bake
counters since boot, new in `pzopt-bench.out`, via `pzoptBakeCounters()`). Defaulted off after the parity session's
reading of those counters: on a scene that is steadily slow (30-45 fps downtown) every frame exceeds 20 ms, the budget
sits at 1, the held strong squares are re-marked every pass (strong marks 25x in the slow recorded runs) and the
screen shows stale light — the loop it was meant to break turned out to be the zoom session's stale pending bits
(`build-zoom-leak-fix`), not the strong re-bakes. Kept as an A/B key; a spike-relative rule (cut only on a frame well
above the recent average) would be the version worth trying.

## zombie.iso.IsoWorld (added 2026-09-22, the zombie relevance sort)

`sceneCullZombies` sorts the zombies with a model by their relevance to the players before handing out the model /
animation tiers; stock's comparator recomputes both zombies' scores on every comparison (n log n × 2 score walks,
1.4 % of the game thread in a Louisville horde). Behind `pzopt.Config.ZOMBIE_CULL_SORT_FAST` (`zombieCullSortFast`,
true) `pzoptSortZombiesByScore` computes each score once, packs it with the element index into a long
(`pzopt.SortKeys.descending`: the negated sortable float bits in the high word, the index in the low word, so a signed long sort gives score descending, index ascending on ties; -0 folded into +0) and sorts the keys as
primitives, then permutes the list's backing array through a scratch array — the exact order of stock's stable
sort under its comparator (`tests/pzopt/SortKeysTest`). The rest of the method is stock.

## Upscaling pass (added 2026-09-22; `upscaler`, `upscalerQuality`, `upscalerScalePct`, `fsrSharpnessPct`, dlss keys; docs/plan-upscalers.md)

With `upscaler` other than `off` the world pass renders at `upscalerQuality`'s fraction of the screen and
`pzopt.Upscaler` resolves it to the screen size before the stock screen shader; the UI, world text, cursor and
that shader stay at full resolution. `pzopt.RenderScale` holds the scale and the render-thread state. With the
key off every hook below is a no-op.

### zombie.core.textures.TextureDraw (edit of 2026-09-22, upscaling)

`run()`: after the stock `DoStartFrameStuff` (case `glDoStartFrame`) and `DoStartFrameNoZoom`
(`glDoStartFrameNoZoom`) `RenderScale.afterStartFrame(player)` runs; when the frame was started with a player
index and the world framebuffer is bound it replaces the viewport and scissor with the scaled player rectangle
(with the current sub-pixel jitter as a float viewport offset) and marks the render thread "in the world pass".
After `DoEndFrameStuff` (`glDoEndFrame`) the mark is cleared. Case `glViewport` goes through
`RenderScale.requestedViewport`: inside the scaled world pass a rectangle equal to a player's screen rectangle or
the whole screen (IsoWorld's view-cone restore) is scaled the same way; any other rectangle (the FX mask, the cone
texture) is set as requested.

### zombie.iso.IsoCamera (added 2026-09-22, upscaling)

`getScreenLeft/Top/Width/Height` and `getOffscreenLeft/Top`: on the render thread, while the world framebuffer
is bound inside a scaled world pass (`RenderScale.scaledView()`), they return the scaled rectangle. That covers
the viewport restores and uniforms of `ModelOutlines` and `VisibilityPolygon2` (`screenSize`, `displayOrigin`)
and the water / puddle viewport origin without touching those classes. The game thread always gets the stock
values (culling, chunk work, UI, mouse).

### zombie.iso.WaterShader and zombie.iso.PuddlesShader (added 2026-09-22, upscaling)

`startRenderThread`: the `WViewport` size (stock `camera.offscreenWidth / camera.zoom`, the screen size) goes
through `RenderScale.viewPx`, i.e. it is scaled inside the scaled world pass, since the shaders map
`gl_FragCoord` through it for the sky reflection and the noise.

### zombie.core.skinnedmodel.ModelManager (added 2026-09-22, upscaling)

`RenderParticles`: the two `glViewport(0, 0, offscreenWidth, offscreenHeight)` of the fire / smoke pass take
their size through `RenderScale.viewPx` (stock's numbers scaled like the rest of the pass; the stock behaviour
at other zooms is unchanged).

### zombie.iso.weather.fog.ImprovedFog (edit of 2026-09-22, upscaling)

`startFrame` values: `screenWidth/Height` and `cameraOffscreenLeft/Top` (the `screenInfo.xy` / `cameraInfo.xy`
the stock fog shader and `FogPass` map `gl_FragCoord` through) go through `RenderScale.scaledPx`, scaled
whenever the pass is active (every world frame renders scaled; the game thread computes them).

### zombie.core.textures.MultiTextureFBO2 (edit of 2026-09-22, upscaling)

`render()`: before the per-player quads `pzopt.Upscaler.queueResolve()` queues the render-thread resolve
(`GenericDrawer`) of the frame; with the pass active the quad draws the resolved texture (`fsr1`, `dlss`:
`Upscaler.output()`, a `Texture` over the GL texture, same screen coordinates on both sides) or, for `bicubic`
and before the first resolve, the offscreen texture's scaled region (`RenderScale.scaledRect`) stretched to the
screen rectangle, so the stock screen shader's bicubic filter is the upscaler.

### zombie.iso.weather.WeatherShader (added 2026-09-22, upscaling)

`startMainThread`: `texd.col2/col3` (the `TextureSize` uniform, the bicubic texel size) take the resolved
texture's size from `Upscaler.compositeTextureSize()` when the quad draws it; stock's offscreen texture size
otherwise.

## zombie.characters.IsoZombie (fifth edit, 2026-09-22, the flat draw of the horde's zombies)

New `pzoptRenderFlat(x, y, z, col)` (`zombieAtlasFast`), called by `FBORenderCell.renderMovingObject` in place of
`render` for an object of exactly this class: for the two cases that make up a horde — a culled zombie (no active
model, the atlas sprite) and a zombie whose model draw data the pre-pass built (`CharDraw.isPrepared`) — it
performs the observable steps of `IsoZombie.render` and `IsoGameCharacter.render` in their order: drop the
corpse-atlas texture, the alpha rule when the camera is not on the player, the doRender / alpha / seat / invisible /
alpha-zero returns, the depth-mask state, the static light scratch from the square, the default facing, the
last-rendered statics, `checkUpdateModelTextures`, the sprite scale, then either `renderTextureInsteadOfModel` or
the body of `IsoSprite.renderActiveModel` (the object / debug-option test, the profiler area, the lights call the
frame stamp answers, the camera record — the one the pre-pass built with the draw data, `CharDraw.takeCamera`, else
its own — and the model enqueue that takes the prepared data), then the item updaters
and the ragdoll / ballistics debug renders — and returns true; otherwise (fire sprites, a non-parts sprite, the
non-fbo path, a debug mode, a fake-dead zombie, a model without prepared data) it returns false having done nothing
and the stock virtual chain runs. Sits after `renderTextureInsteadOfModel`.

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-22, the flat draw and the prepared zombies' tests)

`renderMovingObject` is split: the three leading tests (not a player, a square, on screen) stay in it and the rest
moves to the new `pzoptRenderOnScreenObject(object, playerIndex)`, which the pre-pass's loop calls directly (its
list was built from the same three tests in this frame; nothing changes them during the render). In that rest: a
zombie whose draw data the pre-pass built (`CharDraw.isPrepared`) skips the square light-info and cutaway-visibility
tests (the pre-pass applied both on the game thread this frame); the square is read once into a local; before
`render`, an object of class `IsoZombie` is offered to `pzoptRenderFlat`; true = drawn, the method returns; false =
the stock `render` call as before.
