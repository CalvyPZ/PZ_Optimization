# Result: parallel-chunk-grid-recalc

Measured 2026-09-15 on the fixed route (`harness/baseline/comparison-2026-09-15.txt`
is the full table; per-run summaries in `harness/baseline/bench-*.json`; every
figure below comes from the harness, not from observation). Machine: Ryzen 7
9800X3D, RTX 4090, NVMe; game 42.20.4 `b0bbce05d5` under Proton.

## What changed for the player

Chunk latency — the time from the game asking for a chunk to the chunk being
ready for the game thread — on the 100 s car-speed route:

| variant | p50 | p90 | p99 |
|---|---|---|---|
| stock (two runs) | 166 ms | 310 ms | 1024 ms |
| wake-on-enqueue only | 20 ms | 184 ms | 811 ms |
| pool only, W=4 (no wake) | 174 ms (noise) | 293 ms (noise) | 707 ms |
| wake + pool W=2 | 9.7 ms | 103 ms | 556 ms |
| wake + pool W=4 (shipped default) | 9.3 ms | 84 ms | 517 ms |
| wake + pool W=8 | 9.1 ms | 87 ms | 503 ms |
| kill switch (`parallel=false wake=false`) | 175 ms | 337 ms | 923 ms |

Frame time (mean, p99, p99.9; in-game sampler cross-checked against MangoHud)
is within run-to-run noise for every variant, including W=8 — no
render-thread starvation, and no frame-time *improvement* either: the
stutter this change set out to address is not caused by the streamer on this
machine. Chunks per second is unchanged (the route sets it).

## Honest reading

- The proposal's premise was half right. The recalc pass is the larger part
  of the streamer's work (71 %), but the streamer is idle >90 % of the time
  at car speed. What players see as chunk latency was ~90 % the loop's fixed
  140 ms sleeps. Waking the streamer on enqueue (design Decision 8, added
  during implementation) is worth 8× at the median on its own; the pool on
  top brings it to 18×, mostly at p90/p99 where bursts of a full chunk row
  are being processed.
- The pool alone, as originally proposed, would have been a "within noise"
  result at the median. It is kept because with the wake in place it is a
  further 2× at the median and 2.2× at p90, at a cost of ~0.4 ms more CPU per
  chunk (workers contend for cache) — 4 workers is the knee; 8 buys nothing.
- No visible frame-time change. On a 16-thread machine with an NVMe disk the
  game thread's own chunk work (`doLoadGridsquare`, lighting) and rendering set
  the frame-time tail, not the streamer. A slower CPU or disk would gain more
  from both changes, but that is not measured here.
- Parity: identical recalc output to stock at W=1, 2, 4, 15 and across two
  W=4 runs (131,133 squares in 1,653 chunks); an injected worker failure is
  retried on the streamer thread and still matches.

## Max-zoom driving benchmark implementation status

The separate `drive` harness mode is implemented but not enabled as a default.
It validates that the local player remains the vehicle driver, applies forward
input through the vehicle `CarController`, measures completion from observed
vehicle displacement, and rejects a missing or changed driver/vehicle. The
legacy `bench` and `parity` modes retain their teleport route unchanged.

Driving and rejected runs write `pzopt-bench.out` metadata for route status,
movement source, vehicle identity and distance, effective/max zoom, offscreen
dimensions, camera-pan setting, chunk-map width, resolution, renderer backend,
dashboard state, and the complete runtime settings string. `harness/run.sh`
also has a `--game-profiler` switch that temporarily enables
`GameProfiler.Enabled=true`, copies the resulting recording into the run
directory, and restores the user's debug options on exit.

The installed game jar compiles the harness changes and the existing unit tests
pass. A fixed vehicle fixture and a live GameProfiler recording are still
required before the driving benchmark tasks can be marked complete; no cache
decision is made from the existing teleport-route data.

## 2026-09-18: native Linux build, harness re-validated, first frame-time baseline

See `docs/native-baseline-2026-09-18.md`. Short version: the game is now the
native build (NVIDIA GL, `~/Zomboid`); all frame-time baselines before this
date are Proton and not comparable. Stock on the teleport route at 5120x2160:
162 fps mean, p99 19–22 ms, half the frames below the 240 cap, **GPU 80 %
mean / p90 100 %**, 13 of 16 CPU threads idle — the route is GPU-bound at this
resolution, and the remaining frame-time tail is CPU-side stalls.

## 2026-09-18 (evening): review, native re-measure of the shipped change, new plan

Full write-up: `docs/plan-driving-frame-time.md`. Dashboard:
`docs/benchmark-progress.html` (regenerate with `python3 harness/dashboard.py`).

- Wake + pool W=4 on native NVIDIA GL (`native-both-w4-20260918-181000` vs the
  stock pair `native-stock-3-20260918-173527/-175009`): chunk latency p50
  160 → 6.2 ms (−96 %), p90 287 → 35 ms, p99 1004 → 613 ms; frame mean / p99 /
  p99.9 and GPU busy all within noise. Same verdict as on Proton.
- Harness bugs fixed (compare.py crash on native runs, Zink baseline pair,
  MangoHud zero GPU columns, run.sh restore ordering, Lua flag consumption,
  drive mode never validated): listed in the plan, section 5.
- The drive fixture `Apocalypse/2026-09-18_12-18-03` has the player on foot
  (`drive-1-20260918-180741`, `route_status=rejected`); drive mode now spawns
  a vehicle and seats the player.
- A/Bs on the teleport route: no PZDashboard → p99 −6 % (rest within noise);
  G1 → p99 −13 % but 3× the frames > 33 ms (not adopted).
- Drive mode drives (cruise control, 60 km/h in 6 s) but the first recorded
  run ended in a tree line; road spawn added, not yet verified (session was
  locked during the check run). No driving baseline yet.
- Next measured target (JFR attribution): the per-frame translucent render
  pass in `FBORenderCell` (trees, windows, fading walls), not the streamer.

## 2026-09-18 (night): max-zoom teleport route, three render-side changes adopted

All runs: native Linux build, NVIDIA GL 615, 5120x2160, zoom pinned at 2.5
(`--flag zoom=max`), teleport route 18 tiles/s, wake+pool on, direct launcher
(Steam was logged out; `harness/run.sh --launcher auto`). Run directories in
`harness/runs/`; attribution with `harness/attribute.py` (now also
`--thread main` for the render thread and caller chains in `--drill`).

| run | change | frame mean | p99 | p99.9 | GPU busy | game thread | render thread |
|---|---|---|---|---|---|---|---|
| direct-z25-1 | reference (wake+pool) | 6.0 ms | 18.5 | 28.8 | 80 % | 82 % | 63 % |
| pvbo-1 | + `persistentVbo` | 6.2 | 18.7 | 28.4 | 84 % | 80 % | 35 % |
| trees-1 | + `treesInChunkTexture` | 5.2 | 17.4 | 26.2 | 61 % | 98 % | 32 % |
| hotsave-1 | + `hotsaveIntervalSec=30` | 5.0 | 16.0 | 25.1 | 61 % | 99 % | 31 % |
| windows-1 | + `windowsInChunkTexture` | 5.0 | 16.4 | 25.3 | 62 % | 98 % | — |
| tltiles-1 | + `translucentTilesInChunkTexture` | **4.5** | **13.8** | **22.4** | 59 % | 95 % | 19 % |
| nodash-all-1 | same, PZDashboard mod disabled | 4.4 | 11.9 | 20.2 | — | 95 % | — |
| uifbo-1 | same, game option uiRenderOffscreen=true (UI at 120 fps) | 4.5 | 13.1 | 22.4 | 58 % | 95 % | — |
| budget-1 / budget-2 | + `bakeBudget=8 lightingBudget=8` | 4.4 | **8.3 / 8.4** | **19.2 / 18.3** | 64 % | 96 % | — |
| rebake-1 | + `lightingRebakeMs=500` | 4.4 | 9.0 | 19.9 | 61 % | 97 % | — |
| cutaway-1 | budgets + `cutawayFast cutawayRadius=6 gridStackInterval=10` | 4.4 | 8.4 | 18.9 | 66 % | 95 % | — |
| nodash-budget-1 | budgets + cutawayFast, PZDashboard disabled | **4.3** | **7.9** | **11.8** | — | 96 % | — |

Attribution that led there (`attr-jfr-z25`, 5 ms samples, both threads):

- Render thread: 25 % `glMapBufferRange` (an orphaning `glBufferData` + map per
  64 KB sprite batch), 14 % `ArrayList.grow` from `StateRun.ops.add`, 10 %
  per-float `Buffer.put` bounds checks, 7 % per-sprite uniform setters. The
  persistent mapping removed the first item; the thread is no longer near the
  frame budget, so frame time did not move until the game thread was helped.
- Game thread at max zoom: 80 % of ordinary-frame time in `IsoCell.render`;
  the per-frame translucent pass (`renderOneLevel_Translucent`) alone was 35 %
  of all game-thread samples, every `IsoTree` being drawn per frame the main
  reason. Baking static trees (`isTreeRenderedEveryFrame` = false) cut the
  GPU from 84 to 61 % and the frame mean by 1 ms; the recording
  (`trees-1/recording.mp4`) shows forests intact.
- The chunk save worker's ancillary hot save (whole meta grid serialised on
  the game thread) fired every ~0.45 s while moving (222 episodes on the
  zoom-1 route); at 30 s minimum spacing 82 drains are skipped per hot save.
- Zoom 1.0 runs (`attr-jfr-1`) sit at 233 fps / p99 8 ms; their tail is Lua
  (PZDashboard collectors: the fog sweep does 6000 Java calls per pass in a
  single tick, plus `string.match`/`gsub` allocation churn) and the hot save.
- The 250–290 ms frame at route start in earlier runs was the zoom change
  itself (hundreds of chunk levels baked in one frame); the harness now forces
  the zoom when the world comes up, before the settle time.

Dev counters (`instrument=true` logs "translucent pass per frame" every 1800
frames) showed what the per-frame pass still drew after the trees: 1,500–3,600
objects per frame carrying the `Translucent` tile property (`depthFlags & 2`;
16,476 tile definitions in `media/tileGeometry.txt`: damaged fences, railings,
wall decorations and overlays, farm crops, pylons, boulders…) against only
30–130 windows and 20–40 doors. Baking those tiles (`tltiles-1`) took the
count to 50–120 (the ones fading near the player), and the recording shows
fences, poles, planters and wall signs intact. The windows switch alone is
within noise (kept, harmless).

Slow-frame composition after the tiles change (`attr-jfr-all`, frames ≥ 8 ms,
game thread): chunk-texture bakes 26 % (a new chunk row at max zoom bakes
dozens of chunk-level textures in one frame), Lua OnTick events 15 %
(PZDashboard), lighting cache refresh 12 %, cutaways 11 %. A per-frame budget
of 8 texture bakes (deferred textures keep their previous image for a frame;
never-baked ones wait) and 8 lighting-cache chunk refreshes (continued next
frame) took the p99 from 13.8 to 8.3 ms. Holding lighting-only re-bakes for
500 ms on top did nothing measurable and is not adopted.

**What the tail is now.** In the budget profile every frame ≥ 15 ms except
five is a Lua `OnTick` burst of 18–24 ms that repeats every 2.00 s exactly:
PZDashboard's `vehicles`, `fog`, `containers` and `appearance` collectors all
default to a 2.0 s interval and fire on the same tick (the fog sweep alone
makes up to 6,000 Java calls per pass). Disabling the mod takes the p99.9 from
19.2 to 11.8 ms. Recommendation for the dashboard mod, not this repo: stagger
the collectors (offset each category's first run by a fraction of its
interval) and spread the fog sweep over ticks (a few hundred calls per tick).
The cutaway savings (mask replay, visit radius, grid-stack interval) are
within noise on this route because the frame cap hides CPU headroom; the mask
replay is on by default, the other two stay off. The last ≥ 30 ms frame in
every run sits at route start (+0.3 s) and is a route-start artefact.

Defaults in `Config.java` are now the adopted set (`persistentVbo`,
`treesInChunkTexture`, `windowsInChunkTexture`, `translucentTilesInChunkTexture`,
`cutawayFast` true; `hotsaveIntervalSec=30`, `bakeBudget=8`,
`lightingBudget=8`); each is a one-line kill switch in `pzopt.properties`.
Not yet done: a long free-play soak of the baked-object changes (interiors,
zombies behind fences, curtains/doors state changes), the Steam-launched vs
direct A/B (Steam was logged out), and the driving fixture (the car still
leaves the road; see `drive-race-*`).

Remaining game-thread profile (`attr-jfr-trees`, before the tiles change): translucent pass still
~35 % (windows, glass doors, `Translucent`-flagged tiles, wall lighting),
`IOpenGLState` set calls 5 %, `TilePropertyAliasMap` string lookups from
`IsoObject.prepareToRender`, per-sprite uniform HashMap lookups, cutaway
occlusion recompute every frame while any chunk texture is dirty.

## 2026-09-19 (00:50–01:30): first valid driving runs

Drive mode now follows the road (`Harness.roadFollow`: heading from the
vehicle's own motion, street centre sampled 4–14 tiles ahead, a bearing fan
when nothing is straight ahead; distance is path length). The Rosewood bench
save (`Sandbox/pzopt-bench`, default when no `--source-save` is given) puts the
car on the highway east of town; the Apocalypse save starts on a farm track by
the river and is not a driving fixture. Route: `E:1200` at 60 km/h cruise,
zoom 2.5, both runs complete the 1,200 tiles in 73 s, 44 chunks/s streamed.

| drive run | settings | frame mean | p90 | p99 | p99.9 | max | GPU busy | game thread | render thread |
|---|---|---|---|---|---|---|---|---|---|
| drive-stock-road-1 | everything off (stock) | 8.1 ms | 14.5 | 18.4 | 22.3 | 28.7 | 92 % | 52 % | 75 % |
| drive-road-2 | Config defaults (all adopted changes) | **4.2** | **4.2** | **5.4** | 18.2 | 25.7 | 55 % | 100 % | 12 % |

Stock driving at max zoom is GPU-bound (92 %) at 123 fps mean; with the
adopted set the route sits on the 240 fps cap for 99 % of frames and the GPU
has 45 % headroom. The p99.9 (18 ms) is the same 2-second PZDashboard burst
as on the teleport route.

## 2026-09-19 (01:30–03:40): quad-view showcase, Steam performance monitor found capping fps

Four showcase recordings (60 and 120 km/h, stock vs optimized) were re-taken for
the quad-view video (`harness/stitch-quad.sh`, `config/mangohud-showcase-*.conf`).
The first optimized takes stopped at ~157 fps instead of the 240 fps cap seen at
00:50, with every pzopt setting confirmed identical (`[pzopt] settings:` line) and
the same save, route and zoom. The analyzer's thread split pointed at the render
side: the GL thread (`main`) went from 12–17 % of a core to 90 %, the game thread
(`MainThread`) from 99 % to 30 %, GPU busy from 55 % to 42 %.

Cause: **Steam's in-game performance monitor** (the newer FPS/perf overlay in
Steam's settings, not the classic Steam overlay). It hooks every GL call and
serialises the render thread. Verified by A/B/A on the optimized 120 km/h route,
all launched through Steam with nothing else changed:

| Steam performance monitor | classic overlay | route | mean fps | render thread | game thread |
|---|---|---|---|---|---|
| on (as at 01:30–02:30) | on | 60 km/h | 157 | 92 % | 31 % |
| off, Steam shut down, `--launcher direct` | – | 60 km/h | 238 | 17 % | 99 % |
| off | off | 120 km/h | 237 | 21 % | 99 % |
| off | on | 120 km/h | 236 | 22 % | 97 % |
| **on** | on | 120 km/h | **164** | **92 %** | 24 % |
| off | on | 120 km/h | 237.5 | 19 % | 99 % |

The classic Steam overlay is harmless; only the performance monitor costs the
frames. It stays disabled for all benchmark and showcase runs. Stock is GPU-bound
at ~75 fps at max zoom either way, so the monitor does not change the stock
numbers, only hides the optimized headroom. Runs: `quad2-opt60-1` (capped),
`quad5-opt60-2`, `quad6-*` (video), `overlaycheck-opt120-1..4` (A/B/A).

Two smaller findings from the same session:

- Direct launches (`--launcher direct`) ran the JVM under the desktop's
  `LC_NUMERIC=de_DE`; MangoHud then failed to parse `fps_metrics=avg,0.01,0.001`
  (no 1 % / 0.1 % rows, `4,2ms` formatting). `run.sh` now pins `LC_NUMERIC=C` for
  direct launches, matching the Steam runtime.
- MangoHud 0.8.4 applies `offset_x` towards the anchored edge for right-anchored
  positions, so `position=top-right` needs a negative `offset_x` to move inwards.

Showcase runs also press MangoHud's `reset_fps_metrics` key (Shift_R+F9, via
xdotool) at route start so avg / 1 % / 0.1 % cover only the drive, and `--record`
now captures desktop audio. The quad video (`docs/media/drive-60-120kmh-stock-vs-
optimized-quad.mp4`, 3840x1920, git-ignored at 731 MB) uses the `quad6-*` runs:
optimized 60 and 120 km/h both at the 240 fps cap with the GPU at ~57–61 %,
stock at 72–75 fps with the GPU at 100 %.

## 2026-09-19 (15:00–15:45): why the GPU idles uncapped — Zink's swap, not the game

Full write-up: `docs/plan-resource-use.md`. New tooling: `run.sh --jfr-setting`,
wait events in `tools/JfrSamples.java`, `harness/waits.py` (per-thread blocking
sites in the route window).

| run | renderer | route | fps | GPU busy | game thread | GL thread |
|---|---|---|---|---|---|---|
| waits-uncap-1 | Zink / Wayland | 122 km/h | 378 | 60 % | 60 % (39 % of the window blocked waiting for the GL thread) | 30 % (64 % of native samples inside glfwSwapBuffers) |
| waits-uncap-zinkx11-1 | Zink / XWayland | 122 km/h (off road) | 293 → 195 | 51 % | 68 % | throttled to the 240 Hz refresh |
| waits-uncap-gl-1 | NVIDIA GL | 122 km/h (off road) | 629 moving | 93 % | 85 % | 59 % |
| base60-gl-1 | NVIDIA GL | 60 km/h, complete | 570 | **98 %** | 69 % | 43 % |
| uifbo60-gl-1 | NVIDIA GL, uiRenderOffscreen=true | 60 km/h, complete | 567 | 98 % | **57 %** | 42 % |

- The game thread runs one frame ahead and waits for the single ready slot; the
  GL thread's wall time sets the frame, and on Zink ~1.8 ms of it is the swap.
- NVIDIA GL uncapped is GPU-bound at max zoom (12800x5400 offscreen buffer);
  the CPU idle is the correct state there.
- Stock "render UI offscreen" removes the per-frame Lua UI draw (25–34 % of
  game-thread CPU); frames from both recordings are identical.
- 122 km/h runs left the road twice today at ~380 tiles; A/Bs use 60 km/h.

## 2026-09-19 (15:45–16:05): NVIDIA GL on native Wayland, MangoHud overlay fixed there

- `wl-gl60-1` (NVIDIA GL, `-Dzomboid.wayland=1`, 60 km/h): 527 fps, GPU 96 %,
  route complete, but no MangoHud HUD or CSV: GLFW resolves `eglSwapBuffers`
  with `dlsym` on its private libEGL handle, so the preloaded hook never sees
  the swap (on X11 the Steam overlay's dlsym hook chains to MangoHud).
  MangoHud's own dlsym shim kills the game's JNI launcher (`wl-gl-mhshim-1/2`).
- Fix in the `Display` override (`docs/override-edits.md`): when MangoHud's
  library is preloaded and the platform is Wayland, `swapBuffers()` calls that
  library's exported `eglSwapBuffers` with GLFW's EGL display/surface through
  the JDK foreign-function API. Proved first with `tools/GlfwSwapProbe.java`
  (the game's LWJGL build; MangoHud blacklists processes named `java`, so the
  probe runs under a copied launcher).
- `wl-gl-mh-3` (400-tile route): HUD drawn, control socket up, CSV logged;
  663 fps mean, frame 1.5 / 4.2 ms (mean / p99), GPU 92 %.
- The single 3.4 s frame at route start in `wl-gl60-1` came from the other
  session's texture-buffer override installed during that run (256 MB decode
  budget, since reverted to 50), not from the Wayland GL path.

## 2026-09-19 (20:48–22:15): native Wayland vs XWayland, capped 240, same build

Question: should `-Dzomboid.wayland=1` (native Wayland window) become the default? Runs
are NVIDIA GL 615.71, adopted Config defaults (persistentVbo and
translucentTilesInChunkTexture off), `--no-dashboard`, instrument on, back to back.

| Run | Display | Route | fps | frame mean / p99 / p99.9 (ms) | under cap | GPU | render thread |
|---|---|---|---|---|---|---|---|
| `xwl-bench-1` | XWayland | bench, max zoom | 203 | 4.9 / 14.0 / 20.3 | – | 56 % | 37–51 % + NVIDIA worker 47 % |
| `wl-bench-glthr-2` | Wayland | bench, max zoom | 199 | 5.0 / 14.0 / 20.5 | – | 55 % | 80–88 %, no worker |
| `wl-bench-1` / `wl-bench-jfr-1` / `wl-bench-glthr-1` | Wayland | bench, max zoom | 196 / 196 / 196 | 5.1 / 14.8–15.0 / 21–22 | 41–43 % | 55 % | 81 % |
| `xwl-drive60-1` | XWayland | drive 60 km/h | 242 | 4.1 / 5.9 / 10.3 | 17.5 % | 54 % | 37 % |
| `wl-drive60-1` | Wayland | drive 60 km/h | 242 | 4.1 / 6.3 / 10.1 | 17.5 % | 56 % | 65 % |

- Verdict: at the 240 cap native Wayland is a wash. Bench mean 5.0 vs 4.9 ms and p99
  14.0 vs 14.0; drive p99 6.3 vs 5.9 and p99.9 10.1 vs 10.3, all inside the
  `compare.py` noise floor (0.2 ms mean, 0.6 ms p99). XWayland stays the default.
- The first comparison (`wl-bench-1` 196 fps against `fpscap-stock240` 215 fps, logged
  earlier tonight as a Wayland regression) was mostly a build difference: the artifact
  fixes installed at 20:52 turned persistentVbo and translucentTilesInChunkTexture off,
  and the two runs did not share settings. The same-build pair is 4 fps apart.
- Where the CPU goes differs, and it is the one real Wayland finding. A per-thread
  snapshot of the live process (`harness/native-threads.sh`, all native threads, not
  only the Java ones `pzopt-threads.out` sees) shows an unnamed second `ProjectZomboid6`
  thread at 47 % of a core on XWayland: NVIDIA's threaded-optimisation worker under GLX.
  On native Wayland (EGL) there is no such thread, even with
  `__GL_THREADED_OPTIMIZATIONS=1` in the process environment (verified in
  `/proc/<pid>/environ`); the render thread does that work itself (88 % vs 56 % of a
  core) and its JFR profile is dominated by `glDrawRangeElements` (15 % of samples)
  where XWayland's is dominated by `glGetInteger` and `glClientWaitSync` sync points.
  Total process CPU is equal (259 vs 281 % of a core). Capped, the render thread has
  slack either way; uncapped the GL thread's wall time sets the frame, which matches the
  earlier 527 fps (Wayland, `wl-gl60-1`) vs 570 fps (XWayland, `base60-gl-1`).
- MangoHud on native Wayland: HUD and CSV work through the `Display` override's swap
  hand-off, and the log starts and stops through the control socket in bench and drive
  mode (`wl-drive60-1`). The only piece that cannot work is the xdotool keypress that
  resets the HUD fps metrics at route start: MangoHud's Wayland keybind path needs its
  `eglGetPlatformDisplay` hook to see GLFW's `wl_display`, which GLFW's private `dlsym`
  bypasses, and the control socket has no reset command (only hud / logging / fcat).
  It is cosmetic: MangoHud's fps metrics are a rolling window of the last 10 000 frames
  (about 45 s at 240 fps), so by the end of any route the HUD shows route-only numbers.
  `run.sh` now says so and skips the keypress on native Wayland instead of sending it to
  nowhere (`native_wayland` flag from `--env ...zomboid.wayland=1`).
- Also fixed tonight: `analyze.py` did not parse `gc.log` files with comma decimals
  (runs before the `LC_NUMERIC=C` launch), so yesterday's benches showed "0 GC events".
  With the fix every run has 6–12 ZGC cycles in the route window; there was no GC
  regression.

## 2026-09-19 (22:20–22:50): native Wayland vs XWayland, uncapped

Same build and options as the capped pair above, `--prop uncappedFps=true`, back to back.

| Run | Display | Route | fps | frame mean / p99 / p99.9 (ms) | jitter | GPU | game thread / render thread |
|---|---|---|---|---|---|---|---|
| `xwl-uncap-bench-1` | XWayland | bench, max zoom | 296 | 3.4 / 12.7 / 18.7 | 1.0 | 70 % | 95 % / 68 % |
| `wl-uncap-bench-1` | Wayland | bench, max zoom | 262 | 3.8 / 13.7 / 20.0 | 0.7 | 65 % | 86 % / 94 % |
| `xwl-uncap-drive60-1` | XWayland | drive 60 km/h | 471 | 2.1 / 5.1 / 8.8 | 0.5 | 86 % | 97 % / 73 % |
| `wl-uncap-drive60-1` | Wayland | drive 60 km/h | 391 | 2.6 / 5.0 / 9.2 | 0.2 | 73 % | 82 % / 98 % |

- Uncapped, native Wayland is 12 % (bench) to 17 % (drive) slower in mean frame time,
  well outside the noise floor. The tails are the same: p99 12.7 vs 13.7 and 5.1 vs 5.0,
  p99.9 within 1 ms. Jitter is slightly lower on Wayland because the render thread is the
  steady bottleneck there.
- The cause is the one found in the capped runs: no NVIDIA threaded-optimisation worker
  under EGL. The render thread ("main") sits at 94–98 % of a core on Wayland with the game
  thread waiting on it (82–86 %, down from 95–97 %), and the GPU is left at 65–73 % instead
  of 70–86 %. On XWayland the driver worker takes the command building off the render
  thread (73 % there) and the GPU is the limit on the drive route (86 %, p90 100 %).
- Verdict unchanged and now stronger: XWayland stays the default; native Wayland costs
  frame rate uncapped and gains nothing capped. Against the objective ("CPU and GPU maxed
  if not pegged at 240"), Wayland is the worse state: one core pegged, GPU idle time.

## 2026-09-19 (22:25–23:05): ZombieBuddy + ZBBetterFPS vs our overrides

Zed's ZBBetterFPS (Steam Workshop build of 2026-08-09, `42.13` jar, supports 42.12–42.17;
game is 42.20.4) loaded through ZombieBuddy 2.3.3 (GitHub release jar, `-javaagent` in the
launcher JSON via the new `--vmarg`; `JAVA_TOOL_OPTIONS` is unusable because the launcher's
libjvm-locating helper JVM picks the agent up and dies on `zombie.Lua.LuaManager`). Mods copied
to `~/Zomboid/mods`, enabled per run with the new `--mod`. Every ZBBetterFPS option on except
render distance (game default, 152 tiles, so the view is identical), uncapped FPS (default),
instant zoom, background throttling ("never"). The mod runs use our build with every
runtime optimization switched off (the same stock-behaviour property set as the showcase stock
runs) so the harness route driver is present; "stock" below is that set without the mod.
All six runs: XWayland, NVIDIA GL, 5120x2160, max zoom, no dashboard, `--option frameRate=240`
(options.ini had drifted to 60 fps: the 22:06–22:16 forced-uncapped runs left uncappedFPS=true and
stock Core.loadOptions rewrote it as frameRate=60 on the next boot; fixed in a0d323b, FrameCap now
snapshots and restores the file. The boot log's `frame cap:` line is the tell).

Findings:
- **Object separation patch crashes 42.20.** `optimizeIsoMovingObject` reads
  `IsoZombie.networkAi`, which no longer exists: `NoSuchFieldError` on the game thread 75 s
  into `zbfps-bench-1`, game thread dead, native SIGSEGV on shutdown. Disabled for the runs below.
- **Ring-buffer patch never applies in a fresh boot.** `SpriteRenderer$RingBuffer.create`
  runs before the Lua `OnGameBoot` handler sets the flag, so its 1 MB buffers were not in effect
  (no "Patching SpriteRenderer.RingBuffer" line). The other patches (IndieGL alpha/depth cache,
  chunk-depth uniform cache, MVP matrix cache, IsoChunkMap width, MultiTextureFBO2, main-loop
  sleeps) were transformed and enabled.
- The mod is within noise of stock on both routes; our overrides are the only thing that
  moves the tail.

| run | route | fps mean | frame mean / p99 / p99.9 ms (game) | mangohud p99 / p99.9 | >33 ms | GPU busy | game CPU |
|---|---|---|---|---|---|---|---|
| `stock-bench-1` | teleport, max zoom | 156.7 | 6.4 / 19.3 / 27.4 | 22.4 / 35.5 | 24 | 78 % | 306 % |
| `zbfps-bench-2` | teleport, max zoom | 161.8 | 6.2 / 18.6 / 27.9 | 22.1 / 34.2 | 19 | 79 % | 306 % |
| `ours-bench-1` | teleport, max zoom | 198.1 | 5.0 / 14.4 / 20.2 | 16.9 / 25.2 | 4 | 55 % | 298 % |
| `stock-drive120-1` | E:1200 at 122 km/h | 121.3 | 8.2 / 19.1 / 22.1 | 17.6 / 21.9 | 0 | 90 % | 288 % |
| `zbfps-drive120-1` | E:1200 at 122 km/h | 122.5 | 8.2 / 18.8 / 22.2 | 17.2 / 20.7 | 1 | 90 % | 286 % |
| `ours-drive120-1` | E:1200 at 122 km/h | 234.8 | 4.3 / 7.3 / 13.9 | 9.3 / 17.0 | 1 | 58 % | 291 % |

Stock and the mod are GPU-bound at the 240 cap (90 % busy while driving, 16 % of the machine's
CPU); the chunk-texture baking in our build is what halves the GPU work and puts the drive at the
cap. Nothing in ZBBetterFPS touches that path, so stacking it on top of our build is not worth a
run. Recordings of the three drives were checked frame-by-frame: identical scenes, no artifacts.

## 2026-09-19 (23:00): side-by-side video, stock 244 fps cap vs optimized uncapped, 120 km/h

`harness/stitch-sbs.sh` -> `docs/media/drive-120kmh-stock-244cap-vs-optimized-uncapped.mp4`
(3840x1450, 63 s). Both panes start at their own launch with live boot / load counters; the
optimized pane waits at its route start until the stock run is loaded, then both drive in sync.

| run | build | cap | boot (first log -> Continue) | load (Continue -> world) | fps mean | frame mean / p99 / p99.9 ms | GPU busy |
|---|---|---|---|---|---|---|---|
| `sbs-stock120-1` | every pzopt flag off (render, loader and boot flags) | 244 (`--option frameRate=244`) | 7.31 s | 8.74 s | 122.1 | 8.2 / 18.9 / 22.6 | 91 % |
| `sbs-opt120-uncap-1` | defaults, `--prop uncappedFps=true` | none | 6.01 s | 3.68 s | 412.4 | 2.4 / 6.3 / 11.4 | 86 % |

Stock at the 244 cap is GPU-bound at ~122 fps exactly as at the 240 cap. The optimized boot
here (6.0 s) is slower than the 5.0 s figure from the load loop: the caches under
`~/Zomboid/pzopt` were cold-ish after the stock run and the machine was busy with a render
check; the load (3.7 s) matches. Hardware panel: Ryzen 7 9800X3D, RTX 4090 (driver 615.71),
32 GB DDR5-8000, Crucial T705 2 TB (13.5 GB/s sequential read measured with fio, 1 MiB QD32,
direct I/O through ZFS).

Side finding: `zpool status zpcachyos` reports 5 permanent data errors (ZFS-8000-8A) on the
T705 pool that holds /games and /home; `zpool status -v` lists the files.

## 2026-09-20 (03:10–04:00): game-thread pass on a Rosewood teleport route

Question: nothing so far touched the simulation half of the game thread; what
does the thread spend on, and how far is 240 locked? New route: the bench save
loads at 8002,11204 just north of Rosewood; `--flag route=S:1800` (100 s) goes
south through the town, and the short form `--flag route=S:450 --flag turn=90
--route-seconds 25` (new harness flag: the player facing spins 90°/s so the
vision cone, lighting cone and cutaways keep changing) loads 55 chunks/s. All
runs: NVIDIA GL, 5120x2160, zoom 2.5, 240 cap, no dashboard, `gt-*` in
`harness/runs/`.

Attribution (5 ms JFR, route window, share of game-thread samples): on the 100 s
route render-command recording 67 % (`IsoCell.render` 46 %, Lua UI draw 15 %,
weather mask 2.6 %), logic 30 % (`IsoWorld.update` 19 %: player 4 %, zombies
and animation post-update 5 %, chunk hand-off 5 %), lighting JNI 3 %. The
over-budget time is bursts: 90 % of the excess over 4.17 ms came from frames
over 6 ms, and those were chunk-texture bakes (36 % of their samples), world
update (21 %), chunk hand-off (10 %), cutaway visits (6 %). Bakes were 2.4 to
4.3 per frame and 87 % of them re-bakes (lighting drift, neighbour-loaded seam
redraws, cutaway changes); the existing bake budget only defers never-baked
levels, which is why `bakeBudget=3` changed nothing.

| run | change | fps mean | frame mean | p90 | p99 | p99.9 | under the cap | game thread |
|---|---|---|---|---|---|---|---|---|
| gt-q-1 | reference, previous defaults | 199.1 | 5.0 ms | 8.1 | 16.9 | 29.8 | 31.2 % | 92 % |
| gt-q-3 | + weather-mask scan gate and building-only scan | 203.1 | 4.9 | 7.6 | 16.5 | 28.1 | 29.9 % | 92 % |
| gt-q-cut-1 | + `cutawayRadius=6 gridStackInterval=8` | 208.2 | 4.8 | 7.1 | 15.9 | 28.1 | 30.2 % | 91 % |
| gt-q-bake3-1 | + `bakeBudget=3` | 208.3 | 4.8 | 7.1 | 15.6 | 27.0 | 30.2 % | not adopted |
| gt-q-ui-1 | + `uiRenderOffscreen=true` (stock option) | 208.4 | 4.8 | 7.0 | 16.2 | 28.8 | 32.6 % | not adopted |
| gt-q-lrb-1 | + `lightingRebakeMs=250` | 215.6 | 4.6 | 6.5 | 13.6 | 23.8 | 29.1 % | 95 % |
| gt-q-rb-1 | + `rebakeBudget=4` (new) | 225.9 | 4.4 | 5.5 | 11.2 | 19.7 | 28.3 % | 97 % |
| gt-q-def-1 | + light-switch cache, all as defaults | 225.6 | 4.4 | 5.5 | 11.3 | 20.4 | 27.9 % | 97 % |
| gt-q-lrb1000-1 | `lightingRebakeMs=1000` | 225.7 | 4.4 | 5.5 | 11.1 | 21.7 | 26.7 % | not adopted |
| gt-q-lua-1 | + single-lookup Kahlua `rawget` | 228.1 | 4.4 | 5.3 | 10.5 | 20.0 | 27.8 % | 97 % |
| gt-q-occ-1 | + occluder masks on the chunk | **228.8** | **4.4** | **5.4** | **10.2** | **17.8** | **27.1 %** | 97 % |
| gt-base-1 | 100 s route, previous defaults | 230.1 | 4.3 | 5.1 | 10.5 | 20.9 | 24.4 % | 90 % |
| gt-long-final-1 | 100 s route, final build | **238.5** | **4.2** | **4.6** | **7.3** | **14.4** | **20.6 %** | 94 % |

Visual check: `gt-q-rb-2` (final keys) and `gt-q-ref-1` (every new key off)
were recorded; frames every 2 s over the route are the same, including the
dark unloaded and unlit areas at the leading edge that both show at 18
tiles/s. Console errors are the same stock map warnings in both.

What each trim was (details in `docs/override-edits.md`): the weather mask
rasterized the whole view every frame even when it could add no mask
(`isInteriorLocation` per exterior tile, 70 % of the pass in slow frames);
`checkTreeTranslucency` did a `HashSet.remove` per tree per frame on an
almost always empty set (our own edit) and read the aim key per tree;
`LightingJNI.checkLights` asked every light switch for power every frame
(2.3 %); the exact occluder mask lookup was a `HashMap.get` per on-screen
level per frame (1.8 %); Kahlua's `rawget` did `containsKey` then `get`.

Honest reading against the objective. The game thread is now 97 % busy on the
spinning route and 94 % on the plain one with the GPU at 59 to 68 %, so the
frame is game-thread-bound and the remaining cost is broad: chunk texture
bakes 20 % (create and object changes while streaming; the re-bakes that can
be held are held), `IsoWorld.update` 23 % (player 4 %, zombies 3 %, animation
post-update 6 %, vehicles 2 %, chunk hand-off 4 %), the Lua UI draw 10 % plus
its update 3 %, JNI light info caching 3 %, `LightingJNI.update` 3 %. There is
no single hot spot left worth a class override; 240 locked on this route needs
the game thread to do less per frame structurally: recording chunk-texture
bakes off the game thread, or overlapping the render-command recording with
the next frame's logic (`docs/plan-resource-use.md`). Both are multi-day
changes with real race risk and need their own plan and gate.

Video (04:26–04:31): `docs/media/rosewood-spin-stock-vs-optimized-vs-game-thread.mp4`
(`harness/stitch-triple.sh`, 3840x1920, 28 s) from three recorded runs of the spinning
route with the showcase HUD: `gtshow-stock-2` (every Config key at its stock value, the
harness plumbing still installed so the run auto-starts: 105.3 fps mean, 9.5 ms, p99
28.3), `gtshow-opt-1` (today's keys off: 197.4 fps, 5.1 ms, p99 18.0) and `gtshow-gt-1`
(defaults: 225.8 fps, 4.4 ms, p99 11.6). The "optimized before" cell still carries the
three key-less code trims (tree check, Kahlua rawget, occluder masks on the chunk),
which are within noise. A stock run with the overrides uninstalled cannot auto-start
(click-to-start is pressed by pzopt.AutoStart), which is why the keys-at-stock form is used.

Small measured negatives, not adopted: `bakeBudget=3` (bursts are re-bakes,
not first bakes), `uiRenderOffscreen=true` (UI draw 10 → 7 % but frame time
unchanged at 240), `lightingRebakeMs=1000` (fewer bakes, same frame time).


## 2026-09-20 (05:05–05:50): laptop, JVM x GC matrix on the spinning route, in-game overlay only

Machine: the laptop (`diego-flip`, Ryzen AI 9 / Radeon 890M, Mesa 26.2.3, 1920x1080,
**on battery** throughout, ~25 to 40 W package draw), not the desktop; none of the
desktop numbers above apply. Every run: max zoom, no dashboard, `--prop uncappedFps=true`,
frame times from `pzopt-overlay.out` with MangoHud not loaded at all (new
`harness/run.sh --no-mangohud`; `analyze.py` reads the overlay log the same way).
One run per cell, so treat differences under ~5 % as noise.

Spinning route (`--flag route=S:450 --flag turn=90 --route-seconds 25`), optimized
build. "Stock" is our build with every render and game-thread key at its stock value
(`parallel wake persistentVbo treesInChunkTexture windowsInChunkTexture
translucentTilesInChunkTexture translucentCache cutawayFast weatherMaskIdleSkip` false,
`hotsaveIntervalSec bakeBudget lightingBudget lightingRebakeMs rebakeBudget
lightSwitchCheckFrames cutawayRadius gridStackInterval` 0), the harness plumbing and
the overlay still installed. GC is the launcher JSON's `-XX:+UseZGC` unless `--gc g1`.

| run | JVM | GC | fps | mean | p50 / p90 | p99 | p99.9 / max | >33 ms | jitter | JIT+GC CPU |
|---|---|---|---|---|---|---|---|---|---|---|
| spin-stock-uncap-1 | Zulu 25.0.1 | ZGC | 44.2 | 22.6 | 19.2 / 38.0 | 67.1 | 110 / 127 | 185 | 11.2 | |
| spin-opt-uncap-1 | Zulu 25.0.1 | ZGC | 66.9 | 14.9 | 13.3 / 24.4 | 41.0 | 98 / 125 | 50 | 5.7 | 87 s |
| spin-opt-uncap-g1-1 | Zulu 25.0.1 | **G1** | **81.8** | **12.2** | **10.6 / 20.2** | 40.8 | **72 / 110** | 46 | **4.8** | 102 s |
| spin-opt-uncap-graal-1 | Oracle GraalVM 25.2.4 | ZGC | 56.3 | 17.8 | 15.6 / 29.1 | 52.1 | 104 / 116 | 86 | 6.8 | 143 s |
| spin-opt-uncap-graal-g1-1 | Oracle GraalVM 25.2.4 | G1 | 65.8 | 15.2 | 13.2 / 25.4 | 46.3 | 111 / 273 | 63 | 6.0 | 159 s |
| spin-opt-uncap-graal253-1 | Oracle GraalVM 25.3.4.1 | ZGC | 57.1 | 17.5 | 15.5 / 28.9 | 51.0 | 106 / 163 | 88 | 6.8 | 143 s |
| spin-opt-uncap-graal253-g1-1 | Oracle GraalVM 25.3.4.1 | G1 | 70.0 | 14.3 | 12.6 / 23.4 | 44.0 | 92 / 159 | 54 | 5.5 | 151 s |

Milliseconds except fps and counts. "JIT+GC CPU" is `process_cpu_ms - live_threads_cpu_ms`
from `pzopt-threads.out` over the 25 s route: CPU the JVM's own (non-Java) threads used.
The Java threads did the same work in every row (65 to 66 s of CPU; game thread 96 to 98 %
of a core, Lighting Thread 75 to 79 %).

Findings:
- The overrides at their defaults are 1.5x stock on this route here (44 → 67 fps, p99
  67 → 41 ms, >33 ms frames 185 → 50), same shape as the desktop result, at a lower level.
- **G1 beats ZGC by 22 % on this laptop** (67 → 82 fps) and the gain is in the body of
  the distribution (mean, p50, p90), not the tail: p99 and the >33 ms count are unchanged.
  ZGC's concurrent threads and load barriers cost CPU that, on a battery-limited APU, comes
  out of the clock budget of the single pegged game thread; G1 trades that for stop-the-world
  pauses (up to 53 ms each in `gc.log`, ~0.8 s total over the run) that show up as the p99.9
  still being 72 ms. On the desktop uncapped the GPU is the wall (98 % busy), so this is a
  laptop/APU finding until measured there.
- **GraalVM (Oracle, Graal JIT on by default) is ~15 % slower than HotSpot C2 on the same
  collector**, both releases. Its compiler threads used 55 to 60 s more CPU than C2 during
  the 25 s route (it warms up slower and does far more work per method), and that CPU is
  taken from the game thread. Not adopted. Both copies stay in the game dir
  (`jre64_linux` = 25.2.4, `jre64_graal253` = 25.3.4.1); `jre64` is the shipped Zulu.
  Note `analyze.py`'s "gc (ZGC) N events, M ms wall" counts ZGC's concurrent cycles, not
  pauses; ZGC pauses are sub-millisecond, so the ZGC tail is not GC pauses.

Does GraalVM catch up once warm? 100 s route (`--flag route=S:1800 --route-seconds 100`),
G1 on both, same conditions:

| run | JVM | fps | mean | p50 / p90 | p99 | p99.9 / max | >33 ms | under 240 cap | GPU busy | JIT+GC CPU |
|---|---|---|---|---|---|---|---|---|---|---|
| long-opt-uncap-g1-1 | Zulu 25.0.1 | **233.5** | **4.3** | 3.0 / 8.1 | **20.1** | 40 / 210 | 55 | 24 % | 65 % | 203 s |
| long-opt-uncap-graal253-g1-1 | GraalVM 25.3.4.1 | 204.1 | 4.9 | 3.4 / 9.1 | 24.2 | 48 / 140 | 70 | 31 % | 61 % | 283 s |

In 20 s slices (fps, Zulu vs Graal): 0–20 s in town 72.7 vs 56.6 (−22 %), 20–40 s 233.6 vs
189.2 (−19 %), 40–60 s 343.7 vs 294.3 (−14 %), 60–80 s 300.5 vs 266.4 (−11 %), 80–100 s
217.1 vs 214.0 (−1 %). The gap closes steadily as the compiler drains its queue but only
reaches parity about two minutes after launch; nothing in these runs shows Graal ahead of
C2. Not worth the 680 MB and the slow first minutes.

Side finding: the 100 s route is a different regime from the spinning one on this laptop.
Out of town the GPU gets busy (65 %, clocks to 2.4 GHz, 40 W battery draw) and a quarter of
the frames hit the 240 cap; in town on the spinning route the game thread is the wall at
~⅓ CPU and GPU utilization. Both are "fps < 240 with hardware not saturated"; the first
20 s of the long route (72 fps, p99 43 ms) is the part that matters for play.

Next, if pursued: repeat the Zulu G1 vs ZGC pair on AC before shipping `-XX:+UseG1GC` in
the launcher JSON for laptop users (n=1, battery), and the same pair on the desktop.

## 2026-09-20 (05:50–05:55): launcher JSON tuned for G1; GC pauses gone from the frame tail, tail unchanged

`ProjectZomboid64.json` on the laptop now carries `-Xms4096m -Xmx4096m -XX:+UseG1GC
-XX:MaxGCPauseMillis=25 -XX:+AlwaysPreTouch -XX:+PerfDisableSharedMem` (copies:
`config/launcher/ProjectZomboid64.g1.json`, Steam's original as
`config/launcher/ProjectZomboid64.stock.json`; Steam rewrites the file on an update, `cp`
the tuned copy back). Reasons: the G1 log of `spin-opt-uncap-g1-1` showed the heap growing
436 MB → 2.7 GB during the run with ~1.7 GB live, so a young collection every 2 to 3 s and
pauses up to 53 ms; THP is already `always` here so `UseTransparentHugePages` adds nothing.
`harness/run.sh` passes the JSON through (it only adds the gc log), so runs without `--gc`
inherit these flags from now on.

Spinning route, same conditions as the matrix above:

| run | launcher | fps | mean | p50 / p90 | p99 | p99.9 / max | >33 ms | jitter | GC pauses (run): n / total / max |
|---|---|---|---|---|---|---|---|---|---|
| spin-opt-uncap-g1-1 | `-Xmx3072m -XX:+UseG1GC` | 81.8 | 12.2 | 10.6 / 20.2 | 40.8 | 72 / 110 | 46 | 4.8 | 92 / 762 ms / 53 ms |
| spin-opt-uncap-g1tuned-1 | tuned set above | 82.6 | 12.1 | 10.5 / 20.1 | 42.1 | 77 / 122 | 39 | 4.9 | 54 / 623 ms / **9.9 ms** |

The flags did what they are for (worst pause 53 → 9.9 ms, fewer pauses, no extra JIT+GC
CPU: 102 → 100 s) and the frame distribution did not move at all: every column is within
single-run noise. So the tail that remains on this route (p99 ~41 ms, p99.9 ~75 ms, a few
100+ ms frames) is not GC; it is the game thread's own bursts while streaming (chunk
hand-off and bakes, the same bursts the 03:10 game-thread pass attributed on the desktop).
JVM flags are exhausted as a lever here. Kept anyway: no 50 ms stop-the-world hits, no
heap growth, no hsperfdata writes, for ~1 GB more RSS and a slightly longer boot
(`AlwaysPreTouch`).

## 2026-09-20 (12:10–13:10): uncapped 400 fps pass on the spinning Rosewood route

Route as the game-thread pass (`--flag route=S:450 --flag turn=90 --route-seconds 25`, max zoom,
5120x2160, NVIDIA GL, Steam launcher, no dashboard) but uncapped (`--prop uncappedFps=true`), frame
source the in-game overlay log (`--no-mangohud`), JFR at 1 ms. Every run used the maintainer's
Optimizations-tab file (persistentVbo, translucentTilesInChunkTexture on). Full table, GPU
breakdown and the structural remainder in `docs/plan-400fps.md`.

| run | change | fps mean | p50 | p90 | p99 | p99.9 | game thread | GPU |
|---|---|---|---|---|---|---|---|---|
| u400-base-1 | previous build, UI drawn every frame | 272.8 | 2.8 | 6.3 | 13.2 | 27.8 | 98 % | 80 % |
| u400-uifbo-2 | stock option `uiRenderOffscreen=true` | 374.3 | 1.9 | 4.8 | 12.3 | 23.4 | 93 % | 89 % |
| u400-it1-1 | + cutawayInvalidateChanged, soundZoneCache, lightInfoOncePerFrame | 442.2 | 1.7 | 3.8 | 9.5 | 18.3 | 91 % | 92 % |
| u400-it4-1 | + chunkHandoffDivisor=8, occlusionSkipLightingOnly | 454.2 | 1.7 | 3.7 | 9.0 | 18.4 | 89 % | 92 % |
| u400-it8-1 | + cutawayVisitPrefilter | 466.1 | 1.7 | 3.4 | 8.6 | 18.6 | 90 % | 93 % |
| u400-it9-1 | + lightInfoChunkGate | 500.8 | 1.6 | 3.1 | 7.7 | 16.8 | 89 % | 93 % |
| u400-final-1 | final build, confirmation | 499.1 | 1.6 | 3.2 | 7.6 | 15.1 | 89 % | 94 % |

Caveat found while recording the video: the game's Steam launch options are
`harness/steam-launch.sh mangohud %command%`, so the MangoHud HUD was drawn (the maintainer's
full config) on every run above even with `--no-mangohud`; it depresses the absolute numbers
(same build: 273 fps with the HUD in u400-base-1 vs 391 fps with `MANGOHUD_CONFIG=no_display`
in u400show-prev-1, UI drawn every frame in both) but was identical across the runs, so the
deltas hold. The recordings for `docs/media/rosewood-spin-uncapped-stock-vs-optimized-vs-all-hdr.mp4`
(u400show-stock-2 / -prev-1 / -all-1) hide it: stock settings 114 fps mean, p99 31.4 ms;
optimized before the pass 391 fps, p99 10.2 ms; all optimizations 492 fps, p99 7.7 ms.

No gain: weatherFxScalePct=50, lightingRebakeMs=1000, bakeBudget=4; uiRenderFPS=60 +2 % (noise).
The whole weather FX pass off is +11 % (measurement only). Verdict against the objective: the
mean is past 400 but the frame is not locked; ~13 % of frames exceed 2.5 ms, all of them chunk
streaming on the game thread (loot roll, new-row bakes, cutaway data) or the UI FBO refresh,
and from ~450 fps the GPU (chunk composite + bakes) is saturated, so the machine is now used to
the max on both sides at this resolution.

## 2026-09-20 (13:35–13:47): desktop JVM matrix — Zulu vs GraalVM 25.0.3, stock ZGC vs tuned G1 JSON

Same spinning route and conditions as the 400 fps pass (`--flag route=S:450 --flag turn=90
--route-seconds 25`, max zoom, uncapped, `--option uiRenderOffscreen=true`, in-game overlay log,
`--no-mangohud`, no dashboard, Steam launcher). This time the MangoHud HUD was really absent
(`harness: MangoHud is NOT loaded`), so the absolute numbers sit above the 400 fps pass table.
GraalVM is Oracle GraalVM JDK 25.0.3+9.1 (Graal JIT on by default) unpacked to the game dir;
the tuned JSON is `config/launcher/ProjectZomboid64.g1.json` (4 GB fixed heap, 25 ms pause
goal, pretouch, no hsperfdata). Every run passes `--prop persistentVbo=true --prop
translucentTilesInChunkTexture=true --prop hotsaveStaged=false` explicitly, see the caveat.

| run | JRE | launcher JSON | fps mean | p50 | p90 | p99 | p99.9 | max | 1 %-low | < 240 fps | game thread | GPU | cpu | GC in window |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jvm-zulu-vbo-1 | Zulu 25.0.1 (C2) | stock, ZGC 3 GB | 508.5 | 1.6 | 3.1 | 7.7 | 15.2 | 52.6 | 131 | 3.7 % | 87 % | 95 % | 28 % | ZGC 3 cycles |
| jvm-graal-1 | GraalVM 25.0.3 | stock, ZGC 3 GB | 463.0 | 1.7 | 3.5 | 9.9 | 19.7 | 64.2 | 101 | 5.7 % | 89 % | 91 % | 36 % | ZGC 6 cycles |
| jvm-graal-g1-1 | GraalVM 25.0.3 | tuned G1 4 GB | 474.3 | 1.6 | 3.4 | 9.3 | 18.7 | 54.5 | 107 | 5.2 % | 85 % | 92 % | 39 % | G1 17 events, max 407 ms wall |
| jvm-zulu-g1-1 | Zulu 25.0.1 (C2) | tuned G1 4 GB | 508.7 | 1.6 | 3.0 | 7.3 | 16.7 | 49.1 | 137 | 3.4 % | 81 % | 96 % | 30 % | G1 10 events, max 333 ms wall |

- **GraalVM is 7–9 % behind HotSpot C2 on the desktop too**, on either collector, with a worse
  tail (p99 7.7 → 9.9 ms, 1 %-low 131 → 101). Its compiler threads show as process CPU 28 → 36–39 %.
  Same verdict as the laptop matrix; not adopted. The copy stays at `jre64_graal`.
- **Zulu + tuned G1 JSON is the best of the four**: the mean is GPU-bound at 96 % either way, but
  the tail is a little tighter than ZGC (p99 7.3 vs 7.7 ms, max 49 vs 53, 1 %-low 137 vs 131) and
  the game thread drops 87 → 81 % (no ZGC load barriers / concurrent cycles competing with it).
  Adopted: the game dir now runs Zulu with the G1 JSON; re-apply it from `config/launcher/` after
  a game update.
- **Caveat that cost two runs (jvm-zulu-1, jvm-zulu-2: 184 fps, p50 4.3 ms, game thread 99 %).**
  The ~500 fps numbers depend on `persistentVbo=true` and `translucentTilesInChunkTexture=true`,
  which are OFF by default (artifacts, 2026-09-19) and were only on through the maintainer's
  Optimizations-tab file `~/Zomboid/pzopt/options.ini`. That file was rewritten at 13:32 (now only
  `hotsaveStaged=true`) and the same route dropped to 184 fps; `u120-mine-1` (148 vs 558 fps) is the
  same effect earlier in the day. Forcing the two keys via `--prop` restores 508. One of them
  (almost certainly the persistent VBO mapping) is worth ~2.7x uncapped, so the artifact question
  is worth solving instead of leaving the key off. Comparisons across runs must check the
  `settings:` line in console.txt. Also: the launcher JSON had carried `-Dzomboid.steam=0` since
  an earlier run; steam=1 vs 0 made no difference (jvm-zulu-1 vs -2), the G1 JSON in the game dir
  keeps steam=0.

## 2026-09-20 (13:55–14:50): the black chunk squares were the light-info chunk gate, not persistentVbo

Question: why does `persistentVbo=true` (worth ~2.7x uncapped) draw black chunk-sized squares on
the left of the screen, and can it be fixed instead of left off. Rig built for it: `run.sh --shot-at
12` holds the camera 12 s into the spinning Rosewood route (`--flag route=S:450 --flag turn=90
--route-seconds 25`, max zoom, 240 cap unless stated, `--no-mangohud`, no dashboard) and captures the
screen twice 2 s apart; `harness/blacktiles.py` counts the 32 px tiles that are entirely black in a
run but drawn in the control (`bs-off-1`, everything default). All 19 runs, one change each:

| run | change vs defaults | fps mean | p99 | black tiles |
|---|---|---|---|---|
| bs-off-1 | control | 216.5 | 11.9 | 0 |
| bs-both-1 | persistentVbo + translucentTiles | 283.9 | 7.5 | 290 |
| bs-vbo-1 | persistentVbo | 226.2 | 11.2 | 225 |
| bs-vbofinish-1 | + glFinish before every map | 134.5 | 17.9 | 2 |
| bs-vboff-1 | + per-frame fence (rewrite after the drawing frame is done) | 221.7 | 11.4 | 284 |
| bs-vbolag2-1 | + frame fence, 2 frames of extra lag | 213.4 | 12.6 | 81 |
| bs-vbodelay-1 | + 40 us CPU park per map, no GPU sync | 191.3 | 11.7 | 9 |
| bs-vboslots4-1 | + 4 storage slots per buffer (reuse after 512 batches) | 215.5 | 11.7 | 303 |
| bs-vboflush-1 | + MAP_FLUSH_EXPLICIT instead of MAP_COHERENT | 208.1 | 11.9 | 147 |
| bs-vbostock-1 | persistentVbo with every other key at stock | 140.8 | 21.8 | 0 |
| bs-vbonobudget-1 | persistentVbo, bake/rebake/lighting budgets off | 214.4 | 14.4 | 237 |
| bs-vbonotrees-1 | persistentVbo, trees/windows out of the chunk texture | 192.3 | 12.3 | 197 |
| bs-vbonostream-1 | persistentVbo, parallel streamer / wake / hand-off off | 218.8 | 11.7 | 148 |
| bs-vbonodepth-1 | persistentVbo, parallelDepthMaps off | 204.3 | 14.8 | 128 |
| bs-vbonolightinfo-1 | persistentVbo, lightInfoChunkGate / OncePerFrame / occlusionSkip off | 200.5 | 12.4 | 1 |
| bs-gatefix-1 | persistentVbo, **gate fix** | 199.9 | 13.1 | 0 |
| bs-gatefix-both-1 | persistentVbo + translucentTiles, gate fix | 278.3 | 7.6 | 0 |
| bs-gatefix-both-2 | same, held at 20 s (no matching control; clean by eye) | 276.2 | 7.9 | n/a |
| bs-gatefix-uncap-1 | same, uncapped, uiRenderOffscreen | **511.7** | 6.7 | 0 |

- **The GPU buffer race hypothesis is dead.** Every synchronisation variant on the persistent
  mapping (per-frame fences, extra lag, four storage slots, explicit flush) left the squares; what
  reduced them were the things that slowed the render thread down (glFinish 2, a CPU-only park 9),
  i.e. timing, not memory. Both captures 2 s apart were identical every time: the black is baked into
  the chunk texture.
- **Cause: `lightInfoChunkGate`** (400 fps pass). In `prepareChunkForUpdating` a square whose light
  info had never been cached (fresh chunk whose lighting pass consumed the JNI dirty bit before the
  level's first bake) stayed `lightInfo == null`, failed the loop's null test and was left out of
  `squareFlags`, so the whole level baked black. Stock refreshes every square unconditionally.
  `persistentVbo` only shifts the render/lighting thread timing enough for the window to open often.
  Fix: the gated branch refreshes any square with null light info. 0 black tiles in every
  configuration afterwards, uncapped 511.7 fps mean / p99 6.7 ms / GPU 92 %: the experimental keys
  keep their whole gain.
- Left as opt-in diagnostics in `GLVertexBufferObject` (`persistentVboFrameFence`, `-FrameLag`,
  `-Slots`, `-Coherent`, `-DelayUs`, `-Finish`; a "persistent VBO:" counter line every 5 s with
  `instrument=true`). The 2026-09-19 "black building lot" (`artfix-opt120-2`) predates the gate and
  was not reproduced today; the 2026-09-19 `translucentTilesInChunkTexture` black floor rectangles
  did not show on this route either. The maintainer confirmed the fix in game the same afternoon and
  both keys are ON by default from this commit (the Optimizations tab labels lose "experimental");
  if either 2026-09-19 report comes back, `--shot-at` + `blacktiles.py` on a copy of the real save is
  the way to bisect it.

## 2026-09-20 (14:54–15:25): scene presets — night with/without torch, thunderstorm

New `run.sh --preset night-torch|night-dark|storm` (spinning Rosewood route, `pzopt.Scene` forces the
hour, weather and torch at world-ready; `harness/CLAUDE.md`). 240 cap, `--no-dashboard`, defaults
(persistentVbo + translucentTiles on). Runs `preset-*`.

| preset | fps mean | p50 | p99 | p99.9 | max | >33 ms | notes |
|---|---|---|---|---|---|---|---|
| night-dark (01:00, no light) | 282 | 3.3 ms | 10.4 | 17.9 | 47 | 1 | `night_strength=1.0` |
| night-torch (01:00, lit HandTorch) | 283 | 3.3 ms | 10.0 | 18.7 | 46 | 1 | beam visible live; no measurable cost |
| storm (save's hour, pinned STAGE_STORM values, strike every 6 s) | **83** | 10.1 ms | **43** | 81 | 571 | 40 | game thread 94 %, GPU 76 % |

- Night alone is free on this route (283 vs ~283 in daylight on the same build).
- **The thunderstorm is a 3.4x frame-time regression and the tail is the worst measured on this
  route**: 8156 chunk bakes per 1800-frame period vs 1526 in clear weather (`lighting=6535` flags vs
  1112: the storm's per-frame ambient/daylight changes dirty the chunk lighting continuously, and every
  lightning strike sets `dirtyRecalcGridStackTime=1` for ~100 frames), plus the rain FX pass. Game
  thread 94 % of a core, render thread 69 %, GPU 76 % (overlay) / 49 % (nvidia-smi): neither is at
  the wall, so this is a "fps < 240 and hardware not saturated" finding.
- GameProfiler A/B, uncapped, direct launcher (15:34, runs `storm-prof-stock` / `storm-prof-opt`,
  `docs/findings-scene-presets-2026-09-20.md` §3): stock 32 fps (p99 143 ms), optimized 70 fps
  (p99 39 ms; the probes cost ~8 %). Game thread 28.9 → 13.4 ms; the bakes are down to 1.4 ms but
  **`FBORenderCell.puddles` stays at 4.5 ms/frame (34 % of the game thread)** — stock
  `renderPuddles` re-filters, re-lights and re-packs every wet square on every level every frame,
  and the render thread re-uploads and redraws them (8.7 ms `buildStateDrawBuffer`, 4.4 ms waiting
  on the game thread). Not the lighting rebakes: the puddle pass is the storm's structural item.
  `sections.py` is per thread now (`--thread game|render`); it used to merge both recordings.
- Fixes (15:42–16:02, `docs/findings-scene-presets-2026-09-20.md` §4): JFR put 73 % of the render
  thread in the rain quads (`VBORenderer` 4 KB buffer, a flush every 28 quads); `vboBatchKb=1024` +
  `vboFastQuads` (new `VBORenderer` override) → submission 8.7 → 6.1 ms. `puddleCache`
  (`pzopt.PuddleCache`, `IsoPuddles` override, slot on `IsoChunk`): packed puddle vertices kept per
  chunk level, lights / jiggle / depth patched per frame → puddles 4.5 → 0.96 ms. Storm route
  uncapped: **83 → 131 fps** (p99 43 → 25 ms), game thread 13.4 → ~7 ms, GPU 71 %. Remaining: the
  rain particle path itself (~100k quads a frame at 5120x2160, walked twice on the game thread).
- Rain tiles + the 6 s rain freeze (16:20–17:00, `docs/findings-scene-presets-2026-09-20.md` §5):
  `rainTiles` (template once, one draw per screen cell) → desktop spinning storm 111 → 188 fps,
  laptop 120 km/h storm drive 84 → 106 (70 before today), laptop clear drive unchanged (~200 fps).
  The rain "vanishing" every ~6 s the maintainer saw was five 50-90 ms stalls per lightning
  strike: the re-bake budget's 3-frame cap released every flash-dirtied chunk texture in one
  frame. Lighting-only re-bakes now have `lightingRebakeBudget=8` / `lightingRebakeMaxFrames=30`:
  storm drive p99.9 57 → 12.5 ms, max 88 → 19, 0 frames over 33 ms (was 48); `lightingRebakeMs=100`
  tried and worse. Runs `storm120-rec` / `-spread` / `-lrb100` / `-lrb100b16` (recorded), `storm-tiles`
  / `storm-notiles`, laptop `lap-*`.
- Torch: the beam was visible on screen in every night-torch run (maintainer watching), with the
  default invisible bench player, and costs nothing measurable (283 vs 282 fps). The `--shot-at`
  captures never show it: torch and dark shots were pixel-identical after the 2 s stand-still hold, so
  the hold-and-capture rig is blind to the player light (lights must be judged live or from
  `--record`); a wrong afternoon was spent concluding the opposite from the shots. Also learned: the
  bench save's pistol has an always-on weapon light that `Scene` strips before `torch=on|off`, and an
  invisible player is what `LightingJNI.playerSet` receives as ghost mode (`visible=true` flag exists,
  off by default: the beam does not need it).
- First run (`preset-night-torch-1`) hung at the loading screen: `ClimateManager.forceDayInfoUpdate()`
  before the first climate tick NPEs every frame in `WAIT_WORLD`; removed, and a scene exception now
  rejects the run instead of looping.

## 2026-09-20 (15:15–15:31): laptop on AC, power profile x stock/optimized on the spinning route

Machine: the laptop again (`diego-flip`, an AYANEO Flip 1S DS: Ryzen AI 9 HX 370 / Radeon 890M, Mesa 26.2.3, 1920x1080),
this time **on AC** with `powerprofilesctl` set to each profile by the maintainer between pairs.
Every run: spinning Rosewood route (`--flag route=S:450 --flag turn=90 --flag zoom=max
--route-seconds 25`), `--prop uncappedFps=true`, `--option uiRenderOffscreen=true`, `--launcher
direct` (no Steam), `--game-profiler`, `--no-mangohud --no-dashboard`, tuned G1 launcher JSON
(`config/launcher/ProjectZomboid64.g1.json`, already installed there). Stock = `--prop
enabled=false` (the master switch: stock code path everywhere, harness only). One run per cell.
Runs `flip-spin-uncap-gp-ac-{perf,balanced,powersave}[-stock]-*`.

| profile | build | fps mean | mean | p99 | p99.9 | max | >33 ms | jitter | CPU (24 c) | GPU | game thread |
|---|---|---|---|---|---|---|---|---|---|---|---|
| performance | stock | 56.2 | 17.8 | 39.3 | 57.5 | 74 | 24 | - | 21 % | 24 % | 99 % |
| performance | optimized | **125.6** | 8.0 | 22.4 | 33.2 | 61 | 4 | 2.4 | 21 % | 42 % | 99 % |
| balanced | stock | 56.4 | 17.7 | 40.3 | 68.0 | 121 | 34 | - | 21 % | 25 % | 99 % |
| balanced | optimized | **124.9** | 8.0 | 22.3 | 33.9 | 62 | 4 | 2.5 | 21 % | 38 % | 99 % |
| power-saver | stock | 40.8 | 24.5 | 63.1 | 97.8 | 126 | 176 | - | 29 % | 19 % | 98 % |
| power-saver | optimized | **64.3** | 15.6 | 42.2 | 75.5 | 105 | 47 | 5.1 | 30 % | 20 % | 98 % |

(ms unless stated; jitter = overlay frame-to-frame; stock runs have no overlay log, so no jitter.)

- **performance and balanced are the same run** on this laptop, stock and optimized alike: the
  game thread is pegged at 99 % of one core either way, so balanced is not clocking the busy core
  down. power-saver is: optimized 125 → 64 fps, 1 %-low 41 → 22 fps, and the tail doubles.
- **Overrides: 2.2x on AC** (56 → 125 fps, p99 40 → 22 ms, spikes 24-34 → 4), 1.6x under
  power-saver, where the side threads (Lighting 78 %, four recalc workers ~10 % each) share the
  smaller power budget with the game thread.
- **Hardware not saturated in any cell** (the objective's own finding): ~20 % of 24 cores, GPU
  ≤ 42 % at 38-41 W. Single game thread is the wall, as on the desktop (~500 fps on this route),
  scaled down by the laptop's clock/IPC. GameProfiler on the slow frames (≥ 20 ms, ~41 per run):
  all of the extra time is `GameWindow.logic` (23 vs 4.7 ms) that its own sub-sections do not cover.
- GC is not it: `gc.log` in the route window shows five stop-the-world pauses of 0.1-17.6 ms; the
  ~300 ms entries `analyze.py` sums are the concurrent mark cycle (background threads).

Thermals over the same route windows (`sysmon.csv`; on this APU `gpu_c` is the edge sensor and
`gpu_w` the package draw, CPU included):

| profile | build | temp start → end (max) °C | package W | fps / W | GPU clock first 5 s → last 5 s |
|---|---|---|---|---|---|
| balanced | optimized | 66 → 72 (74) | 40.6 | 3.08 | 1256 → 2076 MHz |
| balanced | stock | 67 → 68 (70) | 38.4 | 1.47 | 1321 → 1476 |
| performance | optimized | 70 → 77 (77) | 40.5 | 3.10 | 1105 → 2070 |
| performance | stock | 70 → 72 (74) | 37.7 | 1.49 | 1342 → 1450 |
| power-saver | optimized | 63 → 64 (65) | 23.2 | 2.77 | 1536 → 1749 |
| power-saver | stock | 60 → 64 (65) | 20.3 | 2.01 | 1308 → 1525 |

- **No thermal throttling in any run**: peak 77 °C and the clocks rise over the route rather than
  sag. performance-opt started 4 °C warmer than balanced-opt (back to back) and matched it, so
  heat is not what equalised the two profiles; both sit at the same ~41 W package cap.
- power-saver is a **power cap, not a thermal one**: ~21 W, 12 °C cooler; that is the whole
  125 → 64 fps drop.
- **Efficiency**: the overrides roughly double frames per joule at the same package power
  (balanced 1.47 → 3.08 fps/W, performance 1.49 → 3.10); under power-saver 2.01 → 2.77. The
  optimized build on power-saver is still more efficient than stock on any profile, at 57 % of the
  power.
- 25 s is not steady state on a thin laptop; whether sustained play throttles needs the 100 s
  route on `performance` (the sysmon CSV already logs everything needed).

## 2026-09-20 (16:05–16:45): flicker of objects inside buildings, doors, windows, corpses — held re-bakes drew empty per-frame lists

Report (maintainer, normal play, clear weather): objects inside buildings, doors, windows and
corpses appear / disappear. Repro recipe from the maintainer: spawn where the south route ends
and spin. New harness flag `hold=N` (stay on the end square, `turn` keeps spinning) and a
metric, `harness/flicker.py` (pixels that change and revert within 3 frames of a `--record`),
runs `flick-*`, all `--flag route=S:450 --flag speed=90 --flag turn=90 --flag hold=10 --flag
zoom=1`, hold window analysed at `--scale 2560`, the game classes of the 16:01 install.

| run | keys | transient px/frame | what the heat map shows |
|---|---|---|---|
| flick-opt-1 | defaults | 26.2 | papers on the desks, the table beside the player, doors, wall objects |
| flick-stock-1 | `enabled=false` | 3.8 | the spinning player only |
| flick-norebake-1 | `rebakeBudget=0` | 28.1 | same as defaults |
| flick-g1cut-1 | cutawayInvalidateChanged/VisitPrefilter/Fast off, cutawayRadius=0, gridStackInterval=0 | 34.3 | same as defaults |
| flick-g2bake-1 | lightingRebakeMs=0 lightInfoChunkGate/OncePerFrame off, occlusionSkipLightingOnly=false, bakeBudget=0 lightingBudget=0 | 11.5 | edge shimmer only |
| flick-g3tex-1 | windows/translucentTiles/treesInChunkTexture off, persistentVbo=false | 670.7 | everything per-frame flickers (more objects per frame = more flicker) |
| flick-lb0-1 | `lightingBudget=0` | 25.4 | same as defaults |
| flick-lrb0-1 | `lightingRebakeMs=0` | 7.8 | edge shimmer only |
| flick-bb0-1 | `bakeBudget=0` | 22.4 | same as defaults |
| flick-fix-1 | fix, defaults | 11.5 (0.1 at `--scale 1280`; stock 0.0, broken 3.4) | edge shimmer only |

Frames of the table region (flick-opt-1, 29.43 s): fading table + lamp per frame -> opaque table
with the papers (fresh bake) -> table, lamp and papers gone for three frames -> back.

Cause (`docs/override-edits.md`, FBORenderCell entry of this evening): stock
`FBORenderLevels.NLevels.invalidate()` also empties the level's per-frame square lists (items,
obscuring objects, cutaway window frames, corpses, flies, attachments, puddles) outside
`performRenderTiles`, because stock always re-bakes in the same frame. A held re-bake
(`lightingRebakeMs=250`, `rebakeBudget=4`) drew the previous texture with those lists already
empty, so for the held frames the per-frame objects were nowhere. Group 3 was worse because with
windows, translucent tiles and trees per frame there is more per-frame content to lose.

Fix: keep the lists across invalidations when a hold is configured (they only change at a bake,
so they always match the texture on screen), clear them when a chunk object returns to the pool,
and never hold cutaway (2048) re-bakes (their per-frame draws re-test live flags). Cost on the
uncapped spinning route: `flickfix-u-1` 488.7 fps mean, p99 7.2 ms, ~15 % more bakes per period
(`jvm-zulu-g1-1` reference 508.7 / 7.3), GPU 96 % in both.

## 2026-09-20 (17:05–17:15): side-by-side video, stock vs optimized, 120 km/h thunderstorm drive

`harness/stitch-storm-sbs.sh` -> `docs/media/drive-120kmh-storm-stock-vs-optimized.mp4` (3840x810,
42 s, both panes aligned at the car's motion onset, each run's in-game overlay inset at full
resolution). Direct launcher (no Steam), `--flag weather=storm`, `--no-mangohud --no-dashboard`,
`--prop overlay=true overlayFont=Large`.

| run | build | cap | fps mean | p50 / p99 / p99.9 / max ms | >33 ms | GPU / game / render |
|---|---|---|---|---|---|---|
| `sbs-storm120-stock-1` | every pzopt render key off (the `u400show-stock` set + today's keys off) | 300 (framecap.ini won over `--option frameRate=244`; moot at 71 fps) | 71.5 | 13.1 / 67 / 87 / 103 | 42 | 96 % / 83 % / 84 % |
| `sbs-storm120-opt-1` | defaults incl. rain tiles, puddle cache, lighting re-bake spread | none | 268.7 | 3.3 / 8.8 / 12.7 / 19.9 | 0 | 98 % / 61 % / 97 % |
