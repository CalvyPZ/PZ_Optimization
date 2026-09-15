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
