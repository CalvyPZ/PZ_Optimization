# scripts/

Build, install and decompile. All scripts source `pz-env.sh` for the game and user dirs
(native: `.../ProjectZomboid/projectzomboid/`, `~/Zomboid`; Proton: `.../ProjectZomboid/`,
compatdata prefix). Override with `PZ_DIR` / `ZOMBOID`.

| Script | Does |
|---|---|
| `build.sh` | `javac --release <game JRE>` of `src/overrides`, `src/shims`, `src/pzopt` against `projectzomboid.jar` into `build/classes/` plus `build-info.properties` (game build compiled against). `OVERRIDES=(...)` lists the shadowed game classes. Copies `src/lua/` under `build/classes/media/lua/` and generates the `puddleEarlyZ` shader variants (`media/shaders/pzopt_puddles_*`) from the installed game's puddle shaders (2026-09-21). |
| `pzopt.sh install\|uninstall\|reinstall\|status\|check` | copies `build/classes/` over the game dir. The launcher JSON puts `.` ahead of the jar, so loose `.class` files shadow it; the jar is never written. `pzopt-installed.txt` records what was written so uninstall is exact even after a game update. |
| `accept.sh [--prop k=v]` | build + install + parity gate against the stock capture; fails if recalc output differs. |
| `release.sh [--publish] [--notes ...]` | build + test + `build/pzopt-<rev>-classes.zip` (flat `build/classes/` plus a `pzopt-files.txt` manifest, Python zipfile); `--publish` = `gh release create win-<rev>-<commit>` with the zip plus the repo-root `install.sh` / `install.ps1` from a pushed clean HEAD. Skill `release-windows`. |
| `workshop.sh [--zip <release zip>] [--out <dir>]` | stages the Steam Workshop item (pure distribution) under `~/Zomboid/Workshop/PZ_Optimization/`: the zip unpacked as `42/pzopt-classes/`, `install.ps1`, `install.bash`, `mod.info`, `workshop.txt` from `docs/workshop/description.txt`, preview/poster PNGs; checks the uploader's banned extensions and preview size. Upload is in-game (Workshop > Create/Update item). `docs/workshop.md`. |
| `option-classes.py [-v]` | which Java classes read each `pzopt.Config` key (`Config.FIELD`, or a small Config helper such as `effectiveWorkers()`) → `src/lua/client/pzopt/pzopt_optimizations_classes.lua`, the Optimizations tab's search index; build.sh runs it; `-v` lists keys nothing outside Config reads. |
| `test.sh` | compiles `tests/` against `build/classes` + jar, runs each `*Test` main (no game needed). |
| `decompile.sh` | CFR, all game packages in parallel into `decompiled/`. Re-run after a game update only. |
| `regen-overrides.sh` | Vineflower decompile of the OVERRIDES list into `build/vineflower/` to diff against `src/overrides/` after an update. Vineflower because its output recompiles with one fix; CFR's needs several. |

Rules:
- Before `reinstall`, check no game or `harness/run.sh` process is running (yours or a peer
  session's); a reinstall seconds before another session's launch made their run measure your
  classes. Announce to peers.
- `build/` is gitignored; `pzopt.properties` in the game dir switches Config keys off at
  runtime (defaults live in `src/pzopt/pzopt/Config.java`).
- Proton dir still holds Sep-15 overrides and `jre64 -> jre64.graal`; before any Proton run
  do `PZ_DIR=/games/steamapps/common/ProjectZomboid scripts/pzopt.sh uninstall && install`.
