# Plan: 500 fps locked on the uncapped spinning Rosewood route (2026-09-20 night)

Follows `docs/plan-400fps.md`. Same route and measurement rules: `--mode bench --flag zoom=max
--flag route=S:450 --flag turn=90 --route-seconds 25 --prop uncappedFps=true --no-dashboard
--no-mangohud --jfr --jfr-period 1 --option uiRenderOffscreen=true`, frame source is
`pzopt-overlay.out`, no `--game-profiler`, no builds or decompiles during a run. Tools:
`harness/gametree.py <run>` (game-thread call tree), `harness/attribute.py` (slow frames),
`--prop gpuSections=true` (GPU time per section, measurement runs only).

Starting point: `u400-final-1-20260920-125700`, 499 fps mean, p50 1.6 ms, p90 3.2 ms, p99 7.6 ms,
p99.9 15.1 ms, game thread 89 % busy, GPU 94 % busy. "Locked 500" means 2.0 ms per presented
frame at p99, which is where the GPU already sits, so this pass has to take time off both the
game thread and the GPU per frame, not one of them.

## 1. Where the game thread still spends its frame (u400-final-1, 19,091 samples on the route)

Inclusive shares of `GameWindow.frameStep`. Render side 57 %, logic side 39 %, lighting publish
4 %. The "override" column says whether any class in the subtree carries a `// pzopt:` edit.

| area | share | override | notes |
|---|---|---|---|
| performRenderTiles (record the world) | 30.6 % | partly | renderOneChunk 10.3, translucent 5.2, translucentFloor 2.9, vispoly 2.7, items 1.7, endFrame 1.7, moving 1.4, water 0.9 |
| IsoCell.update | 18.2 % | partly | ProcessObjects 12.4 (player 5.3, zombies 2.9, vehicles 2.0, animals 0.9), chunk hand-off 3.4 |
| MovingObjectUpdateScheduler.postupdate | 8.9 % | no | player 4.2, zombies 4.2: animation player, action state machine |
| Lua UI draw (UI FBO, 120 Hz) | 5.6 % | no | Kahlua main loop, `LuaJavaInvoker` reflection, `tableget` |
| renderWeatherFX (mask) | 3.8 % | idle skip only | drawFxMask 3.1, of which `IndieGL.bindShader` via lambda capture 1.3 |
| LightingThread.update (JNI publish) | 3.9 % | no | `LightingJNI.checkLights` 2.6 every frame |
| prepareChunksForUpdating | 3.6 % | yes | `shouldRenderBuildingSquare` 2.9 (hasFloorAtTopOfStairs 0.9) |
| runChecks | 3.6 % | partly | checkChunksWithTrees 1.9 (isAnyAimKeyDown via PZOptional 0.7), checkNewlyOnScreenChunks 1.4 |
| UpdateStuff | 2.9 % | no | VirtualVehicleManager 1.9 (`emitAnimalFleeingWorldSound` every frame 0.9) |
| UIManager.update | 2.7 % | no | UIElement.update recursion |
| updateChunkLighting (budgeted) | 2.0 % | yes | cacheLightInfo JNI 1.2 |
| checkExteriorWalls | 1.9 % | yes | per prepared chunk per frame |
| Core.StartFrame | 1.5 % | no | `SpriteRenderState.clear` 1.4 (postRender of the previous frame on the game thread) |
| Lua OnTick | 1.2 % | no | mod-free run; the game's own OnTick handlers |
| SoundManager.Update | 1.1 % | no | `FMODParameter.update` 1.0 |
| ObjectAmbientEmitters.update | 0.9 % | no | |

Everything below ~0.5 % is left out; the table covers ~93 % of the sampled frame.

## 2. Untouched code that is still hot

None of these classes has an override. Ordered by share, with the concrete shape of the waste
seen in the tree.

1. **Character update and animation** (`IsoPlayer`, `IsoZombie`, `IsoGameCharacter`,
   `AnimationPlayer`, `ActionContext`, `BodyDamage`), ~21 % combined. The player alone is 9.5 %.
   `updateBoneAnimationTransform` re-parents every bone per track per frame;
   `CharacterVariableCondition.resolveValue` builds a string per transition test
   (`getValueString`) on every zombie every frame; `BodyDamage.Update` runs at frame rate.
   This block scales with the zombie count, so it matters more in real play than on the bench.
2. **Per-object draw recording** (`IsoObject.render`, `IsoSprite.render` three levels deep,
   `TextureDraw.Create`, `GenericSpriteRenderState`), ~8 % spread through performRenderTiles.
   Each sprite goes through three `render` overloads before `renderCurrentAnim_FBORender`.
   `SpriteRenderState.clear` (1.4 %) still runs the previous frame's `TextureDraw.postRender`
   on the game thread inside `Core.StartFrame`.
3. **VisibilityPolygon2** 2.7 %. Recomputed from scratch every frame (`isInViewCone`,
   `lineSegmentsIntersects` per wall edge); the inputs change only with the player square,
   facing and the wall set, so it can be memoised on those.
4. **Lua UI** ~8 % (draw 5.6 + update 2.7). `LuaJavaInvoker.call` reflection and `tableget`
   dominate. Candidates: cache the resolved Java method per call site, skip `UIElement.update`
   for invisible subtrees (the recursion visits every element every frame).
5. **Weather FX mask** 3.8 %. Only the idle skip exists. `IndieGL.bindShader` per wall sprite
   goes through `Lambda.capture` and a `Stacks` callback (1.3 %); the mask draws every visible
   wall every frame although the mask only changes with the camera and the cutaway set.
6. **LightingJNI.checkLights** 2.6 %. Runs the full light scan every frame from the game
   thread; needs a dirty flag from the light-switch / weather paths (we already have
   `lightSwitchCheckFrames` on the Java side).
7. **VehicleEngine.updateWorldSounds** 0.9 %: `emitAnimalFleeingWorldSound` calls
   `WorldSoundManager.addSoundRepeating` every frame while driving; a per-second cadence is
   enough.
8. **Small exact fixes** (one batch, no visual risk):
   `FBORenderLevels.isTextureSizeValid` → `ImageUtils.getNextPowerOfTwoHW` recomputes a
   constant per chunk level per frame (0.6 %); `checkTreeTranslucency` queries
   `isAnyAimKeyDown` through a `PZOptional` lambda per chunk with trees instead of once per
   frame (0.7 %); `IndieGL.bindShader` lambda capture (1.3 %, item 5).

## 3. Touched, but the residual is the tail

- **Chunk hand-off** (`IsoChunk.doLoadGridsquare`, 3.4 % mean, the 7 to 25 ms frames).
  `loadInMainThread` still does the loot roll (`ScriptManager.FindItem` and `getLootType` per
  candidate item) and `RecalcAllWithNeighbour` on the game thread. Locking any target needs this
  time-sliced (N squares per frame) or the roll moved to the streamer thread with a hand-off.
- **Hot save** (`IsoMetaGrid.savePart`, `saveStringMap`, `Zone.saveData`): one ~45 ms frame
  per `hotsaveIntervalSec`. `hotsaveStaged` was rejected for cross-file consistency; the
  alternative is a snapshot copy of the zone/string maps taken on the game thread and written
  on the save worker.
- **Cutaways** (`shouldRenderBuildingSquare` 2.9 %, `checkExteriorWalls` 1.9 %): change
  detection and the visit prefilter exist, but the per-square decision is still evaluated for
  every prepared chunk every frame. Cache per chunk level, invalidate on the same change set
  as `cutawayInvalidateChanged`.
- **Lighting refresh** (`pzoptCacheLightInfo`, 1.9 %): budgeted, still a JNI call per square.
  A per-chunk-level JNI call returning the block would remove the per-square crossing.

## 4. Never profiled

- **The render thread.** `RenderThread` is overridden only for the hand-off. Its own share of
  a frame (GL state changes, `IndieGL` replay, shader binds, VBO uploads) has no call tree in
  any run. At 499 fps the game thread is 89 % busy, so ~11 % of it is waiting on the render
  thread or the GPU. First step: `gametree.py --thread` on the render thread of u400-final-1.
- **The GPU composite** (~0.6 ms of the ~1.9 ms GPU frame): ~290 chunk-level textures drawn
  with per-pixel depth writes, so no early-z, ~72 Mpx of overdraw. The structural item from
  plan 400 (cached colour+depth composite scrolled by the camera delta, only dirty chunk
  levels redrawn) is the only GPU lever left besides bake count.
- **Other situations.** Every adopted key was measured driving or spinning in Rosewood.
  Base interiors with many lights, combat with a horde, rain, and the map screen have no run.

## 5. Order of work (one bench run after each)

1. Small exact fixes batch (§2 item 8). Expected ~2 %; confirms the measurement floor.
2. Render-thread JFR tree of u400-final-1 (no code change). Decides whether §4 or §2 is next.
3. Character animation and action state machine (§2 item 1): string-free
   `CharacterVariableCondition`, bone re-parent memo when the track set is unchanged,
   `BodyDamage.Update` on a tick cadence. Verify on a horde save, not only on the bench.
4. VisibilityPolygon2 memo on (square, facing, wall set).
5. Weather mask: rebuild only when camera or cutaway set changed; direct shader bind.
6. `LightingJNI.checkLights` dirty flag.
7. Chunk hand-off time slice (§3), then the hot-save snapshot.
8. Composite cache (structural, GPU).

Adopt a key only if the route mean and p99 both improve outside noise (two runs) and a verify
run on the real-save copy shows no artefact.
