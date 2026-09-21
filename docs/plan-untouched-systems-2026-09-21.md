# Untouched game systems that still hold optimizations (2026-09-21, evening)

A stock-take after the fog, storm-parity, tree and macOS passes: which parts of the game have no
`// pzopt:` edit yet, and what the last profiles say they cost. Sources: the game-thread JFR tree of
`u400-final-1` (19,091 samples, `docs/plan-500fps.md` §1), its render-thread tree (§5), the Louisville
horde runs (`docs/results.md`, `show-louisville-*`), the storm and fog findings, and the override list
(`src/overrides/`, 41 classes on `7292eb6`). Every class named under "no override" was checked for a
`pzopt` marker in `src/`; none has one.

## 1. Game thread, no override at all

| System | Share of the frame | The waste the tree shows | Why it matters |
|---|---|---|---|
| **Character update + animation** (`IsoPlayer`, `IsoZombie`, `IsoGameCharacter`, `AnimationPlayer`, `ActionContext`, `BodyDamage`) | **~21 %** on the spinning bench (player alone 9.5 %); the whole wall in Louisville (stock 23.7, optimized 31.7 fps, game thread 98 %, GPU 42 %, ~2,500 zombies) | `updateBoneAnimationTransform` re-parents every bone per track per frame; `CharacterVariableCondition.resolveValue` builds a `String` per transition test on every zombie every frame (`getValueString`); `BodyDamage.Update` runs at frame rate, not tick rate | Scales with the zombie count, so it is the only lever for hordes and matters more in real play than on the bench |
| **Per-object draw recording** (`IsoObject.render` → `IsoSprite.render` three overloads deep → `TextureDraw.Create`, `GenericSpriteRenderState`) | ~8 %, spread through `performRenderTiles` | Three virtual hops per sprite before `renderCurrentAnim_FBORender`; `SpriteRenderState.clear` (1.4 %) runs the *previous* frame's `TextureDraw.postRender` on the game thread inside `Core.StartFrame` | Pure overhead on every visible sprite; the `postRender` belongs on the render thread |
| **Lua UI** (`LuaJavaInvoker`, `UIManager.update`, `UIElement`) | ~8 % (draw 5.6 + update 2.7) even with `uiRenderOffscreen=true` | `Method.invoke` reflection per Lua→Java call, `tableget`; `UIElement.update` recurses into invisible subtrees every frame | Cache the resolved Java method per call site; skip hidden subtrees |
| **VisibilityPolygon2** | 2.7 % game thread **+ 9.5 % of the render thread** | Rebuilt from scratch every frame (`isInViewCone`, `lineSegmentsIntersects` per wall edge); the drawer calls `IsoDepthHelper.getChunkDepthData` per polygon vertex on the render thread | Inputs change only with the player square, facing and the wall set: memoise on those, cache the depth lookups per chunk |
| **Weather FX mask** (`WeatherFxMask.drawFxMask`) | 3.8 % with clouds active | Only `weatherMaskIdleSkip` exists. `IndieGL.bindShader` per wall sprite goes through `Lambda.capture` and a `Stacks` callback (1.3 %); the mask is redrawn every frame although it only changes with the camera and the cutaway set | Rebuild on camera / cutaway change only; direct shader bind |
| **`LightingJNI.checkLights`** | 2.6 % | Full light scan every frame from the game thread. The switch-power memo (`IsoLightSwitch` override) removed the per-light query cost, the scan itself has no dirty flag | Dirty flag from the light-switch / weather paths (`lightSwitchCheckFrames` already exists on the Java side) |
| **`VehicleEngine.updateWorldSounds`** | 0.9 % while driving | `emitAnimalFleeingWorldSound` calls `WorldSoundManager.addSoundRepeating` every frame | A per-second cadence is enough; trivial and exact |
| **Small exact fixes** | ~0.6 % | `FBORenderLevels.isTextureSizeValid` → `ImageUtils.getNextPowerOfTwoHW` recomputes a constant per chunk level per frame | One batch, no visual risk (the `isAnyAimKeyDown` hoist from the same list is done) |

## 2. Render thread (`main`): profiled once, never edited

Inclusive shares of its 3,687 route samples (`plan-500fps.md` §5): `RingBuffer.add` /
`prepareCurrentRun` 15 % (state-run batching of the draw list), `TextureDraw.run` →
`GenericDrawer.render` 34 % (vispoly drawer 9.5, weather particle drawer 7.1, `WorldItemAtlas` depth
drawer 6, model slots 4.5), `ShaderHelper.setModelViewProjection` 4.4 % (`Matrix4f.equals` per draw
element, 1.6 %), `ShaderUniformSetter.invokeAll` 3 % (uniform re-upload per state run),
`ChunkRenderShader` / `TileDepthShader.startRenderThread` 2.3 % (`ShaderProgram.setValue` per chunk
draw). At ~500 fps the game thread is 89 % busy, so ~11 % of it is waiting for this thread or the GPU;
the `VBORenderer` override (batch size, fast quads) is the only edit on this side so far. Candidates in
order: the vispoly depth cache (shared with §1), a dirty flag on the MVP instead of `Matrix4f.equals`,
uniform upload only when a value changed.

## 3. Touched, but the residual is the tail (the > 2.5 ms frames)

- **Chunk hand-off** (`IsoChunk.doLoadGridsquare` → `loadInMainThread`, 3.4 % mean, the 7–25 ms
  frames): the loot roll (`ScriptManager.FindItem` + `getLootType` per candidate item) and
  `RecalcAllWithNeighbour` still run on the game thread. Needs a time slice (N squares per frame) or
  the roll moved to the streamer thread with a hand-off.
- **Hot save**: one ~45 ms frame per `hotsaveIntervalSec` (`IsoMetaGrid.savePart`, `saveStringMap`,
  `Zone.saveData`). `hotsaveStaged` was rejected for cross-file consistency; the open design is a
  snapshot copy of the zone / string maps taken on the game thread and written by the save worker.
- **Cutaway per-square decision** (`shouldRenderBuildingSquare` 2.9 %, `checkExteriorWalls` 1.9 %):
  change detection and the visit prefilter exist, but the decision is still evaluated for every
  prepared chunk every frame. Cache per chunk level, invalidate on the `cutawayInvalidateChanged` set.
- **Lighting refresh** (`pzoptCacheLightInfo`, 1.9 %): budgeted, still one JNI crossing per square; a
  per-chunk-level call returning the block would remove it.
- **Rain**: `rainTiles` covers the draw, `rainSplashesFast` the idle-square RNG; the particle path
  itself (~100k quads a frame at 5120x2160, walked twice on the game thread) and `GameWindow.logic`
  in storms are still stock (`docs/findings-scene-presets-2026-09-20.md` §5).

## 4. GPU (structural)

The world composite: ~290 chunk-level textures drawn onto the offscreen buffer with per-pixel depth
writes, so no early-Z, ~72 Mpx of overdraw, ~0.6 ms of the ~1.9 ms GPU frame. A cached colour+depth
composite scrolled by the camera delta with only dirty chunk levels redrawn is the one GPU lever left
besides the bake count (`plan-400fps.md`, GPU section). Rendering the zoomed-out view below the 2.5x
supersample would cut fill 6x but changes the look; not planned unless the maintainer wants it.

## 5. Never measured

- **~3.9 of the process's 5.9 cores** live outside every Java thread: the FMOD mixer and stream
  threads, the NVIDIA GL worker under GLX, JIT and GC. No harness output attributes them; per-OS-thread
  CPU (`/proc/<pid>/task/*/stat` sampled beside sysmon) is the missing tool and costs no game change.
- **Scenes with no run**: base interiors with many lights, combat inside a horde (only the Louisville
  spin exists), the map screen, inventory / crafting UI open, multiplayer.
- **Pathfinding, collision, world streamer threads** sleep between jobs on every profile so far
  (< 0.5 % each); nothing to take unless a horde run shows otherwise.

## 6. Recommendation

Next pass: **character update and animation** (§1 row 1). It is the largest untouched block on the
bench, it is *the* wall in every zombie-dense scene (Louisville: game thread 98 %, GPU 42 %, exactly the
"fps < 240 and hardware not saturated" finding the objective calls out), and its three wastes have
exact, visual-risk-free fixes: a string-free `CharacterVariableCondition`, a bone re-parent memo while
the track set is unchanged, `BodyDamage.Update` on a tick cadence. Verify on `--preset louisville` as
well as the Rosewood bench, one key per run, adopt only when mean and p99 both move outside noise.
Then, in order: the small exact batch (§1 last row + `updateWorldSounds`), the vispoly memo (both
threads), the weather mask rebuild-on-change, the `checkLights` dirty flag, the render-thread state-run
trims (§2), the hand-off time slice and hot-save snapshot (§3), and last the composite cache (§4).
