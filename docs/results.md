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
