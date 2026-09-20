# src/

Three Java source roots, compiled together by `scripts/build.sh`, plus `src/lua/`:

- `src/overrides/` (committed since 2026-09-19): Vineflower decompiles of the game classes we
  shadow, with our edits applied. Shadowed list is `OVERRIDES` in `scripts/build.sh`:
  IsoChunk, WorldStreamer, ChunkSaveWorker, GLVertexBufferObject, FBORenderCell, GameWindow,
  TISLogoState, org.lwjglx Display and Mouse, FileSystemImpl, TileDepthTextures,
  TextureIDAssetManager, MapCollisionData, IsoMetaGrid, and since the boot/load work
  (2026-09-19 evening, `docs/plan-instant-load.md`): ScriptParser, IsoMetaCell,
  BuildingRoomsEditor, GameLoadingState, se.krka LuaCompiler, AnimationSet,
  AnimationAssetManager, TexturePackDevice, scripting.objects.Item, PerformanceSettings
  (frame limiter, 2026-09-19), and skinnedmodel.model.Model (shader cache, issue #1, 2026-09-19
  night), and core.textures.ImageData (byte[] mipmap loops, issue #2, 2026-09-20), and the game-thread
  trims of 2026-09-20: iso.weather.fx.WeatherFxMask (mask scan gate), iso.objects.IsoLightSwitch
  (electricity check cache), se.krka KahluaTableImpl (single-lookup rawget), and
  core.opengl.RenderThread (performance-overlay hooks, 2026-09-20). Inner classes
  are shadowed too.
  **Every edit is described in prose in `docs/override-edits.md` and marked `// pzopt:` in the
  source.** After a game update, `scripts/regen-overrides.sh` decompiles the new jar so the
  edits can be re-applied on top.
- `src/lua/`: loose game-dir Lua (`client/pzopt/*.lua`, `shared/pzopt/*.lua`); build.sh copies it under
  `build/classes/media/lua/` so `pzopt.sh` installs it into the game dir's `media/lua/` with the
  classes. No mod to enable. Currently the "Menu framerate" Display-options combo
  (`pzopt_framecap_options.lua`) and the "Optimizations" options tab
  (`pzopt_optimizations_options.lua`: every Config key as a tick box or combo in nine titled
  categories (chunk textures ×2, cutaways/lighting/weather, sprite buffers, chunk streaming, boot ×2,
  world load ×2; 2026-09-20), saved to
  `~/Zomboid/pzopt/options.ini`, applied on the next launch), and the "Toggle performance
  overlay" key binding (`shared/pzopt/pzopt_keybinding.lua`, default F9, listed after "Display FPS").
- `src/shims/`: small replacement classes (e.g. the TISLogoState shim that skips the boot
  splash screens).
- `src/pzopt/pzopt/`: our own package, committed.

## pzopt package

| Class | Role |
|---|---|
| `FrameCap` | frame limiter: enables the stock "Uncapped" combo entry, snapshots the saved frameRate/uncappedFPS before `Core.loadOptions` clamps and re-saves them, persists caps above 244 and the forced-run restore marker, and holds the separate menu cap (`~/Zomboid/pzopt/framecap.ini`) the main loop reads via `uncappedNow()`/`lockNow()`; the "Menu framerate" combo is `src/lua/client/pzopt/pzopt_framecap_options.lua`, shipped loose into the game dir |
| `Config` | runtime keys read from `pzopt.properties` in the game dir; DEFAULTS are the adopted optimizations (parallel, workers, wake, persistentVbo, trees/windows/translucentTiles InChunkTexture, bakeBudget 8, lightingBudget 8, hotsaveIntervalSec 30, cutawayFast, fileThreads, parallelDepthMaps, loaderCpuFixes, loadWorkers). `instrument=false` by default: harness runs pass `--prop instrument=true`. `uncappedFps=auto` honours the in-game frame-rate option; `true`/`false` force it per run. Lookup order: `-Dpzopt.<key>` > `pzopt.properties` > `~/Zomboid/pzopt/options.ini` (the Optimizations tab, `UserOptions`) > default; `value/defaultValue/pinnedBy(key)` serve the tab. |
| `UserOptions` | the Optimizations tab's file (`~/Zomboid/pzopt/options.ini`): read once for Config, rewritten on every Apply; "Default" removes the key |
| `RecalcPool` / `OrderedPublisher` / `StreamerWake` | parallel chunk recalc workers, ordered publication, waking the streamer |
| `Harness` / `HarnessFlags` / `AutoStart` | in-game harness: reads the flag file, auto-continues into the bench save, presses click-to-start (bench/parity/drive only, never verify), forces zoom, drives the route (`roadFollow`), writes `pzopt-schedule.out`, `pzopt-bench.out` |
| `Scene` | scene flags for a run (`time_of_day`, `weather=storm|clear`, `torch=on|off`, `thunder_secs`; the run.sh presets `night-torch` / `night-dark` / `storm`, 2026-09-20): forces the game hour at world-ready, stops the save's weather period and pins the STAGE_STORM climate overrides every frame with a scheduled lightning strike near the player, equips a lit Base.HandTorch; summary lines in `pzopt-bench.out` |
| `Stats` | `pzopt-frames.out` / `pzopt-chunks.out` samplers |
| `Overlay` | in-game performance overlay and frame log, the platform-independent replacement for MangoHud/RivaTuner (2026-09-20): presented-frame times from `RenderThread` after the swap, GPU busy from a `GL_TIME_ELAPSED` query around `SpriteRenderer.postRender`, game/render thread and process CPU from JMX, a 5 s window of fps / p99 / p99.9 / max / 1%-low / jitter / spikes, a verdict line (at cap, X bound, or nothing saturated), a frame graph; drawn from `Display.imguiEndFrame` (the last game-thread draw before `Core.EndFrameUI` hands the frame over). Keys `overlay` (show from boot), `overlayLog` (`Zomboid/pzopt-overlay.out`, MangoHud column names + `epoch_ms`; always on in harness runs), `overlayKey`, `overlayFont`, `overlayCorner`. The fps number is coloured against the cap (`overlayFpsColor`, `overlayFpsFollowCap`, `overlayFpsCap{Blue,Green,Yellow}Pct` = 98/90/50 % of the cap, `overlayFps{Blue,Green,Yellow}Above` = 300/150/100 fps when uncapped or follow-cap is off, `overlayFpsColor{Blue,Green,Yellow,Red}` names or RRGGBB), all on the Optimizations tab. Toggle with the "Toggle performance overlay" binding (F9). `analyze.py` reports it as `overlay:` next to `mangohud:` |
| `Parity` | per-square recalc capture for the parity gate |
| `LoadTrace` | stamps console lines with epoch ms into `pzopt-loadtrace.out` |
| `BootAsync` / `BootPump` / `LuaPrecompiler` / `AnimClipCache` + `CachedAnimationTask` / `PackIndex` / `ScriptText` / `LotHeaders` / `FileTaskStats` / `ScriptDump` | boot and load work (2026-09-19 evening): FMOD init and animation-set parse on boot threads, file-pool pump during init, parallel Lua precompile cache, animation clip and texture-pack index caches under `~/Zomboid/pzopt/`, linear script text passes, per-cell lot-header memo, file-task timing, item field dump for equivalence checks |
| `ModelShaders` | shaders already created by a `Model`, so `Model.CreateShader` skips the blocking render-thread round trip for repeat shader names (`shaderCache`; issue #1: 73 animal models were 16.5 s of a laptop load); summary logged at load start and world ready |
| `MipMaps` | row-based texture mipmap generation and alpha premultiply on `byte[]` copies (`mipmapArrays`; issue #2: the stock per-byte direct-buffer loop was C2-miscompiled into a SIGSEGV on a file-pool thread); byte-identical to stock, `tests/pzopt/MipMapsTest` |
| `GpuSections` | GPU time per named frame section from `GL_TIMESTAMP` queries riding the sprite stream (`gpuSections=true`, measurement only; printed in the periodic FBORenderCell log line) |
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
