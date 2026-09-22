# Plan: the zombie simulation on all cores (2026-09-22)

Scene: `harness/run.sh --preset louisville` (downtown Louisville, ~2,000 zombies, `see_all`, uncapped), the
maintainer's horde case. After the 2026-09-22 pass (`docs/override-edits.md`: strong re-bake budget,
creation-first bakes, `animBonesParallel`, `vehicleCull`) run `lou-final2` is 32.2 fps (p50 30 ms), GPU 46 %,
machine CPU 26 % of 16 cores, and the game thread is still 97 % busy. Every further frame has to come off that
one thread. This plan says what is on it, what can leave it and how, in the order of gain per unit of risk.

## 1. Where the game thread's 31 ms go (lou-final2, 100 Hz stack profile over the route)

| Share | ms | What | Parallel? |
|---|---|---|---|
| 12.1 % | 3.8 | `IsoPlayer.updateLOS`: the loop over every moving object (9.8 % self: distance, casts, visibility flags), `spotted` 1.8 % | yes, read pass + serial apply (§3.1) |
| 11.4 % | 3.5 | zombie `postUpdateAnimating`: `ActionContext.update` 5.8 % (transition conditions: string / boxed compares over the character's variables), `AdvancedAnimator.update` 2.6 %, the remaining `updateModelSlot` tick 1.5 %, `updateAnimPlayer` (model-less zombies) 0.9 % | yes, per character (§3.2) |
| 11.8 % | 3.7 | `renderMovingObject`: `IsoZombie.render` → `drawModel` 6.6 % (skin palette, lights, the model draw call record), `renderShadow` 2.4 % | prep yes, submission no (§3.3) |
| 12 % | 3.7 | lighting JNI square reads (`getSquareDirty` / `getSquareLighting` per square per pass, the budget queue and its pre-pass flush) | per chunk, with deferred effects (§3.4) |
| 7.1 % | 2.2 | `IsoZombie.updateInternal`: `separate` 2.4 %, `IsoGameCharacter.update` 2.1 % (state machine 1 %), `updateEmitter` 1 %, deferred movement 0.4 % | no (§3.5, reduce instead) |
| 1.1 % | 0.3 | `AnimBatch.flush` (the bone math already on 8 workers; was 9 % inline) | done |
| 5 % | 1.6 | chunk bakes | GPU-side, budgeted already |
| 3 % | 0.9 | `IsoWorld.sceneCullZombies` (sort of every zombie with a model by distance) | yes, trivial (§3.6) |
| rest | | cutaways, translucent pass, UI, chunk map, ECS, GC | — |

Per zombie per frame the game thread does: update (state machine, separation, sounds), postupdate
(action-context transitions, animator, animation tick, bone math → workers), LOS from the player's side, and
render prep. The four rows at the top are ~47 % of the frame; their parallel parts add up to roughly 30 % of
the frame (10 ms), which is the ceiling of this plan: ~32 → ~45 fps on this scene at the same GPU load.

## 2. What makes this hard, and the rules that follow

Stock's simulation is one thread by construction. The hazards found while moving the bone math, all of which
recur in every phase below:

1. **Static scratch objects.** `L_*` holder classes (`AnimationPlayer`), `static final Vector2 tempo`,
   `ModelInstance`'s `gc*` matrices, `AnimationTrack`'s `L_updateDeferredValues`. Any method reached from a
   worker must use thread-local scratch (the override pattern: instance fields + `ThreadLocal.withInitial`).
   `tools/StaticAudit` (written for the recalc pass) lists the static fields a call graph reaches — run it on
   every method a phase moves and fix every mutable static it prints before the first run.
2. **Pools.** `zombie.util.Pool` is thread-local per stack and releases to the owner's stack under a lock, so
   cross-thread release is legal but contended; `PerformanceStatistic`'s `AtomicDouble` counters are bumped on
   every alloc / release by every thread. `Lambda.predicate / capture / forEachFrom` are pooled objects: one
   in a per-bone or per-square loop is a hidden allocation with shared atomics (the `isBoneReparented` find:
   5 % of the game thread waiting). Rule: no `Lambda.*` in a hot loop that moves to a worker.
3. **Global toggles read by the moved code.** `PerformanceSettings.interpolateAnims` is flipped around every
   model-less character's animation update and read by the keyframe sampler; that is why the bone batch runs
   after the loop, not overlapped. Look for the same pattern (a static set, work, static restored) around any
   code a phase moves; overlap only when there is none.
4. **Events.** Track start / loop / finish listeners, `ActionContext.reportEvent`, Lua `OnZombieUpdate`-style
   hooks, `WorldSoundManager.addSound`, pathfinding requests, `Rand` (a global, order-dependent generator).
   Anything that fires an event or rolls the RNG stays on the game thread, in the original order. A phase that
   moves code must split it into "compute" (workers) and "apply" (game thread, original order).
5. **Cross-entity reads.** Grapple, reanimated-player copies, attachments and the render read other
   characters' bones and positions. Either the batch excludes those entities (what `AnimBatch` does) or the
   join lands before the first cross-entity reader. A torn matrix is a visible glitch; a one-frame-old one is not.
6. **Determinism.** Parallel compute must not change results. Every phase ships with a checksum rig
   (§4.2) that compares the serial and parallel paths over the same route before it becomes a default.

## 3. Phases

Each phase is one Config key (default off until its checksum and visual rigs pass), one override diff kept to
hooks, the logic in a `pzopt` class, documented in `docs/override-edits.md`, measured on the Louisville
preset (uncapped) and checked for no regression on the Rosewood spin route and the 120 km/h drive.

### 3.1 `losParallel`: the player's line-of-sight pass (12 %, expect −7 %)

`IsoPlayer.updateLOS` walks every moving object and, per object, computes the distance, the type casts,
`chrCurrentSquare.isCouldSee / isCanSee(playerIndex)`, `couldSeeHeadSquare / canSeeHeadSquare`, then does
the mutating part: alpha targets, `TestZombieSpotPlayer → spotted` (RNG, target, path requests), the stats
counters, `spottedList`. Caveat found while planning: `JNILighting.bCouldSee()` calls the lazy `update()`
first — the per-square JNI read with its side effects (`invalidateLevel`, cutaways, `LightDirt`), i.e. the
visibility getters are not pure until §3.4 exists.

- Refresh pass (game thread, serial, cheap): `lighting[playerIndex].update()` on the current square and head
  square of every object (one lazy read per zombie; most are already fresh this pass). After §3.4 this pass
  becomes a parallel batch of its own.
- Compute pass (workers): over the object list, fill parallel arrays `dist[i]`, `couldSee[i]`, `canSee[i]`,
  `kind[i]` (zombie / player / animal / other), `skip[i]` from the now-fresh `vis` fields. Reads only.
- Apply pass (game thread, same order as stock): the existing loop body with the reads replaced by the arrays.
  `spotted` and everything else stays exactly where it is.
- Override: `IsoPlayer` (8.5 k lines; regen-able like `IsoZombie`). Hook: `updateLOS` calls
  `pzopt.LosPass.compute(objectList, this)` first, then the loop reads the arrays.
- Safety: the compute reads `IsoMovingObject` fields the update loop already finished writing (LOS runs from
  `IsoPlayer.update`, which the scheduler runs after the zombies' updates in the same bucket order — check the
  bucket order for the player; if a zombie later in the list has not updated yet, stock reads its old
  position too, so the parallel read is no different).
- Rig: `--prop devLosChecksum=true` logs a hash of (`spottedList`, `numVisibleZombies`, `closestZombie`,
  every zombie's target) per frame; identical between `losParallel=false/true` over the Louisville route.

### 3.2 `actionEvalParallel`: action-context transitions and the animator (8 %, expect −5 %)

`ActionContext.update → evaluateCurrentStateTransitions → CharacterVariableCondition.passes` resolves
every transition's operands against the character's animation-variable table (strings, boxed numbers) every
frame for every zombie. `AdvancedAnimator.update` then advances the anim layers and may fire events.

Two steps, the first alone is worth most of it and is single-threaded:
1. **Skip unchanged evaluations.** Version-stamp the character's variable table (`AnimationVariableSource`
   set / clear paths) and the action state; when neither changed since the last evaluation and no timer
   condition is pending, the transitions cannot pass differently: skip `evaluateCurrentStateTransitions`.
   Most idle zombies change nothing for many frames. Override `ActionContext` (small) + the variable table.
2. **Evaluate in parallel, apply serially.** Compute pass: for every eligible zombie, evaluate the transitions
   of its current state into a result (`nextState` or none) reading only that character's variables. Apply
   pass: the game thread performs the state changes in the original order (`changeState`, events). Excluded:
   characters whose transition conditions read another entity (audit the condition types: `CharacterVariable`
   is self-only; any `Lua` condition type stays inline).
- Rig: per-frame hash of every zombie's action state + animation state names; identical.

### 3.3 `charRenderPrepParallel`: character render preparation (12 %, expect −5 %)

`IsoZombie.render → IsoSprite.renderActiveModel → SpriteRenderer.drawModel` builds the per-model render data
on the game thread: `AnimatedModelInstanceRenderData.initMatrixPalette` (the skin matrices into the palette),
`ModelInstance.updateLights`, attachments, then records the draw. The palette and lights are per character;
the record must be in draw order.

- Compute pass (workers, after `AnimBatch.flush` and `sceneCullZombies`, before `renderMovingObjects`): for
  every character that will be drawn this frame, fill its render data (palette, lights, shadow data) into
  its own `ModelInstanceRenderData` slot. This is the render data the draw call copies today.
- Submission (game thread): `drawModel` finds the data ready and only records the command.
- Override: `ModelInstanceRenderData` / `AnimatedModel$AnimatedModelInstanceRenderData` (the `init*`
  methods split into compute + record), `FBORenderCell.renderMovingObjects` hook. Audit `ModelInstance`'s
  static `gc*` scratch (thread-local) and the `SpriteRenderer` state (must not be touched by the compute
  pass).
- Rig: `--record` parity (`parity-judge.py`) + a per-frame hash of the palettes.

### 3.4 `lightingReadParallel`: the per-square lighting reads (12 %, expect −8 %, highest risk)

`JNILighting.update → updateFBORenderChunk` reads two JNI calls per square (`getSquareDirty`,
`getSquareLighting`) and updates the square's Java-side light fields, for every dirty on-screen chunk level,
either in the render phase (`lightingBudget` chunks a frame) or in the pre-pass flush.

- Compute pass (workers, one chunk level per task): the JNI reads and the per-square field updates
  (`lightInfo`, `cacheVertLight`, `darkMulti`, `lightLevel`, `vis`, the `LightDirt` accumulator — all per
  square or per chunk).
- Deferred effects (recorded per task, applied by the game thread after the join): `invalidateLevel(z, 32)`
  (per chunk; could stay in the task since a task owns its chunk level, but keep it deferred at first),
  `PuddleCache.lightsChanged`, `FBORenderCutaways.squareChanged`, `checkRoomSeen`, `Meta.dealWithSquareSeen`,
  `LightDirt.markStrong`.
- The unknown: whether the native side tolerates concurrent readers (`getSquareDirty` may read-and-clear a
  bit in a shared word). Test before writing the batch: a throwaway `--prop devLightingReadRace=true` that
  reads every square of every dirty level twice from two threads and compares with a serial read over the
  route; any mismatch kills the phase (fall back to §3.4b: keep the reads serial but move only the Java-side
  delta work, which is small).
- Rig: identical `pzopt-lighting` checksums (per-square light fields hashed per pass) serial vs parallel,
  the black-tile still rig, `blackframes.py` on a recording.

### 3.5 Zombie `update` itself: reduce, do not parallelize (7 %)

`IsoMovingObject.separate` (2.4 %) asks the square grid for neighbours and walls per zombie per frame;
`updateEmitter` (1 %) ticks FMOD parameters; the state machine (1 %) runs the AI. These touch the shared
grid, the sound system and each other's positions (separation is pairwise). Sharding the cell by region
with a two-phase commit would need every cross-shard interaction (separation, grapple, group manager,
world sounds, pathfinding, Lua hooks, RNG order) rewritten; the gain is 7 % of the frame. Not worth it.
Single-thread reductions instead: `separate` for zombies whose square did not change and with no moving
neighbour can reuse last frame's result; `updateEmitter` for zombies beyond audible range every N frames.

### 3.6 Small ones

- `IsoWorld.sceneCullZombies` (3 %): the sort of `zombieWithModel` by distance can run on a worker in
  parallel with `IsoWorld.updateWorld`'s tail; or sort only when the player moved a tile / every 4 frames.
- `IsoZombie.spotted → isVehicleBetween`: done (`vehicleCull`).
- GC: eight G1 young pauses of 20-45 ms in a 25 s route (the `p99.9`). The horde allocates: audit with JFR
  allocation profiling (`--jfr`), target the top sites (boxed `Float` / `String` in condition evaluation,
  §3.2 removes most).

## 4. Infrastructure the phases share

### 4.1 One worker pool

`pzopt.AnimBatch` becomes the generic per-frame batch (`pzopt.FrameBatch`): `begin(kind)`, `submit(task)`,
`flush()` = run on N daemon workers plus the calling thread, join. Same wake mechanism (a generation counter
under one monitor; ~50 µs wake latency, fine for 2-5 ms batches). `Config.animBonesThreads` → `frameThreads`
(default 8 on 16 cores; `clampWorkers` keeps it ≤ cores − 1; on the Dell / low-end profile 2). One batch at a
time; the game thread never blocks inside a batch on anything but the join.

### 4.2 Determinism rig

`--prop devSimChecksum=true`: once per frame after `postupdate`, hash every zombie's (id, x, y, z, target id,
action state, animation state, alpha) and every square light field touched, into `pzopt-sim.out` (one line per
frame). `harness/simdiff.py a b` compares two runs' lines; the route is deterministic enough between two runs
of the same build (`start=`, `population=max`, the save's clock) that the first divergence frame points at the
phase. Every phase's key is default off until `simdiff` says identical over the Louisville route.

### 4.3 Static-state audit before every phase

`tools/StaticAudit` over the moved methods' call graph (it exists for the recalc pass, `docs/recalc-static-
audit.md`); every mutable static it lists becomes thread-local in the override or the method stays inline.

### 4.4 Measurement hygiene

Runs right after a peer session's `update-*` / `release-updater` queue job showed the game's own GPU time
per frame tripled (a leftover instance or the Steam UI on the GPU). Before a run that decides anything, the
queue should wait for `nvidia-smi` utilization < 15 % for 3 s (a preflight in `queue.sh`, not written yet) —
until then, re-run any Louisville result with GPU > 60 %.

## 5. Order, cost, exit criteria

| # | Phase | Est. work | Gain (frame) | Exit criterion |
|---|---|---|---|---|
| 1 | §3.2 step 1: skip unchanged transition evaluations | 0.5 day | −3-4 % | simdiff identical; Louisville +1.5 fps |
| 2 | §3.1 `losParallel` | 1 day | −7 % | LOS checksum identical; +3 fps |
| 3 | §4.1 generic batch + §4.2 simdiff | 0.5 day | — | rigs in `harness/` |
| 4 | §3.3 `charRenderPrepParallel` | 1-2 days | −5 % | parity-judge parity; +2 fps |
| 5 | §3.2 step 2: parallel transition evaluation | 1 day | −2 % | simdiff identical |
| 6 | §3.4 race test, then `lightingReadParallel` | 2 days | −8 % | race test clean; light checksums identical; black-tile rigs 0 |
| 7 | §3.5 / §3.6 reductions, GC audit | 1 day | −3 % | p99.9 under 100 ms on the route |

Target after 1-6: game thread ~65 % of today's work → ~45 fps on the Louisville preset at GPU ~60 %; the
game thread stops being the wall around there and the GPU (5120x2160, 2,000 animated models) takes over,
which is the objective's definition of done for this scene. What is explicitly out: sharding the zombie
update across threads (§3.5) — it is the one item whose cost is not bounded.
