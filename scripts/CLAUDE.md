# scripts/

Build, install and decompile. All scripts source `pz-env.sh` for the game and user dirs
(native: `.../ProjectZomboid/projectzomboid/`, `~/Zomboid`; Proton: `.../ProjectZomboid/`,
compatdata prefix). Override with `PZ_DIR` / `ZOMBOID`.

| Script | Does |
|---|---|
| `build.sh` | `javac --release <game JRE>` of `src/overrides`, `src/shims`, `src/pzopt` against `projectzomboid.jar` into `build/classes/` plus `build-info.properties` (game build compiled against). `OVERRIDES=(...)` lists the shadowed game classes. |
| `pzopt.sh install\|uninstall\|reinstall\|status\|check` | copies `build/classes/` over the game dir. The launcher JSON puts `.` ahead of the jar, so loose `.class` files shadow it; the jar is never written. `pzopt-installed.txt` records what was written so uninstall is exact even after a game update. |
| `accept.sh [--prop k=v]` | build + install + parity gate against the stock capture; fails if recalc output differs. |
| `release.sh [--publish] [--notes ...]` | build + test + `build/pzopt-<rev>-classes.zip` (flat `build/classes/` plus a `pzopt-files.txt` manifest, Python zipfile); `--publish` = `gh release create win-<rev>-<commit>` from a pushed clean HEAD. Skill `release-windows`. |
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
