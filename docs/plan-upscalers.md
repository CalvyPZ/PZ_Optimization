# Plan: render-resolution upscaling (FSR 1.0 / DLSS / XeSS), 2026-09-22

The world is rendered at a fraction of the screen size and resolved to the screen by an upscaler;
the UI, text, cursor and the stock post-process ("weather" screen shader) stay at native resolution.
Every GPU-bound scene (fog 389 fps, storm 390, Louisville's composite, every laptop) gains roughly
the pixel ratio in the world pass. Keys default off: with `upscaler=off` nothing changes.

## The seam (how the game composes a frame)

- `Core.StartFrame(nPlayer)` binds the offscreen `MultiTextureFBO2` (one POT texture, 8192x4096 at
  5120x2160, RGBA8 + the pzopt D24S8 depth texture) and queues `glDoStartFrame(screenW, screenH,
  zoom, player)`; the render thread's `Core.DoStartFrameStuffInternal` sets the projection to
  `ortho(0, w*zoom, h*zoom)` (world pixels) and the viewport + scissor to the player's screen rect.
  Everything in the world pass is drawn in world pixels through that projection, so shrinking the
  viewport alone renders the same content into fewer pixels.
- `IsoWorld.render()` … `Core.EndFrame(nPlayer)` unbinds. `Core.RenderOffScreenBuffer()` →
  `MultiTextureFBO2.render()` draws the texture region (sx, sy, sw, sh) to the screen rect under
  `SceneShaderStore.weatherShader` (`media/shaders/screen.frag`: bicubic sample, colour grade,
  desaturation, night vision, search-mode circle via `gl_FragCoord`, drunk / blur, film grain).
- Every viewport restore inside the world pass goes through `glDoStartFrame` (TextureDraw case
  `glDoStartFrame` / `glDoStartFrameNoZoom`) or `IsoCamera.getScreen*` on the render thread
  (ModelOutlines, VisibilityPolygon2), or a game-thread `SpriteRenderer.glViewport` of the player
  rect (IsoWorld view cone). Shader passes that map `gl_FragCoord` through a screen size:
  `visibilityBlur` (screenSize / displayOrigin from IsoCamera on the render thread), `water` /
  `puddles` (`WViewport` = offscreen origin + `camera.offscreenWidth / zoom`), `fog` (`screenInfo`
  from the game thread), fire / smoke particles (viewport = offscreen size).

## Mechanism (`pzopt.RenderScale`, render thread only)

1. TextureDraw `glDoStartFrame` / `glDoStartFrameNoZoom` with a player index while the world FBO
   is bound: after the stock call, viewport and scissor become the scaled player rect
   `(sx*s, sy*s, sw*s, sh*s)`; the projection is untouched. The world FBO keeps its texture; the
   low-res image occupies the top-left of each player's region.
2. TextureDraw `glViewport`: a rect equal to a player screen rect or the full screen is scaled the
   same way (IsoWorld's view-cone restore); anything else passes through (FX mask, cone texture).
3. `IsoCamera.getScreenLeft/Top/Width/Height` and `getOffscreenLeft/Top` return the scaled values
   when called on the render thread inside the world pass (ModelOutlines, VisibilityPolygon2 and
   its uniforms). The game thread never sees a scaled screen: culling, chunk work, UI and mouse
   are untouched.
4. `WaterShader` / `PuddlesShader` `WViewport.zw`, the pzopt puddle shader, `FogShader` /
   `ImprovedFogDrawer` `screenInfo.xy`, `ModelManager.RenderParticles` viewport: scaled at the
   site. `FogPass` already derives its buffers from the current viewport.
5. `MultiTextureFBO2.render()`: with an upscaler active the frame goes through
   `pzopt.Upscaler` (a `GenericDrawer` queued before the quad): the low-res region is upscaled
   into a screen-sized texture (`pzopt.UpscaleTexture`, a `Texture` subclass over a raw GL id),
   which the stock weather quad then draws; `WeatherShader.startMainThread` reports that
   texture's size as `TextureSize` so the stock bicubic stays 1:1.
6. `SavefileThumbnail` frames are rendered unscaled (a suspend / resume marker pair queued
   around them).

## Modes

| `upscaler` | Resolve | Needs |
|---|---|---|
| `off` | stock | – |
| `bicubic` | the stock screen shader samples the low-res region directly (its bicubic filter is the upscaler) | nothing (first milestone, pipeline check) |
| `fsr1` | AMD FidelityFX Super Resolution 1.0: EASU + RCAS (`media/shaders/pzopt_fsr1_*.frag`, MIT) | GLSL 3.30 |
| `dlss` | NVIDIA DLSS Super Resolution through NGX on a Vulkan device sharing the images with GL (`natives/libpzopt_ngx64.so`, `GL_EXT_memory_object_fd` + `GL_EXT_semaphore_fd`); camera motion vectors + depth + sub-pixel jitter (float viewport offset) | NVIDIA RTX, driver NGX (`libnvidia-ngx.so.1`), the DLSS library next to the natives |
| `xess` | Intel XeSS through the same shim (Vulkan) | Windows only (no Linux runtime); untestable here |

`upscalerQuality`: `quality` 67 %, `balanced` 59 %, `performance` 50 %, `ultra` 33 %, `native` 100 %
(DLAA-style for dlss). `fsrSharpness` 0..1 (RCAS, default 0.2 = FSR "quality" default).

## Milestones

1. bicubic at 50 % on the bench route uncapped: fps up, screenshots sane, `ups-off` parity.
2. FSR 1.0: EASU + RCAS ported from `ffx_fsr1.h`; screenshots vs stock; laptop numbers.
3. DLSS: shim (Vulkan device matched by UUID, exported memory + semaphores, NGX init / create /
   evaluate), Java side imports the images, copies the low-res colour + depth into the shared
   images, writes camera motion vectors, jitters the viewport; then per-object motion vectors for
   characters and vehicles (rect + depth band).
4. XeSS backend in the shim behind `_WIN32`, cross-compiled with mingw when available, shipped as
   experimental.

Run labels `ups-*`; the parity watch (pz-optimization-63) compares each `ups-<mode>` recording
against the matching `ups-off` recording of the same route (overlay panels off).

## Log

- 2026-09-22 06:20 — milestone 1 and 2 on the 120 km/h drive (E:1200, kmh=193, uncapped, 5120x2160, one build,
  `--install opt`): `upscaler=off` 509.5 fps / p99 7.7 ms (`ups-off-p-3`), `bicubic` 50 % 676 fps / p99 6.2 ms
  (`ups-bicubic-p-3`), `fsr1` 50 % 619 fps / p99 6.4 ms (`ups-fsr1-p-3`; EASU + RCAS ≈ 0.14 ms a frame at 4K).
  The GPU stays ~95 % busy at 50 %: the chunk bakes and the composite are not screen-pixel work.
- 2026-09-22 06:23 — first DLSS run segfaulted inside `libnvidia-ngx.so` at
  `NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements`: NGX init needs more than the 1 MB stack of the game's
  render thread (reproduced with `ulimit -s 1024` on the standalone test; fine at 4 MB). The shim now runs every
  entry point on one worker thread with a 64 MB stack.
- 2026-09-22 07:20 — DLSS on the 120 km/h drive, same build (`ups-build6`): `ups-dlss-p-3` (camera motion vectors
  only) 231.9 fps / p99 12.0 ms; `ups-dlss-p-4` (per-object motion vectors: characters + vehicles through stencil ids)
  231.8 fps (the object pass is free); `ups-dlss-f-1` (`dlssPreset=f`, the older convolutional model) 377.2 fps.
  The transformer model (preset M at performance) costs ~2.5 ms a frame at 5120x2160 output on the 4090 (GPU
  100 %, 346 W vs 282 W with fsr1). Bench route S:450 turn=90 uncapped: control 592 fps, DLSS 50 % 281 fps.
  Parity watch: bicubic, fsr1 and both DLSS variants pass (the bench pair with object motion vectors: transients
  0.98x the control, no trail on the player or the zombies, Jev parity 0.94 — "the cleanest pair today").
- Decisions: `dlss` / `xess` that cannot run (no RTX, no shim, split screen, XeSS not built) continue as `fsr1` at
  the same scale (`RenderScale.fallback`); the Windows DLL of the shim needs MSVC (src/native/README.md).
- 2026-09-22 07:30 — bench route S:450 turn=90 uncapped, one build: off 592 fps / p99 6.8 ms; fsr1 50 % 802 / 5.5;
  DLSS 50 % 281 / 12.2; DLSS 67 % (quality) 301 / 11.0 (the DLSS cost is per output pixel, not per input pixel).
  Jitter-sign A/B (`dlssJitterSign=-1`, run `ups-bench-dlss-jneg-1`): the car lettering doubles and blurs, so the
  viewport-offset sign as reported (+1, memory-row convention) is the right one; motion-vector sign confirmed by the
  drive pairs (no trail).
- 2026-09-22 07:40 — parity watch closed the pass with every judged mode passing: fsr1 50 % on the bench route
  (transients 0.85x the control, Jev parity 0.82), DLSS 67 % (1.04x, parity 0.88), on top of the earlier bicubic /
  fsr1 / DLSS 50 % pairs. Judging note from the watch: an auto window that slides onto the quit-to-black reads as
  black tiles + a hue shift; `parity-judge.py` / `colorshift.py` take `--window-a` / `--window-b` to pin a side —
  check the printed window against the route before trusting a black_tiles verdict.
