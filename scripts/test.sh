#!/usr/bin/env bash
# Unit tests for the pzopt classes that do not need the game running.
# Compiles tests/ against build/classes and the game jar, then runs each *Test main.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/pz-env.sh"
JAR="$PZ_DIR/projectzomboid.jar"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$REPO/build/tests"
[[ -d "$REPO/build/classes" ]] || { echo "run scripts/build.sh first" >&2; exit 1; }
rm -rf "$OUT"; mkdir -p "$OUT"
javac --release 25 -nowarn -cp "$REPO/build/classes:$JAR" -d "$OUT" $(find "$REPO/tests" -name '*.java')
fail=0
for t in $(cd "$OUT" && find . -name '*Test.class' | sed 's|^\./||;s|\.class$||;s|/|.|g' | sort); do
  if ! java -Dpzopt.dev=true -cp "$OUT:$REPO/build/classes:$JAR" "$t"; then echo "FAILED: $t" >&2; fail=1; fi
done
exit $fail
