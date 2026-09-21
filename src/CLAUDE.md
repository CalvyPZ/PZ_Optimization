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
  core.opengl.RenderThread (performance-overlay hooks, 2026-09-20), and the thunderstorm pass
  (2026-09-20 evening): core.opengl.VBORenderer (batch buffer size, single-advance quad) and
  iso.IsoPuddles (pack / append / draw pieces for the puddle cache), iso.weather.fx.ParticleRectangle and
  WeatherParticleDrawer (rain tiles: template once, one draw per screen cell), and iso.LightingJNI
  (harness `see_all` view for the Louisville preset, 2026-09-20 night), and iso.weather.fog.ImprovedFog + ImprovedFogDrawer and core.textures.MultiTextureFBO2 (one-pass fog, the offscreen depth as a texture, 2026-09-21). Inner classes
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
| `Scene` | scene flags for a run (`time_of_day`, `weather=storm|clear`, `fog=heavy|off|0..1`, `torch=on|off`, `thunder_secs`; `population=N|max`, `see_all=true`; the run.sh presets `night-torch` / `night-dark` / `storm` / `fog` / `storm-fog` / `louisville`, 2026-09-20): forces the game hour at world-ready, pushes the sandbox zombie population multipliers to the native popman before the chunks load, flips the LightingJNI see-all view, stops the save's weather period and pins the STAGE_STORM climate overrides every frame with a scheduled lightning strike near the player, pins the fog intensity (plus the storm fog tint on a storm), equips a lit Base.HandTorch; summary lines in `pzopt-bench.out` |
| `Stats` | `pzopt-frames.out` / `pzopt-chunks.out` samplers |
| `Overlay` | in-game performance overlay and frame log, the platform-independent replacement for MangoHud/RivaTuner (2026-09-20): presented-frame times from `RenderThread` after the swap, GPU busy from a `GL_TIME_ELAPSED` query around `SpriteRenderer.postRender`, game/render thread and process CPU from JMX, a 5 s window of fps / p99 / p99.9 / max / 1%-low / jitter / spikes, a verdict line (at cap, X bound, or nothing saturated), a frame graph; drawn from `Display.imguiEndFrame` (the last game-thread draw before `Core.EndFrameUI` hands the frame over). Keys `overlaySampling` (2026-09-21: measure at all — frame ring, GL timer queries, sampler thread; **off by default**, so F9 only shows a notice pointing at the tick box and the restart; `overlay`, `overlayLog` and harness runs imply it), `overlay` (show from boot), `overlayLog` (`Zomboid/pzopt-overlay.out`, MangoHud column names + `epoch_ms`; always on in harness runs), `overlayKey`, `overlayFont`, `overlayCorner`. The fps number is coloured against the cap (`overlayFpsColor`, `overlayFpsFollowCap`, `overlayFpsCap{Blue,Green,Yellow}Pct` = 98/90/50 % of the cap, `overlayFps{Blue,Green,Yellow}Above` = 300/150/100 fps when uncapped or follow-cap is off, `overlayFpsColor{Blue,Green,Yellow,Red}` names or RRGGBB), all on the Optimizations tab. Toggle with the "Toggle performance overlay" binding (F9). `analyze.py` reports it as `overlay:` next to `mangohud:`. The utilization (thread CPU times, JMX process / machine load, GPU busy share) is sampled every 500 ms by the daemon thread `pzopt-overlay-util`, never on the game thread: on Windows the JMX load calls are PDH queries that enumerate every process (5-50 ms on old PCs; the 2026-09-21 "micro stutter every 0.5 s" reports) |
| `Parity` | per-square recalc capture for the parity gate |
| `LoadTrace` | stamps console lines with epoch ms into `pzopt-loadtrace.out` |
| `BootAsync` / `BootPump` / `LuaPrecompiler` / `AnimClipCache` + `CachedAnimationTask` / `PackIndex` / `ScriptText` / `LotHeaders` / `FileTaskStats` / `ScriptDump` | boot and load work (2026-09-19 evening): FMOD init and animation-set parse on boot threads, file-pool pump during init, parallel Lua precompile cache, animation clip and texture-pack index caches under `~/Zomboid/pzopt/`, linear script text passes, per-cell lot-header memo, file-task timing, item field dump for equivalence checks |
| `ModelShaders` | shaders already created by a `Model`, so `Model.CreateShader` skips the blocking render-thread round trip for repeat shader names (`shaderCache`; issue #1: 73 animal models were 16.5 s of a laptop load); summary logged at load start and world ready |
| `MipMaps` | row-based texture mipmap generation and alpha premultiply on `byte[]` copies (`mipmapArrays`; issue #2: the stock per-byte direct-buffer loop was C2-miscompiled into a SIGSEGV on a file-pool thread); byte-identical to stock, `tests/pzopt/MipMapsTest` |
| `PuddleCache` | packed puddle vertices per chunk level kept on `IsoChunk.pzoptPuddles`; per frame copies the block into IsoPuddles' RenderData and patches the vertex lights, camera jiggle and depth delta; rebuilt on bake, cutaway change or every `puddleCacheFrames` (`puddleCache`; storm route puddles 4.5 → 0.96 ms, 2026-09-20 evening) |
| `RainTiles` | weather particle tiles (`rainTiles`): the game thread renders a `ParticleRectangle`'s particles once at the origin plus the screen-cell origins; the render thread packs and uploads that template once and draws it once per cell with a translated ModelViewProjection through VBORenderer's PositionColorUV shader (2026-09-20 night) |
| `TreeBake` | the tree pass of the chunk-texture bake (`treeBakePass`, issue #5): geometry helpers (a tree sprite's rectangle in a chunk texture's space, the neighbour textures' rectangles, `needsCopy`, the depth tilt of one level's depth per level of height) and the pooled `Drawer` that draws the quads on the render thread through VBORenderer's position/colour/uv/depth format under GL_LEQUAL; `FBORenderCell.pzoptBakeTrees` fills it after the top level of every texture, with the neighbours' trees that reach beyond their own texture; `tests/pzopt/TreeBakeTest` |
| `FogPass` | heavy fog (`ImprovedFog`) in one pass (`fogPass`, `fogScalePct`, 2026-09-21): the render thread draws all fog rectangles of the frame in one draw call into a fog buffer of `fogScalePct` % of the viewport, depth-tested against the scene depth (read in place: the offscreen buffer's depth is a texture since the `MultiTextureFBO2` edit; below 100 % reduced per block to its nearest value), noise sampled through a mipmapped sampler, and composites it once (premultiplied; depth-aware at edges so thin objects keep their fog); the game thread skips the per-square walk that only fed the row iterator and builds the rows from per-chunk fog masks (`fogMaskFrames`). Stock shaded every pixel up to twelve times with one draw call per row segment. Falls back to the stock drawer if a depth copy or shader is refused. `docs/findings-fog-2026-09-21.md` |
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
