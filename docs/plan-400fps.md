# Plan: 400 fps locked on the uncapped spinning Rosewood route (2026-09-20)

Route: `--mode bench --flag zoom=max --flag route=S:450 --flag turn=90 --route-seconds 25
--prop uncappedFps=true --no-dashboard --no-mangohud --jfr --jfr-period 1`. Frame source is the
in-game overlay log (`pzopt-overlay.out`), never MangoHud. `--game-profiler` is not used: the
GameProfiler probes cost ~8 % of the game thread themselves (Pool.alloc, PerformanceStatistic).
`--prop overlay=true` (the visible HUD) costs 0.9 % and is left off on measured runs.
Tools: `harness/gametree.py <run>` (inclusive call tree of the game thread from the JFR samples,
`--root`, `--callers`), `harness/attribute.py` (slow-frame attribution).

Target: 2.5 ms per presented frame at p99, not just on average.

## Where the game thread stands (u400-base-1 / u400-uifbo-2)

| run | change | fps mean | frame mean | p50 | p90 | p99 | p99.9 | game thread | GPU busy |
|---|---|---|---|---|---|---|---|---|---|
| u400-base-1 | Config defaults, UI drawn every frame | 272.8 | 3.7 ms | 2.8 | 6.3 | 13.2 | 27.8 | 98 % | 80 % |
| u400-uifbo-1 | + stock option `uiRenderOffscreen=true` (UI FBO, 120 Hz) | 383.1 | 2.6 ms | 1.8 | 4.7 | 12.2 | 21.8 | 91 % | 91 % |
| u400-uifbo-2 | same, 1 ms JFR, no HUD | 374.3 | 2.7 ms | 1.9 | 4.8 | 12.3 | 23.4 | 93 % | 89 % |
| u400-it1-1 | + cutawayInvalidateChanged, soundZoneCache, lightInfoOncePerFrame | 442.2 | 2.3 ms | 1.7 | 3.8 | 9.5 | 18.3 | 91 % | 92 % |
| u400-it2-nofx-1 | + hotsaveStaged; weather FX pass OFF (measurement only) | 496.1 | 2.0 ms | 1.5 | 3.4 | 8.8 | 16.5 | 93 % | 91 % |
| u400-it3-fx50-1 | weather FX buffers at 50 % (run invalid: my decompile+build ran during it) | 392.1 | 2.6 ms | | | | | | |
| u400-it3-fx50-2 | same, clean: a wash, so the FX cost is not fill rate; default back to 100 | 445.3 | 2.2 ms | 1.7 | 3.8 | 9.4 | 18.6 | 92 % | 91 % |
| u400-it4-1 | + chunkHandoffDivisor=8, occlusionSkipLightingOnly (8283 rebuilds skipped) | 454.2 | 2.2 ms | 1.7 | 3.7 | 9.0 | 18.4 | 89 % | 92 % |
| u400-it6-lrb1000-1 | lightingRebakeMs=1000 A/B: no change (bakes are driven by create/redraw, not lighting) | 453.7 | 2.2 ms | 1.7 | 3.7 | 9.5 | 18.6 | 94 % | 89 % |
| u400-it7-bb4-1 | bakeBudget=4 A/B: no change | 455.7 | 2.2 ms | 1.7 | 3.6 | 9.0 | 16.7 | 89 % | 93 % |
| u400-it8-1 | + cutawayVisitPrefilter (140k of 154k wall visits skipped) | 466.1 | 2.1 ms | 1.7 | 3.4 | 8.6 | 18.6 | 90 % | 93 % |
| u400-it9-1 | + lightInfoChunkGate (390k chunk-level square loops skipped) | 500.8 | 2.0 ms | 1.6 | 3.1 | 7.7 | 16.8 | 89 % | 93 % |
| u400-it10-ui60-1 | stock option uiRenderFPS=60 A/B (player setting is 120): +2 %, within noise; not forced | 509.9 | 2.0 ms | 1.6 | 3.0 | 7.8 | 16.8 | 88 % | 94 % |
| u400-it11-gpusec-2 | invalid: build.sh had failed (masked by a pipe), reinstall left the game dir stock, no harness, no click-to-start | | | | | | | | |
| u400-it11-gpusec-3 | measurement: gpuSections without the per-chunk pairs (light, 490 fps) | 489.7 | | | | | | | |
| u400-final-1 | final build, all adopted keys (confirms it9) | 499.1 | 2.0 ms | 1.6 | 3.2 | 7.6 | 15.1 | 89 % | 94 % |
| u400-it5-gpusec-1 | measurement: `gpuSections=true` (600 timestamp queries a frame, 203 fps, numbers perturbed) | 203.0 | 4.9 ms | | | | | | |

GPU per frame by section (it5, microseconds per frame, pairs per frame): chunks 805 to 1381
(the per-chunk draw/bake loop, 293 chunk levels a frame) of which bake 581 to 1142 (1.6 to 4.1
bakes a frame, ~300 us GPU per chunk-level bake), fx 140 to 190, translucent 44 to 102, moving
20 to 43, items 13 to 38, water 0 to 36, translucentFloor 10 to 34. Bakes are the GPU wall; the
counters say ~600 to 750 bakes a second on this route, mostly lighting-drift re-bakes (held to
one per 250 ms per texture) and "redraw" (chunk came on screen / neighbour loaded).

All runs use the maintainer's `~/Zomboid/pzopt/options.ini` (persistentVbo=true,
translucentTilesInChunkTexture=true), consistently, so they compare with each other but not
with the Config-defaults runs of earlier days. Never build or decompile while a run is going.

Finding 1: the stock offscreen-UI option is worth +40 % uncapped (it was a wash at the 240 cap,
which is why it was not adopted). It is a Display option the player already has; harness runs pass
`--option uiRenderOffscreen=true`.

Finding 2: with the UI out of the way the GPU is at ~90 % at 375 fps (frame's draw replay measured
with a GL timer query, nvidia-smi says 82 %). Every game-thread saving from here on only turns
into fps if GPU work per frame also drops. Re-bakes of chunk textures are both CPU (recording)
and GPU (draw). Correction to older notes: the world is not rendered at 12800x5400; `Core`
sets a 12800x5400 orthographic projection onto a screen-sized (5120x2160) viewport of the
offscreen FBO, and the composite samples the screen-sized region. Fill per pass is 11 Mpx.
The weather FX pass costs ~11 % of the frame (u400-it2-nofx-1) but halving its buffers changes
nothing (u400-it3-fx50-2): its cost is draw calls and state, not pixels. `pzopt.GpuSections`
(`--prop gpuSections=true`) measures GPU time per section with timestamp queries.

Game thread, inclusive shares of a frame (u400-uifbo-2, 20k samples):

| area | share | notes |
|---|---|---|
| performRenderTiles (record the world) | 31.5 % | renderOneChunk 14 %, translucent 7 %, vispoly 2.1 %, items 1.6 %, moving 1.3 %, endFrame 1.4 % |
| per-frame render checks | 16 % | prepareChunksForUpdating 4.5 (JNI lightInfo 2.7), calculateOccludingSquares 3.3, runChecks 2.9, cutaway visit 2.2, updateChunkLighting 1.9, checkExteriorWalls 1.6 |
| weather mask (clouds active) | 3.1 % | mask FBO draw every frame; consumed by the cloud layer |
| UI (FBO at 120 Hz) | 5.7 % | Lua draw of the UI elements |
| SpriteRenderState.clear | 1.6 % | postRender of the previous frame's draws, on the game thread |
| IsoCell.update | 16 % | player 4.5 (inventory weight recomputed per frame by walk speed, moodles, thermoregulator), zombies 2.3, vehicles 1.7, animals 0.9, chunk hand-off 3.7 |
| MovingObjectUpdateScheduler.postupdate | 7 % | zombie animation state machines 3.4, player 3 |
| UI update, sound, vehicles, Lua OnTick | ~7 % | sound zone parameters 0.7 (80x80 zone scan per parameter per frame) |
| LightingThread.update | 3 % | JNI lighting publish |

Tail: the >50 ms frame every run is the hot save (`IsoMetaGrid.savePart`/`saveStringMap`/
`Zone.saveData` on the game thread, once per `hotsaveIntervalSec`=30). The 7 to 25 ms frames are
chunk hand-off (`doLoadGridsquare`: recalc, loot roll, erosion) plus cutaway visits and new-chunk bakes.

GPU per frame, final build (it11-gpusec-3, microseconds per frame): composite 555 to 612 (the
~290 visible chunk-level textures drawn onto the offscreen buffer with the chunk shader, which
writes per-pixel depth so there is no early-z; ~72 Mpx of overdraw a frame), bake 266 to 546
(1.0 to 2.0 bakes a frame), fx 93 to 165, translucent 66 to 107, water/items/moving/
translucentFloor 20 to 50 each. That is ~1.2 to 1.5 ms of the ~1.9 ms GPU frame; the rest is the
UI FBO, the offscreen-to-screen composite and clears (unmeasured). The composite is the next GPU
lever and it is structural: a cached world composite (colour + depth) scrolled by the camera
delta with only dirty chunk levels redrawn.

Video: `harness/stitch-triple-hdr.sh` -> `docs/media/rosewood-spin-uncapped-stock-vs-optimized-vs-all-hdr.mp4`
(AV1 10-bit HDR). Its recordings ran with MangoHud hidden (`--env MANGOHUD_CONFIG=no_display`):
the Steam launch options inject `mangohud` on every launch, which every measured run above
carried as a constant cost (same build 273 -> 391 fps without the HUD).

## Where it ends (2026-09-20 night)

Mean 273 -> ~500 fps, p50 2.8 -> 1.6 ms, p90 6.3 -> 3.1 ms, p99 13.2 -> 7.7 ms. Not locked: ~13 %
of frames are still above 2.5 ms. Those are the chunk-streaming frames (hand-off with the loot
roll's `ScriptManager.FindItem` + `getLootType` per candidate item, new-row bakes, cutaway data
recreation for new chunks), the UI FBO refresh frame (every 4th frame at 120 Hz) and the hot save
(one ~45 ms frame per 30 s, `hotsaveIntervalSec`). Locking 400 needs the streamer's main-thread
work (`doLoadGridsquare`) off the game thread or time-sliced, and the GPU composite cache above.

## Work list (one bench run after each)

1. `cutawayInvalidateChanged` (FBORenderCutaways): the visit re-flags every cutaway square each time it
   runs and invalidates (2048) every chunk that holds one, so those chunk textures re-bake every frame
   while moving. With `fboRenderChunk` the texture only reads the target flag, so invalidate only chunks
   where some square's target flag actually changed. Exact.
2. `soundZoneCache` (ParameterZone): the 80x80 zone scan depends on the listener's integer square only;
   reuse the value while the square is unchanged (re-scan at most every 30 frames in case zones change).
3. `lightInfoOncePerFrame` (FBORenderCell + IsoChunk stamps): `cacheLightInfo` is a JNI call per square
   and the same square is asked twice per frame (prepare + lighting refresh). Skip the second call.
4. Hot save: stagger the ancillary save parts over successive drains instead of one 55 ms frame, or
   raise the interval. (Open.)
5. Next candidates by size: `calculateOccludingSquares` when only off-screen levels are dirty,
   `checkChunksWithTrees` (1.6 %), player inventory weight memo (0.9 %), `SpriteRenderState.clear` on
   the render thread (1.6 %), zombie `CharacterVariableCondition` string lookups (0.9 %).
6. Structural (not this pass): overlap render-command recording with the next frame's logic; render the
   offscreen buffer below 2.5x for the GPU wall.
