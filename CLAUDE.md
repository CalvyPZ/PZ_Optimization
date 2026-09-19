# PZ_Optimization

Class overrides for Project Zomboid Build 42 (Java, LWJGL/OpenGL) that improve chunk
streaming and driving frame time, plus a hands-off benchmark harness that measures them.
Private repo (DiegoVillalobosFlores/PZ_Optimization). Owner: Diego (they/them).

## Objective (stated 2026-09-18)

"Consistent frame time if the CPU and GPU utilization allows it; the CPU and GPU should
always be used to the max if the framerate is not smoothly pegged at 240 fps."
Every benchmark report must show frame-tail metrics (p99 / p99.9 / spikes / jitter) AND
utilization (CPU/GPU load from sysmon) over the route window. "fps < 240 and hardware not
saturated" is itself a finding. Chunk-latency wins are done; do not spend more on the streamer.

## Hard rules

- **Never commit game code.** `decompiled/` and `src/overrides/` are gitignored and stay local.
  Never `git add` them, never paste game source into committed docs. Every edit to an
  overridden class is described in prose in `docs/override-edits.md`.
- **Shared machine.** Several Claude sessions and Diego use the one game install and `~/Zomboid`.
  Before a launch or a reinstall check both `pgrep -f '[P]rojectZomboid64'` and
  `pgrep -f '[h]arness/run.sh'` (excluding your own). Message busy peers (ListAgents /
  SendMessage) before reinstalling or starting a batch. See `.claude/skills/bench-run`.
- **Run etiquette.** Diego is usually at the machine. Say a run is about to start before
  launching, one run at a time, never long batches. Never edit `harness/run.sh` while a run is in
  progress (bash reads it incrementally; a mid-edit launch died and its EXIT trap corrupted
  `latestSave.ini`). Launch from a copy (`harness/.run-snapshot.sh`) if someone else edits it.
- **Never kill with a self-matching pattern.** `pkill -f '<pattern>'` where the pattern appears in
  your own command line kills the tool shell (exit 144). Use bracket patterns like
  `[P]rojectZomboid64` or a saved PID.
- **Real saves are never loaded or written** by a harness run. Runs use the copied bench save
  `Saves/Sandbox/pzopt-bench` and must quit on their own. Launching the game via
  `harness/run.sh` is authorized without asking.
- Commit and push only when asked. Commits end with
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Environment facts

| Item | Value |
|---|---|
| OS | CachyOS (Arch), fish shell, `paru` for AUR, 16 cores, 30 GB, RTX 4090 |
| JDK | system `jdk-openjdk` 26; game bundles its own JRE (build.sh compiles with `--release` for it) |
| Game dir | `/games/steamapps/common/ProjectZomboid/projectzomboid/` (native Linux depot since 2026-09-18) |
| User dir | `~/Zomboid` (console.txt, Saves, Lua/, mods/, options.ini) |
| Layout detection | `scripts/pz-env.sh` (PZ_DIR / ZOMBOID env override) |
| Desktop | 5120x2160, 240 Hz; the game renders windowed at desktop resolution |
| Decompiler | CFR at `~/.local/share/java/cfr.jar` for reading; Vineflower for overrides |
| Code index | `.codegraph/` exists; use `codegraph_explore` before grep/Read |
| Java LSP | `jdtls` not installed (check `command -v jdtls` before relying on the LSP tool) |

## Reading game code

Read game classes from `decompiled/` (CFR output of all 3,407 game classes, package tree such as
`decompiled/zombie/iso/IsoChunk.java`). Never re-decompile or unzip the jar for those packages.
Only two methods lack bodies (`CompressIdenticalItems.areItemsIdentical`,
`ChooseGameInfo.readModInfoAux`); use `javap -c -p` for them. If the jar mtime changes (game
update) re-run `scripts/decompile.sh` and `scripts/regen-overrides.sh`.

## Layout

| Path | Purpose | Details |
|---|---|---|
| `src/` | overrides, shims and the `pzopt` helper package | `src/CLAUDE.md` |
| `scripts/` | build / install / decompile / test | `scripts/CLAUDE.md` |
| `harness/` | run.sh, analysis scripts, baselines, run outputs | `harness/CLAUDE.md` |
| `config/` | MangoHud profiles | `config/CLAUDE.md` |
| `docs/` | plans, findings, override edit log, dashboard | `docs/CLAUDE.md` |
| `tools/` | standalone Java probes (JFR dump, GLFW swap probe, static audit) | `tools/CLAUDE.md` |
| `tests/` | JVM-only unit tests for pzopt classes (`scripts/test.sh`) | |
| `decompiled/` | CFR output, local only | |
| `openspec/` | OpenSpec change proposals (opsx skills) | |

## Skills (in `.claude/skills/`)

| Skill | Use when |
|---|---|
| `bench-run` | launching any measurement run (bench / drive / parity / verify) |
| `showcase-drive` | recording the stock-vs-optimized drive videos and the quad stitch |
| `build-install` | compiling the overrides and installing them into the game dir |
| `analyze-run` | reading a finished run: analyze, compare, waits, loadtime, dashboard |
| `override-game-class` | adding or changing an overridden game class |
| `game-update` | the jar changed: re-decompile, regen overrides, rebuild, re-baseline |
| `proton-run` | preparing a Windows/Proton comparison run |
| `mangohud-overlay` | HUD/CSV problems, launcher and display-server hooks |

## Current state (2026-09-19)

- Adopted Config defaults (max-zoom route mean 6.2 → 4.4 ms, p99 19.3 → 8.3):
  persistentVbo, treesInChunkTexture, translucentTilesInChunkTexture, windowsInChunkTexture,
  bakeBudget=8, lightingBudget=8, hotsaveIntervalSec=30, on top of wake + recalc pool.
- Remaining tail with the PZDashboard mod is its 2 s collectors; measure with `--no-dashboard`.
- Uncapped: NVIDIA GL is GPU-bound (98 %) at 570 fps; Zink blocks ~1.8 ms/frame in swap.
  `uiRenderOffscreen=true` in options.ini removes the per-frame Lua UI draw.
- Open plans: `docs/plan-game-load.md`, `docs/plan-vulkan-renderer.md`, `docs/plan-resource-use.md`.
- Native Wayland works via `--env JAVA_TOOL_OPTIONS=-Dzomboid.wayland=1`.
- Proton run prepared but blocked on Diego forcing a compat tool in Steam.
