---
name: proton-run
description: Prepare and run the Windows/Proton comparison build of Project Zomboid. Use when asked to compare native Linux against Proton, or to resume the prepared Proton run.
---

# Proton comparison run

State (2026-09-19): prepared, not launched. The Windows depot is not on disk; Steam keeps one
platform depot and swaps it with the compat tool. Forcing a Proton tool in Steam >
Properties > Compatibility (which removes the native depot) is the maintainer's decision. Do not do it.

Sequence, in `docs/proton-run-prep-2026-09-19.md`:
```bash
harness/proton-preflight.sh                                   # read-only readiness checks
PZ_DIR=/games/steamapps/common/ProjectZomboid scripts/pzopt.sh uninstall   # stale Sep-15 classes
PZ_DIR=/games/steamapps/common/ProjectZomboid scripts/pzopt.sh install
# smoke (verify), stock bench, opt bench - flags as in bench-run
```
Decide `jre64.graal` vs `jre64.stock` in the Proton dir before comparing with native; the
native launcher JSON is stock (-Xmx3072m, ZGC), Proton dir holds the maintainer's tuned
`ProjectZomboid64.json.opt`. Proton runs use NVIDIA GL 615 and the compatdata user dir;
run.sh writes the launch env there in that layout. Frame-time numbers are only comparable to
native runs on the same renderer, resolution and zoom.
