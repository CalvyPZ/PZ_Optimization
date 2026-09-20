# Scene presets: night, torch, thunderstorm — findings (2026-09-20, 14:54–15:25)

What was asked: bench presets for night with the player flashlight on and off, and for a
thunderstorm. What came out of testing them: night and the torch cost nothing on the spinning
Rosewood route; a thunderstorm costs 3.4× the frame time with neither CPU nor GPU saturated, and
is the largest unexplained frame-time gap measured on this route so far.

Setup for every run: `harness/run.sh --preset <name> --prop instrument=true --no-dashboard`
(spinning game-thread route `route=S:450 turn=90 zoom=max`, 25 s requested / 31 s driven), desktop
5120x2160, NVIDIA GL 4.6 (615.71.09), launcher steam, 240 fps cap, defaults of commit b0d4fe6
(persistentVbo and translucentTilesInChunkTexture on). Frame numbers are the in-game overlay log
over the route window (`analyze.py`, `overlay:` block). Runs under `harness/runs/preset-*`.

## 1. Numbers

| run | preset | fps mean | p50 | p90 | p99 | p99.9 | max | notes |
|---|---|---|---|---|---|---|---|---|
| preset-night-dark-4-20260920-152119 | night-dark | 282 | 3.5 ms | 4.9 | 10.4 | 17.9 | 47 | no `--shot-at` |
| preset-night-torch-7-20260920-152006 | night-torch | 283 | 3.5 ms | 4.9 | 10.0 | 18.7 | 46 | no `--shot-at` |
| preset-night-dark-2-20260920-150254 | night-dark | 282 | 3.5 ms | 4.6 | 9.6 | 19.9 | 269 | `--shot-at 12` (the 269 ms is the capture) |
| preset-night-torch-4-20260920-151139 | night-torch, `visible=true` | 284 | 3.5 ms | 4.6 | 9.4 | 19.8 | 263 | `--shot-at 12` |
| preset-storm-1-20260920-152349 | storm | **83** | **12.1 ms** | 16.1 | **43.3** | 81.2 | 571 | `--shot-at 12`; 40 frames > 33 ms |

Reference on the same build and route in daylight, clear weather: ~279–283 fps (`bs-defaults-1`,
`docs/results.md` 13:55 section).

Utilization in the storm run (`sysmon` + overlay over the route window): game thread 94 % of a
core (p10 86, p90 100), render thread 69 %, process 381 % of one core = 24 % of the machine, GPU
76 % busy by the overlay's timer query / 49 % by nvidia-smi, GPU clock pinned at 600 MHz and
36 W. Nothing is at the wall.

## 2. Findings

**F1 — Night is free.** Forcing 01:00 (`night_strength=1.0`, `time_of_day=1`) changes nothing on
this route: 282 fps, p99 10.4 ms, the same as daylight. The night darkening is a shader-side
multiplier, not extra work per square.

**F2 — The player torch is free.** A lit `Base.HandTorch` in the primary hand, cone sweeping with the
90°/s turn, registered in the native lighting every pass (`jniTorches` in the `torch check` console
line): 283 vs 282 fps, tails within noise. The beam was visible on screen in every night-torch run
(maintainer watching).

**F3 — A thunderstorm is a 3.4× frame-time regression and the tail is the worst on this route.**
83 fps mean, p50 10.1 ms, p99 43 ms, 40 frames over 33 ms in 31 s. Where the work is, from the
periodic `translucent pass per frame` line of the storm run vs `preset-night-dark-4`:

| counter per 1800-frame period | clear (night-dark-4) | storm | ratio |
|---|---|---|---|
| chunk bakes | 1526 | 8156 | 5.3× |
| `lighting` dirty flags | 1112 | 6535 | 5.9× |
| `redraw` flags | 567 | 2481 | 4.4× |
| `create` flags | 176 | 1114 | 6.3× |
| `objectAdd` flags | 170 | 1050 | 6.2× |
| `cutaways` flags | 95 | 910 | 9.6× |
| budgeted lighting rebakes | 9393 | 6669 | — (budget saturated, `held` 13185) |

Reading: the storm's climate values move every frame (the `WeatherPeriod` case-3 ambient / daylight /
cloud overrides interpolate continuously, and every lightning strike sets
`dirtyRecalcGridStackTime = 1` for ~100 frames), which marks chunk lighting dirty across the whole
view; `lightingRebakeMs=250` / `rebakeBudget=4` then re-bake chunk textures at the budget ceiling all
the time, on top of the rain FX pass. `objectAdd` / `create` ×6 says the rain also adds per-square
objects (puddles/splashes) that trigger bakes. This is the "fps < 240 and hardware not saturated"
case the objective names: game thread at 94 % but the machine at 24 %, GPU at 76 %.

**F4 — The `--shot-at` capture rig is blind to the player light.** Torch and dark captures are
pixel-identical after the 2 s stand-still hold before the capture, even though the beam is on screen
during the route. Judge lights live or from `--record`, never from the shots. Half the runs of the
afternoon (night-torch-2/3/5/6, night-dark-1/3, both `-stock-1` runs) were spent chasing the beam
in screenshots; wrong verdict, corrected by the maintainer.

**F5 — Harness details that bit.** (a) `ClimateManager.forceDayInfoUpdate()` before the first
climate tick NPEs (`currentDay` null); an exception inside `WAIT_WORLD` repeats every frame and the
game sits on the loading screen with the tips cycling (preset-night-torch-1). Removed; a scene
exception now rejects the run. (b) The bench save's character holds a pistol with an always-on
weapon light; `Scene` strips every light item before `torch=on|off`. (c) An invisible bench player is
what `LightingJNI.playerSet` receives as ghost mode; the torch draws anyway, so presets keep the
player invisible and `visible=true` stays opt-in. (d) The screenshot itself is a ~260 ms frame
(`Core.TakeFullScreenshot`); never compare `max` between runs with and without `--shot-at`.

## 3. What to do next

1. Profile the storm: `harness/run.sh --label storm-jfr-1 --preset storm --prop instrument=true
   --no-dashboard --jfr`, then `harness/gametree.py` on `pzopt.jfr`. Expect the game thread in chunk
   lighting rebakes (`FBORenderChunk`) and the rain FX pass; confirm the split before touching
   anything.
2. Candidate trims, in order: (i) coalesce lighting dirty flags while the climate floats change
   smoothly — a rebake per chunk per N ms of *ambient* change, not per frame; (ii) do not let a
   lightning flash dirty the grid stack for 100 frames when the flash is a global multiplier the
   shader already applies; (iii) measure the rain FX pass alone (`--flag thunder_secs=0` and a
   `weather=rain` value that pins precipitation without the ambient movement) to separate FX cost
   from rebake cost.
3. Add the storm preset to the dashboard as its own series; compare only storm with storm.
