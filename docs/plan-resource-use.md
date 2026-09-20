# Plan: use the whole GPU and CPU at 5120x2160 (2026-09-19, afternoon)

Question from the maintainer after the frame cap was removed: the game still does not
use all of the GPU and CPU at 5120x2160. This document is the sweep of the
game's frame pipeline that answers where the idle time goes, the measurements
that back it, and the plan that follows. Every number points at a run
directory under `harness/runs/`; every run was recorded (`recording.mp4`).

## 1. What the sweep covered

Read end to end, with the blocking points marked: `GameWindow` (main loop,
`frameStep`, `logic`, `renderInternal`), `MainThread`, `RenderThread`
(`renderLoop`, `lockStepRenderStep`, the invoke queue), `SpriteRenderer` and
`SpriteRendererStates` (the frame hand-off), the lwjglx `Display` shim (swap,
event pump, `Sync`), `LightingThread` / `LightingJNI`, `WorldStreamer`,
`PathfindNativeThread`, `MapCollisionData`, `PZForkJoinPool` and the five
places the game already runs work in parallel (`IsoWorld.updateThread`, items,
animation post-update, `checkLights`, `recalculateGridStacks`), the sprite
`RingBuffer` and the persistent-VBO override, `MultiTextureFBO2` (the zoom
offscreen buffer), the UI FBO path in `Core.EndFrameUI`, and every
`Thread.sleep` / `wait` / `park` / `glFinish` / `glClientWaitSync` /
`invokeOnRenderContext` site in the decompiled tree (grep over all 8,000
files; the per-frame ones are listed in section 3).

### The frame pipeline, as built

Two threads own a frame. The game thread (`MainThread`) runs `logic()` then
`renderInternal()`, which does not draw: it records sprite commands into a
`SpriteRenderState`. `RenderThread.Ready` → `SpriteRenderer.pushFrameDown`
moves that state into the single **ready** slot; if the slot is still full
(the GL thread has not picked up the previous frame) the game thread waits
(`waitForReadySlotToOpen`). The GL thread (`main` on Linux) takes the ready
state (`acquireStateForRendering`), replays it (`postRender`: builds the
sprite ring buffer, issues the GL calls) and calls `Display.update` (swap +
event pump). So the game thread can run one frame ahead of the GL thread, and
frame time = max(game thread CPU, GL thread wall time). The GL thread's wall
time includes whatever the driver does inside the swap.

## 2. Measurements (all: 5120x2160, max zoom, Config defaults, uncapped, no PZDashboard, recorded)

New tooling for this: `--jfr-setting` in `harness/run.sh` (lowers the JFR
wait thresholds), `tools/JfrSamples.java` now dumps `jdk.JavaMonitorWait` /
`ThreadPark` / `JavaMonitorEnter` events, and `harness/waits.py` sums, per
thread and blocking site, how long each thread was blocked inside the route
window. Native samples at 1 ms show the GL calls the driver blocks in.

| run | renderer / display | route | fps mean | frame mean / p99 | GPU busy | game thread busy | GL thread busy | game thread blocked in the ready-slot wait |
|---|---|---|---|---|---|---|---|---|
| `uncap-wl-zink-opt120-1` (the maintainer's run) | Zink, Wayland | 122 km/h, complete | 381 | 2.6 / 6.5 ms | 60 % | 59 % | 30 % | (no JFR) |
| `waits-uncap-1` | Zink, Wayland | 122 km/h, complete | 378 | 2.6 / 6.4 ms | 60 % | 60 % | 30 % | **39 %** (1.0 ms per frame) |
| `waits-uncap-zinkx11-1` | Zink, XWayland | 122 km/h, off road at 380 tiles | 293 moving / 195 parked | 3.4 / 7.2 ms | 51 % | 68 % | – | – |
| `waits-uncap-gl-1` | **NVIDIA GL**, XWayland | 122 km/h, off road at 380 tiles | **629 moving** / 718 parked | 1.6 / 5.7 ms | **93 %** | 85 % | 59 % | 13 % (0.2 ms per frame) |
| `base60-gl-1` | **NVIDIA GL**, XWayland | 60 km/h, complete | **570** | 1.8 / 4.4 ms | **98 %** | 69 % | 43 % | – |
| `uifbo60-gl-1` | NVIDIA GL + `uiRenderOffscreen=true` | 60 km/h, complete | 567 | 1.8 / 4.6 ms | 98 % | **57 %** | 42 % | – |

Where the GL thread's time goes (native samples inside the route window):

| run | in `glfwSwapBuffers` | in the persistent-VBO fence (`glClientWaitSync`) | in `glGetInteger` (NVIDIA's queue-full block) | GL thread CPU |
|---|---|---|---|---|
| Zink / Wayland (`waits-uncap-1`) | **64 %** of native samples (~1.8 ms per frame) | 14 % | – | 0.8 ms per frame |
| NVIDIA GL (`waits-uncap-gl-1`) | 21 % | 0.6 % | 76 % | 0.9 ms per frame |

## 3. Findings

1. **The idle GPU is the Zink present path, not the game's threading.** On
   Zink the GL thread spends ~1.8 ms of every 2.6 ms frame blocked inside
   `glfwSwapBuffers` (Mesa's kopper / the NVIDIA Vulkan WSI; the Wayland
   surface offers FIFO, IMMEDIATE and FIFO_LATEST_READY, no MAILBOX). While
   it is blocked no new GL work is submitted, so the GPU idles ~40 %, and the
   game thread, one frame ahead, waits 1.0 ms per frame for the ready slot.
   On XWayland Zink is throttled to the 240 Hz refresh outright. The same
   build on NVIDIA's own GL runs the 60 km/h route at 570 fps with the GPU
   at 98 %: the GPU is the limit, as it should be at this resolution. The
   game cannot fix a driver's swap behaviour from Java; the fix is the
   renderer choice (NVIDIA GL for uncapped play; Zink stays the option it was).
2. **On NVIDIA GL the machine is GPU-bound at max zoom**, and the GPU cost is
   fill: the zoom-out is rendered at 2.5× the screen (`MultiTextureFBO2`:
   5120×2160 × zoom 2.5 × tileScale 2 / 2 = 12800×5400, 69 Mpixel per frame,
   allocated as a 16384×8192 texture) and downscaled once. That is the
   game's zoom design and the only lever that would raise fps further on
   this machine; rendering the zoomed-out view at screen size would cut GPU
   fill 6× but changes the look (the 2.5× buffer is a supersample). Not
   planned unless the maintainer wants that trade.
3. **The CPU is idle because the GPU is the limit.** With NVIDIA GL the game
   thread is 57–69 % busy and 13 of 16 threads idle; that is the correct
   state for a GPU-bound frame, not a stall. The one thing that makes the
   game thread use less CPU per frame without changing anything visible is
   the stock **offscreen UI** option (`uiRenderOffscreen=true` in options.ini,
   "Render UI offscreen" in Display options): the Lua UI (25–34 % of
   game-thread CPU, `KahluaTableImpl.rawget`, `luaMainloop`) then renders at
   `uiRenderFPS` (120 in the maintainer's options) into an FBO instead of 570 times a
   second. Game thread 69 → 57 % busy; frames from both recordings at the
   same route time are identical (HUD, inventory bar, clock, world).
4. **Per-frame blocking calls that are fine.** `invokeOnRenderContext` from
   the game thread is 0.0–0.2 % of the window (66–113 calls in a route:
   texture creation on first sight, shader compile, FBO create). The
   `LightingThread` sleeps by design (`lightFPS=15` in options.ini,
   `Display.sync`). The world streamer, pathfinding and collision threads
   sleep between jobs. No `glFinish` anywhere; `glGetTexImage` only in
   puddles/screenshots.
5. **The persistent-VBO fence wait is a Zink-only cost** (14 % of the GL
   thread's native samples on Zink, 0.6 % on NVIDIA GL; the ring rotates
   across frames, 128 × 64 KB, so a wait means the GPU is >128 batches
   behind, which on Zink is the swap stall again).
6. **122 km/h routes are not a valid A/B fixture.** Two of three runs today
   left the road at ~380 tiles (steering oscillation, known); use the 60 km/h
   `E:1200` route for comparisons (completes in 73 s every time today).

## 4. Plan

Ordered by what moves the objective ("CPU and GPU maxed unless pegged at
240 fps"), each with the gate that decides it.

1. **Renderer for uncapped play: NVIDIA GL** (done, measured). Showcase and
   A/B runs default to `--renderer nvidia` (already the harness default). The
   Zink swap stall is documented here; if the maintainer wants Zink, the next
   experiment is Mesa's kopper behaviour on the NVIDIA Vulkan WSI
   (`MESA_VK_WSI_PRESENT_MODE` does not apply to a non-Mesa Vulkan driver), a
   driver question, not a game one.
2. **Offscreen UI on** (done, measured, verified visually): recommend
   `uiRenderOffscreen=true` in options.ini. Stock option, one line, reversible.
3. **Game-thread scalability (code, for when the GPU is not the limit: lower
   zoom, smaller screen, a faster card).** Measured shares of game-thread CPU
   on the Zink run, which is CPU-visible: `IsoCell.render` 58 % (cutaways
   `cutawayVisit`/`getDataForLevel` ~3 %, `FBORenderCell.renderOneLevel` /
   `calculateObjectRenderInfo` / `checkTreeTranslucency` ~4 %, `LightingJNI
   lightInfo` + `cacheLightInfo` 4 %, `IsoCell.getGridSquare` 3 %),
   `VisibilityPolygon2` (the view-cone stencil) ~7 %, Lua UI 34 % (item 2).
   In order:
   a. `VisibilityPolygon2.Drawer.calculateVisibilityPolygon` off the game
      thread: it is called from `FBORenderCell.renderTilesInternal` via
      `renderMain`, reads player position, look angle and the chunks'
      `vispolyData` walls (which only change in `logic()`), and produces a
      `PolygonData` the GL thread transforms. Start it on `PZForkJoinPool`
      right after `logic()` and join in `renderMain`. Gate: parity of the
      polygon vertex list, then `compare.py` on a zoom-1.0 route (CPU-bound
      there, see `direct-launcher-and-zoom-flag`).
   b. Translucent per-frame list cache (`translucentCache`, plan
      `docs/plan-driving-frame-time.md` 3.1) and cutaway skip while driving
      outdoors (3.4): same gate.
   c. Sprite ring depth as a setting (`ringBuffers`, override of
      `SpriteRenderer$RingBuffer`): only if Zink is kept.
4. **GPU cost per frame (the only lever left on this machine)**: optional,
   the maintainer's call because it changes the picture. A `zoomRenderScale` that
   renders the zoomed-out view at screen size (or 1.5×) instead of 2.5×.
   Gate: side-by-side stills at max zoom.

## 5. Verification videos

`harness/runs/<run>/recording.mp4` for every run in the tables above; the
frames compared for item 2 were taken 45 s into `base60-gl-1` and
`uifbo60-gl-1`.
