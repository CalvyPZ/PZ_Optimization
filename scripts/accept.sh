#!/usr/bin/env bash
# Acceptance build: compile the overrides, install them, and run the parity
# gate against the stock baseline capture. Fails if the overrides do not
# reproduce the stock recalc output exactly. Pass --prop k=v to set runtime
# settings for the parity run (e.g. --prop workers=4).
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
"$REPO/scripts/build.sh"
"$REPO/scripts/pzopt.sh" reinstall | tail -1
"$REPO/harness/parity-gate.sh" --label accept "$@"
