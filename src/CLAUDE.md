# src/

Three Java source roots, compiled together by `scripts/build.sh`, plus `src/lua/`:

- `src/overrides/` (committed since 2026-09-19): Vineflower decompiles of the game classes we
  shadow, with our edits applied. Shadowed list is `OVERRIDES` in `scripts/build.sh`:
  IsoChunk, WorldStreamer, ChunkSaveWorker, GLVertexBufferObject, FBORenderCell, GameWindow,
  TISLogoState, org.lwjglx Display and Mouse, FileSystemImpl, TileDepthTextures,
  TextureIDAssetManager, MapCollisionData, IsoMetaGrid, and since the boot/load work
  (2026-09-19 evening, `docs/plan-instant-load.md`): ScriptParser, IsoMetaCell,
  BuildingRoomsEditor, GameLoadingState, se.krka LuaCompiler, AnimationSet,
  AnimationAssetManager, TexturePackDevice, scripting.objects.Item, and PerformanceSettings
  (frame limiter, 2026-09-19). Inner classes are shadowed too.
  **Every edit is described in prose in `docs/override-edits.md` and marked `// pzopt:` in the
  source.** After a game update, `scripts/regen-overrides.sh` decompiles the new jar so the
  edits can be re-applied on top.
- `src/lua/`: loose game-dir Lua (`client/pzopt/*.lua`); build.sh copies it under
  `build/classes/media/lua/` so `pzopt.sh` installs it into the game dir's `media/lua/` with the
  classes. No mod to enable. Currently the "Menu framerate" Display-options combo.
- `src/shims/`: small replacement classes (e.g. the TISLogoState shim that skips the boot
  splash screens).
- `src/pzopt/pzopt/`: our own package, committed.

## pzopt package

| Class | Role |
|---|---|
| `FrameCap` | frame limiter: enables the stock "Uncapped" combo entry, snapshots the saved frameRate/uncappedFPS before `Core.loadOptions` clamps and re-saves them, persists caps above 244 and the forced-run restore marker, and holds the separate menu cap (`~/Zomboid/pzopt/framecap.ini`) the main loop reads via `uncappedNow()`/`lockNow()`; the "Menu framerate" combo is `src/lua/client/pzopt/pzopt_framecap_options.lua`, shipped loose into the game dir |
| `Config` | runtime keys read from `pzopt.properties` in the game dir; DEFAULTS are the adopted optimizations (parallel, workers, wake, persistentVbo, trees/windows/translucentTiles InChunkTexture, bakeBudget 8, lightingBudget 8, hotsaveIntervalSec 30, cutawayFast, fileThreads, parallelDepthMaps, loaderCpuFixes, loadWorkers). `instrument=false` by default: harness runs pass `--prop instrument=true`. `uncappedFps=auto` honours the in-game frame-rate option; `true`/`false` force it per run. |
| `RecalcPool` / `OrderedPublisher` / `StreamerWake` | parallel chunk recalc workers, ordered publication, waking the streamer |
| `Harness` / `HarnessFlags` / `AutoStart` | in-game harness: reads the flag file, auto-continues into the bench save, presses click-to-start (bench/parity/drive only, never verify), forces zoom, drives the route (`roadFollow`), writes `pzopt-schedule.out`, `pzopt-bench.out` |
| `Stats` | `pzopt-frames.out` / `pzopt-chunks.out` samplers |
| `Parity` | per-square recalc capture for the parity gate |
| `LoadTrace` | stamps console lines with epoch ms into `pzopt-loadtrace.out` |
| `BootAsync` / `BootPump` / `LuaPrecompiler` / `AnimClipCache` + `CachedAnimationTask` / `PackIndex` / `ScriptText` / `LotHeaders` / `FileTaskStats` / `ScriptDump` | boot and load work (2026-09-19 evening): FMOD init and animation-set parse on boot threads, file-pool pump during init, parallel Lua precompile cache, animation clip and texture-pack index caches under `~/Zomboid/pzopt/`, linear script text passes, per-cell lot-header memo, file-task timing, item field dump for equivalence checks |
| `Overrides` / `Guard` / `BuildInfo` / `Log` | install checks, build stamp, logging |

Design notes carried from memory:
- The Display override hands `glfwSwapBuffers` to a preloaded `libMangoHud_opengl.so`
  `eglSwapBuffers` via an FFM downcall so the HUD/CSV works on native Wayland with NVIDIA GL.
  `zomboid.wayland=1` selects the GLFW Wayland platform (pass via `JAVA_TOOL_OPTIONS`).
- The GameWindow override removes the frame-rate cap and the boot logos.
- Slow driving frames are the per-frame translucent/tree pass in `FBORenderCell` (JFR: 75 % in
  `IsoCell.render`), which is why trees/windows/translucent tiles are baked into chunk
  textures. Streamer hand-off is 3.4 % of slow frames; leave it alone.
- Zoom 2.5 at 5120x2160 renders a 12800x5400 zoom-out buffer; that fill is the GPU cost.

Java tooling: no jdtls; use `codegraph_explore` for symbols, `decompiled/` for game code,
`scripts/test.sh` for JVM-only tests, and a real harness run for anything touching rendering.
