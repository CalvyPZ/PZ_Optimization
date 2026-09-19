# Plan: instant boot and instant load

Goal (Diego, 2026-09-19): launch → main menu under 1 s, Continue → world under
1 s, on a Crucial T705 (14 GB/s). The disk is not the limit anywhere below: every
phase is single-thread CPU or a serial wait. This plan lists every boot and
load step with its measured cost, what to change, and the measured result per
step. Procedure per step: change, `scripts/build.sh && scripts/test.sh`,
`scripts/pzopt.sh reinstall`, one recorded run
(`harness/run.sh --label <step> --mode drive --flag route=E:60 --route-seconds 8
--record --no-dashboard --prop instrument=true --launcher direct`), then
`harness/loadtime.py` against the previous step, `harness/analyze.py` for the
drive-window frame tail (no regression allowed), and the contact sheet
(`harness/loadsheet.sh`) of the recording. Profiles come from a `--jfr
--jfr-period 1` run read with `harness/phaseprof.py` (new; boot or load window,
inclusive/leaf rankings per thread).

Starting point (run `load-final`, 2026-09-19 16:08): boot 7.35 s (plus ~0.7 s of
JVM start before the first log line), load 6.53 s.

## Boot: launch → main menu (7.6 s in the profiled run `boot-jfr-1`)

Main thread, from the JFR (inclusive wall share):

| # | step | cost | what is wrong | change | key |
|---|---|---|---|---|---|
| B1 | `TileGeometryManager.init` → `TileGeometryFile.parseFile` | 1.78 s | `ScriptParser.stripComments` is quadratic: `StringBuilder.replace` per comment on a multi-MB string (1.56 s); `parseTokens` re-substrings the remainder per token | linear single-pass comment stripper with the same nesting rule; `parseTokens` with an index instead of substring | `scriptParserFast` |
| B2 | `FMODManager.init` (system create + 12 bank files) | ~1.6 s, native, first thing on the main thread | serial before anything else | run on a thread started at the top of `mainThreadInit`; joined before the first FMOD user (the `sound` scripts in `ScriptManager.Load`) and the volume calls moved to the join | `fmodAsync` |
| B3 | `ScriptManager.Load` | 1.87 s | `Item.DoParam`: 442 `equalsIgnoreCase` in a chain per parameter (0.85 s); `readBlock`/`parseTokens` 0.5 s (B1 fixes part) | dispatch table for `DoParam` (later, large mechanical edit) | |
| B4 | `LuaManager.LoadDirBase` (shared + client) | 0.85 s | serial compile (0.37 s) + execution | precompile every Lua file on a pool at boot (`pzopt.LuaPrecompiler`), `LuaCompiler.loadis` returns the cached prototype; also removes the compile from the server Lua at Continue (0.9 s, phase A) and from `OnLoadMapZones` | `luaPrecompile` |
| B5 | `GameWindow.LoadTexturePack` ×7 | 0.55 s | per-byte synchronized reads through `PositionInputStream` | read the pack header from a byte array | `texturePackFastRead` |
| B6 | `MainScreenState.enter`, `Event.trigger` (Lua UI creation) | 0.6 + 0.7 s | Lua | after B4; Lua-bound | |
| B7 | `ZomboidFileSystem.init`, fonts, translations | 0.5 + 0.2 + 0.2 s | serial dir walks and parses | run under the FMOD overlap (B2) or on threads | |
| B8 | JVM start → first log line | ~0.7 s | class loading + verification from the jar | AppCDS needs the overrides in a jar (CDS refuses a non-empty directory on the classpath); install as `pzopt.jar` ahead of the game jar | later |
| B9 | animation sets, server Lua (needed at Continue) | 0 at boot | | preload `AnimationSet("player")` and precompile `media/lua/server` on boot threads so L1 and L8 disappear from the load | `preloadAnimSets`, `luaPrecompile` |
| B10 | the async file pool during `GameWindow.init` | idle for 4 s | only `GameWindow.logic` (per frame) pumps it; frames start at the menu | `pzopt.BootPump` thread pumps every 3 ms from the start of `init`; pool 10 wide at boot, 8 at load; paused while fonts load; `ModelManager.create` moved to right after the scripts so the 2,209 animation imports are queued 2 s earlier | `bootPump`, `bootFileThreads`, `earlyModels` |
| B11 | animation import (`FileTask_LoadAnimation`: jassimp on 2,209 .X files) | 14–17 thread-s per boot | parsing text model files for what ends as a map of keyframes | `pzopt.AnimClipCache`: clips written after a stock import, read on later boots through `pzopt.CachedAnimationTask` (2.9 thread-s) | `animClipCache` |
| B12 | texture pack pages (`FileTask_LoadPackImage`, PNG decode of 174 pages, 12 thread-s including throttle sleeps) | off the critical path now (D = 0.01 s) | pure-Java PNG decode + the 50 MB upload budget | raw-pixel cache would be ~2 GB; not worth it while D is 0 | |

## Load: Continue → world ready (6.5 s)

| # | phase | cost | what is wrong | change | key |
|---|---|---|---|---|---|
| L1 | C7 `IsoPlayer.<init>` → `AdvancedAnimator.init` → `AnimationSet.Load("player")` | 1.1 s | JAXB parse of every AnimSets XML on the loader thread | preloaded at boot (B9); static data, cached in `AnimationSet.setMap` | `preloadAtBoot` |
| L2 | C2/C3 `IsoMetaGrid.load` → `loadZone` → `IsoMetaCell.getChunk` → `LotHeader.getZombieIntensityForChunk` → `MapFiles.getLotHeader` | 0.8 s | one `String.format` + hash lookups per chunk instead of one per cell (same bug as `MapCollisionData`) | `IsoMetaCell` resolves the lot header once per cell | `loaderCpuFixes` |
| L3 | `BuildingRoomsEditor.checkBuildingAndRoomIDs` ×3 (`Basements.beforeLoadMetaGrid`) | 0.85 s | `roomList.indexOf(roomDef)` per room: O(rooms²) per cell | identity index map per cell, same checks and messages | `loaderCpuFixes` |
| L4 | C3 Lua `OnLoadMapZones` (`objects.lua` per map) | 1.5 s | Kahlua compile + execution of large table literals | compile from the boot precompile cache (B4); execution stays | `luaPrecompile` |
| L5 | A `LuaManager.LoadDirBase("server")` on the game thread | 0.5–0.7 s | compile-bound | B4/B9 | |
| L6 | C1 tile definitions (`LoadTileDefinitions` ×7) | 0.6–1.1 s | serial parse + sprite registration | parse the seven files on workers, register in file order | later |
| L7 | C6 initial 361 chunks | 0.7–0.9 s | 100 ms sleep polls + recalc contention | wake instead of poll | later |
| L8 | E/F fade to black 350 ms, `IngameState.enter` boundary recalc 0.4 s | 0.75 s | cosmetic fade; serial recalc | skip the fade under the flag; recalc on the pool | `noLoadFade` |
| L9 | C4 `MapCollisionData.init` | 0.2–0.4 s | remaining per-chunk JNI | later | |

## Results

One recorded drive run per step (route E:60, `--no-dashboard`, direct
launcher); boot = launch to the harness pressing Continue, load = Continue to
the first world frame with the player on a square. Drive-window frame tail
(p99 / p99.9, no frame over 33 ms) was unchanged in every run.

| run | boot | load | step |
|---|---|---|---|
| `load-final` (start) | 7.35 s | 6.53 s | before this plan |
| `boot-jfr-1` | 7.57 s | 7.19 s | profiling run, JFR at 1 ms |
| `load-b1` | 6.57 s | 6.67 s | B1 linear `stripComments`/`parseTokens` |
| `load-b2` | 5.20 s | 6.62 s | B2 FMOD init on a thread (1.4 s, main thread waited 0) |
| `load-l23` | 5.02 s | 6.66 s | L2 lot-header memo, L3 room index, L8 no fade: C2 0.87→0.58, C3 1.52→1.29, F 0.41→0.05, but D (asset wait) 0.33→0.85: the pool only starts at the menu, so a shorter boot moved the wait |
| `load-b10` | 5.14 s | 5.98 s | B10 boot pump, 14 file threads: D 0.36 |
| `load-b10b` | 7.55 s | 4.51 s | + `ModelManager.create` early: animations done at boot (D 0.01) but 16 cores saturated, Lua load 1.6× slower, font callback NPE on the pump thread |
| `load-b10c` | 6.04 s | 5.19 s | pump paused during font loads, boot pool 10 threads |
| `load-b4` | 5.58 s | 5.11 s | B4 Lua precompile: 1,419 files on 14 threads, 1,115 hits, 0 misses |
| `load-l1` | 5.72 s | 4.54 s | L1 animation sets parsed on a boot thread (0.9 s): C7 0.35→0.05 |
| `load-b11a` | 5.59 s | 4.89 s | B11 animation clip cache, first boot: 2,161 imports written (89 MB under `~/Zomboid/pzopt/anims`), 14.3 thread-s of jassimp |
| `load-b11` | 5.66 s | 4.02 s | B11 cache hit: 2,161 hits, 2.9 thread-s; the loader's own serial work (A + C1–C6 ≈ 3.9 s) is now the whole load |
| `load-jfr2` | 5.30 s | 3.97 s | profiling run: boot main thread = scripts 1.76 (DoParam 0.95), texture-pack headers 0.64, Lua 1.8, fonts+translations 0.4; loader = map-zone Lua 1.0, tile defs 0.66, MapCollisionData 0.45, zones 0.5 |
| `load-b5a` | 5.79 s | 3.92 s | B5 pack index, first boot (scans and writes 7 indexes) |
| `load-b5` | 6.26 s | 4.01 s | B5 hit, with the 48 MB item field dump on (B3 reference dump) |
| `load-b3` | 5.86 s | 3.94 s | B3 `DoParam` switch, item dump on: dumps identical apart from object addresses and identity-hashed tag-set order |
| **`load-b35`** | **5.00 s** | **4.03 s** | B5 + B3, clean (no dump): the adopted state. JVM start → first log line 0.74 s on top (`harness/jvmstart.py`) |
| `load-cds-a` / `load-cds-b` | 5.23 / 5.37 s | 3.92 / 3.88 s | B8 AppCDS with the overrides in `pzopt.jar` ahead of the game jar (classpath without `.`): 71 MB archive created and used, JVM start → first line still 0.75 s, boot within noise. Reverted to the loose install; class loading is not what the pre-log 0.75 s is |

## Where it stands (2026-09-19, 17:20) and what is left

From process start: **0.75 + 5.00 s to the main menu** (was 0.75 + 7.35),
**4.03 s from Continue to the world** (was 6.53). The drive-window frame tail
never moved (p99 ≈ 5 ms, p99.9 ≈ 10 ms, no frame over 33 ms in any run). The
sub-second targets are not reachable with class overrides; what remains is
serial interpreter and parser work on the main and loader threads:

Boot, main thread (`load-jfr2` profile, adjusted for B3/B5): Kahlua execution
of the shared+client Lua (0.6 s), `OnGameBoot` handlers (0.65 s) and the main
menu's Lua UI (0.63 s); the item scripts after the switch (~0.9 s:
`readBlock`, `CreateFromToken`, `Item.Load`); fonts + translations (0.4 s);
`ZomboidFileSystem.init` directory walk (0.4 s); tile-geometry parse (0.3 s);
display + GL context (0.35 s); the 0.75 s before the first log line (natives,
display creation; not class loading, see the CDS runs).

Load, loader thread (3.9 s serial, `harness/loadtime.py`): A 0.45 (server Lua
execution on the game thread), C1 1.0 (tile definitions: parse 0.4, sprite
and texture registration 0.3 with 109 render-thread round trips of 1.6 ms),
C2 0.6 (meta-grid loaders, 8 threads), C3 0.9 (`OnLoadMapZones`: Kahlua
executing every map's `objects.lua`, then zone registration), C4 0.15,
C6 0.7 (361 chunks: 5.4 thread-s of recalc at 15 ms per chunk on 8 workers,
against 5 ms in play; CPU contention with the file pool, lighting thread and
meta-grid loaders, not disk: chunk reads total 0.29 s), C7 0.05, F 0.05.

Next tier, in order of expected gain per risk, all deeper than an override
hook: (1) a Java reader for `objects.lua` that builds the Kahlua tables
directly (C3, ~0.5 s); (2) tile definitions parsed on workers with sprite
registration replayed in file order, sprite IDs are already deterministic
(`getSpriteID`) (C1, ~0.4 s); (3) throttle the texture-page decode during C6
so the recalc workers get the cores (C6, ~0.3 s); (4) fonts and translations
on a thread under the FMOD overlap (boot, ~0.3 s); (5) NIO walk in
`ZomboidFileSystem.init` (boot, ~0.25 s); (6) a persisted Kahlua state is the
only way past the ~2 s of boot Lua and is out of scope for overrides.
