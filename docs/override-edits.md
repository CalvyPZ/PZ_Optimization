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
   samples at max zoom (`attr-jfr-z25`).

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
   when a tree changes state. Measured on the max-zoom teleport route
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
   `MinusFloorSE` choice in `calculateObjectRenderLayer`).
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
   the decision through the set). A deferred level whose texture was baked
   before (`DIRTY_CREATE`, 512, no longer set) takes the existing "clean"
   path — the manager's current chunk is pointed at its texture,
   `endRenderChunkLevel(..., false)` queues it for drawing and the cached
   translucent lists are re-registered; a never-baked level returns without
   drawing. Deferred levels are retried next frame in chunk order.
7. **Lighting budget** (`pzopt.Config.LIGHTING_BUDGET`, 0 = stock). In
   `updateChunkLighting` the pass also runs when a previous pass stopped early
   (`pzoptLightingPending`), not only when the lighting counter changed; with
   a budget the loop returns after that many chunks were refreshed and marks
   the pass pending (the stock debug-only `Lighting.SplitUpdate` branch with
   its fixed 5 is kept for when the budget is 0).
8. **Occluder-mask replay** (`pzopt.Config.CUTAWAY_FAST`). In
   `calculateOccludingSquares(int)`, a chunk level that is not dirty and whose
   mask was computed before (tracked in a bounded `HashSet<ChunkLevelData>`)
   has its stored `occludingSquares[playerIndex]` bitmask replayed into the
   occluded grid (bit → square x/y, level z, same window test and `max`
   update as the stock per-square loop); dirty or never-computed levels run
   the stock `ChunkLevelData.calculateOccludingSquares`. Every input of that
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

One edit, in the boot sequence (`init`, between `Translator.loadFiles()` and
`LuaManager.init()`): the call to `doEpilepsyWarningText()` is wrapped in
`if (!pzopt.Overrides.enabled())`, so the photosensitivity warning frame is
not drawn at start-up while the build guard is active. The method itself is
unchanged. The class is otherwise verbatim Vineflower output (revision
`b0bbce05d5`), which recompiles without fixes. Together with the committed
`src/shims/zombie/gameStates/TISLogoState.java` (logo screens skipped), this
is what gets a run from launch to the main menu with no splash screens.

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

Both classes are otherwise verbatim Vineflower output (revision `b0bbce05d5`)
and recompile without fixes; `Display`'s inner classes `$Window` and
`$Callbacks` come along as loose classes. Verified 2026-09-19 with
`harness/run.sh ... --env JAVA_TOOL_OPTIONS=-Dzomboid.wayland=1`: the console
logs "Display mode changed to 5120x2160", the recording is full-screen and the
route numbers match the XWayland runs.
