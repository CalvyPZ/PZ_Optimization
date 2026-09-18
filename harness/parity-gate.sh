#!/usr/bin/env bash
# Parity acceptance gate: run the game once in parity mode with the given
# settings, capture the recalc output of every chunk loaded on the fixed
# route, and compare it with the stored stock baseline capture. Exit status is
# the comparison's: 0 = identical, 1 = differences, so a caller can fail a
# build on it.
#
#   harness/parity-gate.sh --label <name> [--prop k=v]... [--flag k=v]...   compare against harness/baseline/parity-stock.out
#   harness/parity-gate.sh --record-baseline                   (re)create the baseline from a stock-behaviour run
#
# Comparison is restricted to chunks that exist on disk in the bench save
# template (chunks the game generates on the fly are random).
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
source "$(dirname "${BASH_SOURCE[0]}")/../scripts/pz-env.sh"
TEMPLATE_MAP="$ZOMBOID/Saves/Sandbox/pzopt-bench-template/map"
BASELINE="$REPO/harness/baseline/parity-stock.out"
ROUTE="${PARITY_ROUTE:-E:200,S:200}"

label=""; props=(); flags=(); record=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --label) label="$2"; shift 2 ;;
    --prop) props+=("--prop" "$2"); shift 2 ;;
    --flag) flags+=("--flag" "$2"); shift 2 ;;
    --record-baseline) record=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

if (( record )); then
  label="parity-baseline"
  props=(--prop parallel=false)
fi
[[ -n "$label" ]] || { echo "usage: $0 --label <name> [--prop k=v]... | --record-baseline" >&2; exit 2; }

out=$("$REPO/harness/run.sh" --label "$label" --mode parity --flag "route=$ROUTE" "${flags[@]}" "${props[@]}" | tee /dev/stderr | sed -n 's/^run took .*log at \(.*\)\/console.txt$/\1/p')
[[ -n "$out" && -f "$out/pzopt-parity.out" ]] || { echo "no parity capture produced" >&2; exit 2; }

if (( record )); then
  mkdir -p "$(dirname "$BASELINE")"
  cp "$out/pzopt-parity.out" "$BASELINE"
  echo "baseline recorded: $BASELINE ($(grep -c '^# chunk' "$BASELINE") chunks)"
  exit 0
fi
[[ -f "$BASELINE" ]] || { echo "no baseline; run: $0 --record-baseline" >&2; exit 2; }
echo "comparing $out/pzopt-parity.out against baseline"
python3 "$REPO/harness/parity.py" "$BASELINE" "$out/pzopt-parity.out" --only-on-disk "$TEMPLATE_MAP"
