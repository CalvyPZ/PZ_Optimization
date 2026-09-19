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
   floor (Diego's screenshot, walking, not only at speed). The per-frame
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
   pzopt's own (`pzoptExactOccluderMask`, a `HashMap<ChunkLevelData, Long>`,
   computed after the stock call with the stock test but without the
   on-screen clip). The stock `occludingSquares` mask is built with an `int`
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
  `pzopt.BootAsync.startFmod` (a thread) when `fmodAsync` is on; the four
  `SoundManager.instance.set*Volume` calls right after the render-thread wait
  are wrapped in `pzopt.BootAsync.afterFmod(...)`, which runs them at once when
  the init is synchronous and otherwise after the join. Why: the FMOD system
  create and the twelve bank files are 1.4 s of native work that nothing needs
  before the sound scripts, and the VCAs the volume setters read live in the
  banks.
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

## zombie.core.skinnedmodel.model.Model (added 2026-09-19, night, game load; GitHub issue #1)

`CreateShader(name)`: the stock method always posts a lambda to the render
thread and waits for it, even when `ShaderManager` already holds the shader.
Every `Model` constructor calls it, and the render thread only drains that
queue once per render step, so each model built off the render thread costs
one loading-screen frame. On the desktop that is ~1 ms; on the laptop
`diego-flip` (issue #1) the loading-screen step is ~220 ms and the 73 animal
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
