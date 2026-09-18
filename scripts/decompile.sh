#!/usr/bin/env bash
# Decompile Project Zomboid's own code (not the bundled third-party libs) from
# projectzomboid.jar into decompiled/, one .java per top-level class, laid out
# as a package tree (decompiled/zombie/iso/IsoChunk.java).
#
# Re-run after a game update. Needs a JDK on PATH and CFR (see README).
# Packages are split across parallel CFR processes; each writes to its own
# temp dir (CFR writes a summary.txt per run), then the disjoint trees are merged.
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/pz-env.sh"
JAR="$PZ_DIR/projectzomboid.jar"
CFR_JAR="${CFR_JAR:-$HOME/.local/share/java/cfr.jar}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$REPO/decompiled"
JOBS="${JOBS:-10}"

[[ -f "$JAR" ]] || { echo "jar not found: $JAR" >&2; exit 1; }
[[ -f "$CFR_JAR" ]] || { echo "cfr not found: $CFR_JAR" >&2; exit 1; }

# Game-code roots inside the fat jar. Everything else (org/, com/, gnu/, io/,
# kotlin/, oshi/, okhttp3/, imgui/, jassimp/, ...) is a third-party dependency.
GAME_ROOTS='zombie|generation|fmod|se|astar|N3D'

# One CFR process per regex. CFR matches the regex against the full dotted
# name of each top-level class (inner classes ride along with their outer).
# Big zombie.* subpackages get their own job; the negative-lookahead entry
# catches every subpackage not listed, so new packages in future builds are
# still picked up.
BIG='core|network|iso|scripting|characters|entity|randomizedWorld|audio|ai|pathfind|worldMap|commands|util|debug|ui|vehicles|inventory'
FILTERS=(
  '^zombie\.core\..*'
  '^zombie\.network\..*'
  '^zombie\.iso\..*'
  '^zombie\.scripting\..*'
  '^zombie\.characters\..*'
  '^zombie\.entity\..*'
  '^zombie\.randomizedWorld\..*'
  '^zombie\.audio\..*'
  '^zombie\.ai\..*'
  '^zombie\.pathfind\..*'
  '^zombie\.worldMap\..*'
  '^zombie\.(commands|util|debug)\..*'
  '^zombie\.(ui|vehicles|inventory)\..*'
  '^zombie\.[^.]+$'
  "^zombie\\.(?!($BIG)\\.)[^.]+\\..*"
  '^(generation|fmod|se|astar|N3D)\..*'
)

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Run one CFR process for filter $2, writing to dir $1, with heap $3.
run_cfr() {
  java -Xmx"$3" -jar "$CFR_JAR" --outputdir "$1" --silent true "$JAR" --jarfilter "$2" >"$1.log" 2>&1
}

echo "decompiling $JAR -> $OUT with $JOBS parallel CFR processes"
start=$(date +%s)
pids=()
i=0
for f in "${FILTERS[@]}"; do
  i=$((i+1))
  out="$TMP/job$i"
  echo "$f" > "$out.filter"
  run_cfr "$out" "$f" 2g &
  pids+=($!)
  while (( $(jobs -rp | wc -l) >= JOBS )); do sleep 0.5; done
done
failed=()
for i in "${!pids[@]}"; do wait "${pids[$i]}" || failed+=("$TMP/job$((i+1))"); done

# A few packages hold generated lookup-table methods (e.g. zombie.scripting's
# *Key classes, generation.*ScriptGenerator) that make CFR's SSA pass need
# several GB. Re-run those jobs one at a time with a big heap.
for out in "${failed[@]}"; do
  f=$(cat "$out.filter")
  echo "retrying $f with a 12g heap"
  rm -rf "$out"
  if ! run_cfr "$out" "$f" 12g; then
    echo "CFR failed for filter $f:" >&2
    grep -v '^\s*at ' "$out.log" | head -5 >&2 || true
  fi
done

rm -rf "$OUT"
mkdir -p "$OUT"
for d in "$TMP"/job*/; do
  n=$(basename "$d")
  [[ -f "$d/summary.txt" ]] && mv "$d/summary.txt" "$TMP/$n.summary.txt"
  cp -a "$d"/. "$OUT"/
done
# Keep the CFR summaries: they list any class CFR had trouble with.
cat "$TMP"/job*.summary.txt > "$OUT/cfr-summary.txt" 2>/dev/null || true

# Verify: every top-level game class in the jar has a .java file.
unzip -Z1 "$JAR" | grep -E "^($GAME_ROOTS)/" | grep '\.class$' | grep -v '\$' | sed 's/\.class$/.java/' | sort > "$TMP/expected"
( cd "$OUT" && find . -name '*.java' | sed 's|^\./||' | sort ) > "$TMP/actual"
missing=$(comm -23 "$TMP/expected" "$TMP/actual")
extra=$(comm -13 "$TMP/expected" "$TMP/actual")
end=$(date +%s)
echo "expected $(wc -l < "$TMP/expected") top-level classes, produced $(wc -l < "$TMP/actual") .java files in $((end-start))s"
if [[ -n "$missing" ]]; then echo "MISSING:"; echo "$missing"; fi
if [[ -n "$extra" ]]; then echo "UNEXPECTED (not in jar):"; echo "$extra"; fi
[[ -z "$missing" ]]
