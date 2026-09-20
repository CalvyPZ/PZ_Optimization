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
