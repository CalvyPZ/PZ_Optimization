#!/usr/bin/env bash
# Does the Options > Optimizations "Low-end hardware (4 cores or less)" button tick texture compression? (2026-09-23)
# Launches the installed game to the main menu (Steam off), OPTIONS -> the Optimizations tab -> the low-end button ->
# ACCEPT, closes the game, then reads ~/Zomboid/options.ini (textureCompression=true, lightFPS / uiRenderFPS set) and
# Zomboid/pzopt/options.ini (workers=1). Both files are backed up first and restored on exit (shared machine).
# Run through the queue: harness/queue.sh submit cmd --install opt --label <l> -- harness/preset-texcompress-check.sh
set -u
cd "$(dirname "$0")/.."
source scripts/pz-env.sh
OUT=${OUT:-/tmp/preset-texcompress-check}; rm -rf "$OUT"; mkdir -p "$OUT"
UI=harness/ui-drive.py
scripts/pzopt.sh status | grep -q "^installed: *yes" || { echo "overrides not installed"; exit 1; }
ocr() { python3 $UI read 2>/dev/null; }
shot() { sleep "${2:-1.0}"; spectacle -b -n -f -o "$OUT/$1.png" >/dev/null 2>&1; }
click_text() { # <regex> : press-and-release at the first OCR line matching it
  local line x y
  line=$(ocr | grep -E "$1" | head -1)
  [[ -n "$line" ]] || { echo "no OCR line for /$1/"; return 1; }
  x=$(sed -E 's/^[^(]*\( *([0-9]+), *([0-9]+)\).*/\1/' <<<"$line"); y=$(sed -E 's/^[^(]*\( *([0-9]+), *([0-9]+)\).*/\2/' <<<"$line")
  xdotool mousemove "$(( x * 100 / 125 ))" "$(( y * 100 / 125 ))"; sleep 0.3
  xdotool mousedown 1; sleep 0.15; xdotool mouseup 1; sleep 1.0
  echo "clicked /$1/ at $x,$y"
}
GAME_PID=""
cleanup() {
  [[ -n "$GAME_PID" ]] && kill "$GAME_PID" 2>/dev/null; sleep 3
  cp "$OUT/options.ini.orig" "$ZOMBOID/options.ini"
  if [[ -f "$OUT/pzopt-options.ini.orig" ]]; then cp "$OUT/pzopt-options.ini.orig" "$ZOMBOID/pzopt/options.ini"; else rm -f "$ZOMBOID/pzopt/options.ini"; fi
  echo "options restored"
}
cp "$ZOMBOID/options.ini" "$OUT/options.ini.orig"
[[ -f "$ZOMBOID/pzopt/options.ini" ]] && cp "$ZOMBOID/pzopt/options.ini" "$OUT/pzopt-options.ini.orig"
trap cleanup EXIT
sed -i 's/^textureCompression=.*/textureCompression=false/' "$ZOMBOID/options.ini"   # start from off, so a true afterwards is the button's
( cd "$PZ_DIR/.." && JAVA_TOOL_OPTIONS="-Dzomboid.steam=0 -Dpzopt.updateCheck=false" exec setsid ./projectzomboid.sh </dev/null >"$OUT/game.log" 2>&1 ) &
sleep 3; GAME_PID=$(pgrep -f '^([^ ]*/)?ProjectZomboid64( |$)' | head -1)
for i in $(seq 1 45); do sleep 2; ocr | grep -q "OPTIONS" && break; done
sleep 2; shot menu 0
click_text "OPTIONS *$" || exit 2; sleep 2; shot options 0
click_text "Optimizations" || exit 2; sleep 1.5; shot tab 0
click_text "Low-end hardware \(4 cores" || exit 2; sleep 1; shot pressed 0
click_text "ACCEPT" || exit 2; sleep 2; shot accepted 0
ocr > "$OUT/after-accept-ocr.txt"
click_text "^.*\bOK\b *$" >/dev/null 2>&1   # a restart-required popup, if any
sleep 1; kill "$GAME_PID" 2>/dev/null; GAME_PID=""; sleep 4
cp "$ZOMBOID/options.ini" "$OUT/options.ini.after"
cp "$ZOMBOID/pzopt/options.ini" "$OUT/pzopt-options.ini.after" 2>/dev/null
FAILS=0
grep -q "^textureCompression=true" "$OUT/options.ini.after" && echo "PASS: textureCompression=true" || { echo "FAIL: textureCompression is $(grep '^textureCompression' "$OUT/options.ini.after")"; FAILS=$((FAILS + 1)); }
grep -q "^lightFPS=10" "$OUT/options.ini.after" && echo "PASS: lightFPS=10" || { echo "FAIL: $(grep '^lightFPS' "$OUT/options.ini.after")"; FAILS=$((FAILS + 1)); }
grep -q "^workers=1" "$OUT/pzopt-options.ini.after" 2>/dev/null && echo "PASS: workers=1" || { echo "FAIL: pzopt options: $(cat "$OUT/pzopt-options.ini.after" 2>/dev/null | head -5)"; FAILS=$((FAILS + 1)); }
grep -a "options tab: profile could not set" "$ZOMBOID/console.txt" | tail -3
echo "fails=$FAILS (screens in $OUT)"
exit $FAILS
