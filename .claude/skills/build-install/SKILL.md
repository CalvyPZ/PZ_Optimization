---
name: build-install
description: Compile the class overrides and install or remove them in the Project Zomboid game directory, then run the parity gate or unit tests. Use after any change under src/ or when switching between stock and optimized for a run.
---

# Build and install

```bash
scripts/build.sh                    # javac --release <game JRE> -> build/classes/
scripts/test.sh                     # JVM-only tests in tests/
pgrep -fa '[P]rojectZomboid64'; pgrep -fa '[h]arness/run.sh'   # nobody mid-run (peers included)
scripts/pzopt.sh reinstall          # uninstall exactly what was written, then install
scripts/pzopt.sh status
```
Parity gate (recalc output must equal stock byte for byte):
```bash
scripts/accept.sh --prop workers=4      # build + install + harness/parity-gate.sh
```
- Announce a reinstall to peer sessions; a reinstall seconds before their launch changes what
  their run measures.
- Stock measurement = `scripts/pzopt.sh uninstall`; put the classes back afterwards.
- Runtime switches live in `<game dir>/pzopt.properties` (keys from `Config.java`); use
  `harness/run.sh --prop k=v` per run instead of editing that file by hand.
- `src/overrides/` must exist locally; if missing see the `override-game-class` skill.
- Proton dir: prefix with `PZ_DIR=/games/steamapps/common/ProjectZomboid`.
