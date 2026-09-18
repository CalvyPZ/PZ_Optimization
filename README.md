# PZ_Optimization

Performance work on Project Zomboid itself — the Java game, not a Lua mod.

Build 42. Since 2026-09-18 the **native Linux** depot is installed:
`/games/steamapps/common/ProjectZomboid/projectzomboid/` (jar, `ProjectZomboid64`,
`ProjectZomboid64.json`), user dir `~/Zomboid`. The Windows/Proton layout is
still supported by `scripts/pz-env.sh` (detected from the launcher binary).

## Why this is possible without patching the jar

`ProjectZomboid64.json` ships this classpath:

```json
"classpath": [".", "projectzomboid.jar"]
```

`.` precedes the jar, so a loose `.class` file under the install directory
**shadows** the same class inside `projectzomboid.jar`. Overrides are drop-in
and reversible by deleting the file — the shipped jar is never modified, and
its checksum stays intact.

`vmArgs` in the same file is editable, so `-javaagent:` instrumentation is
also available if class shadowing proves too blunt.

The jar is compiled but **not obfuscated** (class-file version 69 = Java 25).

## Decompiled source: `decompiled/`

The game's own code is already decompiled into `decompiled/`, laid out as a
package tree (`decompiled/zombie/iso/IsoChunk.java`), one file per top-level
class with inner classes inlined. Read it there instead of decompiling again.

- 3,407 classes / ~730k lines / 24 MB: `zombie.*` (3,078), `generation.*`,
  `fmod.*`, `se.krka.kahlua.*` (the Lua VM), `astar.*`, `N3D.*`.
- Not included: the third-party libs bundled in the fat jar (`org/`, `com/`,
  `gnu/trove`, `io/`, `kotlin/`, `oshi/`, `okhttp3/`, `imgui/`, `jassimp/`).
  jdtls resolves those from the jar itself (see `.classpath`).
- Produced by `scripts/decompile.sh` (CFR 0.152, 16 package jobs 10-wide, ~45 s).
  Re-run it after a game update. `decompiled/` is gitignored; the script is
  the source of truth.
- `decompiled/cfr-summary.txt` lists the methods CFR could not fully
  reconstruct: 16 are emitted with goto-style fallback (`PolygonalMap2.findPath`,
  `IsoGridSquare.renderMinusFloor`, `KahluaThread.luaMainloop`,
  `Core.loadOptions_OLD`, ...) and 2 have no body at all
  (`CompressIdenticalItems.areItemsIdentical`,
  `ChooseGameInfo.readModInfoAux`). For those, read the bytecode:
  `unzip -p projectzomboid.jar zombie/inventory/CompressIdenticalItems.class > /tmp/x.class && javap -c -p /tmp/x.class`.

For a one-off class outside the game packages:
`cfr <path>/Foo.class` (wrapper at `~/.local/bin/cfr`, jar at
`~/.local/share/java/cfr.jar`, needs `jdk-openjdk`).

### Code navigation

`.project` / `.classpath` / `.settings/` make the repo an Eclipse JDT project
with `decompiled/` as the source root and `projectzomboid.jar` as a library,
so **jdtls** (Java LSP) gets full type resolution: go-to-definition, find
references, call hierarchy across the game code. The Java builder is
intentionally not enabled — nothing here should compile the decompiled tree.

Setup (Arch/CachyOS):

```sh
paru -S jdtls                                            # AUR; Eclipse JDT.LS, runs on the system JDK
claude plugin install jdtls-lsp@claude-plugins-official  # Claude Code LSP bridge for .java
```

Optional, for the game's Lua side (`media/lua`, 1,395 files) —
`lua-language-server` is already installed, only the plugin is missing:
`claude plugin install lua-lsp@claude-plugins-official`.

## Layout and workflow

| Path | What |
|---|---|
| `src/overrides/zombie/iso/` | **Not committed** (decompiled game code). Our copies of the game classes we shadow (`IsoChunk`, `WorldStreamer`): Vineflower output (`scripts/regen-overrides.sh`) plus the edits listed in `docs/override-edits.md`, marked `// pzopt:` |
| `src/pzopt/pzopt/` | New helper classes: build guard (`Overrides`, `BuildInfo`), settings (`Config`), recalc pool (`RecalcPool`, `OrderedPublisher`), streamer wake-up (`StreamerWake`), instrumentation (`Stats`), harness driver (`Harness`, `Parity`) |
| `scripts/build.sh` | Compile `src/` against the jar with `--release 25`; checks every stock class member still exists; writes `pzopt/build-info.properties` |
| `scripts/pzopt.sh` | `install` / `uninstall` / `reinstall` / `status` / `check` against the game directory; never touches the jar; records what it wrote in `pzopt-installed.txt` |
| `scripts/test.sh` | Unit tests in `tests/` (no game needed) |
| `scripts/accept.sh` | build → reinstall → parity gate |
| `harness/run.sh` | One hands-off game run: bench save reset from a template, auto-continue, scripted route, auto-quit, logs collected to `harness/runs/`. Needs the Steam launch options set to `harness/steam-launch.sh %command%` (env/MangoHud injection; no-op outside a run). `--record` saves a screen recording of the run; `--mode drive` spawns a vehicle on the nearest road and drives it with cruise control |
| `harness/steam-launch.sh`, `harness/sysmon.sh` | Steam launch wrapper (reads `~/Zomboid/pzopt-launch.env` written per run); CPU/GPU utilization sampler (nvidia-smi + /proc) |
| `scripts/pz-env.sh` | Detects the install layout (native `…/projectzomboid/` + `~/Zomboid`, or Windows/Proton) for every script |
| `harness/analyze.py`, `compare.py`, `parity.py`, `simulate.py` | Summaries, before/after comparison with noise floor, parity diff, streamer-loop model |
| `harness/attribute.py`, `sections.py` | JFR game-thread attribution per frame (`--jfr` runs); GameProfiler section report (`--game-profiler` runs) |
| `harness/dashboard.py` | Builds `docs/benchmark-progress.html` (+ `.json`) from every run directory: status vs the driving target, frame-tail / chunk-latency / utilization charts, plan progress, full table |
| `docs/plan-driving-frame-time.md` | Current plan and performance target for driving frame time (2026-09-18 review) |
| `config/mangohud-benchmark.conf` | Full MangoHud telemetry profile for benchmark CSV logs |
| `harness/baseline/` | Stock numbers and the stock parity capture the gate compares against |
| `tools/StaticAudit.java` | Bytecode reachability audit of static fields (`docs/recalc-static-audit.md`) |
| `docs/` | Audit and findings |

Runtime settings live in `pzopt.properties` in the game directory (`parallel`,
`workers`, `wake`, `instrument`, `dev`; see `Config.java`). The overrides
disable themselves (stock behaviour, clear log line) when the game revision
differs from the one they were built for.

### Full MangoHud benchmark reporting

Use the repository profile for a benchmark run so the CSV includes frame
timing, CPU/GPU load and clocks, temperatures, power, memory, VRAM, process
memory, throttling, and runtime metadata:

```sh
harness/run.sh --label stock-full-mh --mode bench --quit-after 90 \
  --mangohud 90 --mangohud-config config/mangohud-benchmark.conf
python3 harness/analyze.py harness/runs/stock-full-mh-*
```

`--mangohud-config` temporarily edits only the selected profile to set the
run duration and output directory, then restores it on exit. The captured CSV
is copied into the run directory as `mangohud.csv`; the normal analyzer still
uses its `frametime` and `elapsed` columns for route-window statistics.

## The grid computation

When a chunk streams in, `IsoChunk.loadInWorldStreamerThread()`
(`zombie/iso/IsoChunk.class`) walks every square in the chunk —
8×8 squares per level, across `minLevel..maxLevel` — and runs four sequential
passes over that grid:

1. `sq.RecalcProperties()` per square, plus 3×3 null-filling
2. a rain/roof column pass that marks `haveRoof` and clears `exterior`
3. `sq.RecalcAllWithNeighbours(true, chunkGetter)` per square — the expensive
   one: each call recalculates the square against its full 3×3×3 neighbourhood
   (`ReCalculateAll`, `doGridNav`, plus the four diagonal N/S × W/E fixups)
4. a pass setting `propertiesDirty = true` per square

## What already runs off the main thread

Do not re-solve these; the game threads them today:

| Thread | Class |
|---|---|
| Lighting | `zombie/iso/LightingThread` |
| Chunk streaming + the grid recalc above | `zombie/iso/WorldStreamer` |
| Chunk object reuse | `zombie/iso/WorldReuserThread` |
| Chunk save | `zombie/iso/ChunkSaveWorker` |
| IsoRegion flood fill | `zombie/iso/areas/isoregion/jobs/JobChunkUpdate` |

## The two blockers

The grid recalc is already off the render thread, but it cannot use more than
one core, for two independent reasons:

1. **`WorldStreamer` is a single thread.** `WorldStreamer.java` constructs
   exactly one `new Thread(ThreadGroups.Workers, ...)` and starts it. Every
   chunk in the load queue is recalculated one after another.

2. **`IsoChunk.chunkGetter` is a mutable static singleton.**
   `private static final ChunkGetter chunkGetter;` is stashed with
   `IsoChunk.chunkGetter.chunk = this` for the duration of pass 3 and cleared
   afterwards, guarded by `assert (IsoChunk.chunkGetter.chunk == null)`.
   That static is what makes two chunks recalculating concurrently unsafe —
   it is shared mutable state on the hot path.

## Constraints any change must respect

- **Pathfinding registration stays on the main thread.** `IsoChunk` gates
  `PolygonalMap2.instance.addChunkToWorld(this)` /
  `PathfindNative.instance.addChunkToWorld(this)` behind
  `Thread.currentThread() == GameWindow.gameThread || Thread.currentThread() == GameServer.mainThread`.
  Parallel recalc must not reach that call.
- **Multiplayer.** Shadowed classes change client behaviour only; anything
  that alters save format or network payloads is out of scope.
- **Reversibility.** Every override must be removable by deleting a file.
