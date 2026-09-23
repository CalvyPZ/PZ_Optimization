#!/bin/bash
# Check of the "SHOW / HIDE PERFORMANCE OVERLAY" item (pzopt_mainscreen_overlay.lua) in the main menu and the pause
# menu, by controller (virtual Xbox 360 pad, harness/pad.py) and by mouse. Run it through the queue:
#   harness/queue.sh submit cmd --install keep --label pad-overlay -- harness/pad-overlay-check.sh
#  Main menu (direct launch, overlaySampling on): A activates the pad (focus on SOLO), down x4 walks MULTIPLAYER,
#    HOST, OPTIONS, the item; A must show the overlay (console "overlay: shown", item text HIDE ...), A again hides
#    it; a mouse click on the item shows it again, a second click hides it; then the game is stopped.
#  Pause menu (run.sh bench on the bench save, 1-tile route + hold, overlay sampling implied by the harness): Escape opens the pause
#    menu; a mouse click on the item toggles; then the pad: the SP pause menu focus starts on OPTIONS, one
#    down is the item, A must toggle. Caveat (2026-09-23): the bench player is on keyboard + mouse, so the stock
#    pause menu takes no joypad focus and this pad step fails; it needs the pad to be player 1's controller.
# Needs python-evdev and write access to /dev/uinput; the pad GUID goes into options.ini as a controller= line
# (backed up and restored).
set -u
cd "$(dirname "$0")/.."
source scripts/pz-env.sh
OUT=${OUT:-/tmp/pad-overlay-check}; rm -rf "$OUT"; mkdir -p "$OUT"
UI=harness/ui-drive.py
FIFO=/tmp/pzopt-pad.fifo
GUID=030000005e0400008e02000010010000
SCALE=1.25
LUA=pzopt_mainscreen_overlay.lua
scripts/pzopt.sh status | tee "$OUT/status.txt" | grep -q "^installed: *yes" || { echo "overrides not installed"; exit 1; }
cmp -s "src/lua/client/pzopt/$LUA" "$PZ_DIR/media/lua/client/pzopt/$LUA" || { echo "installed $LUA differs from src"; exit 1; }

ocr() { python3 $UI read 2>/dev/null; }
shot() { sleep "${2:-1.2}"; spectacle -b -n -f -o "$OUT/$1.png" >/dev/null 2>&1; echo "shot $1"; }
pad() { echo "$1" > "$FIFO"; sleep "${2:-0.6}"; }
toggles() { grep -ac "overlay: shown\|overlay: hidden" "$ZOMBOID/console.txt"; }
item_text() { ocr | grep -oE "(SHOW|HIDE) PERFORMANCE OVERLAY" | head -1; }
key() { xdotool keydown "$1"; sleep 0.12; xdotool keyup "$1"; sleep "${2:-1.2}"; }   # a plain `key` is too short for the per-frame poll
# press-and-release on the OCR line that is exactly the item's text (the game only hovers on a plain click)
click_item() {
  local line x y
  line=$(ocr | grep -E "(SHOW|HIDE) PERFORMANCE OVERLAY *$" | head -1)
  [[ -n "$line" ]] || { echo "no item line in OCR"; return 1; }
  x=$(sed -E 's/^[^(]*\( *([0-9]+), *([0-9]+)\).*/\1/' <<<"$line"); y=$(sed -E 's/^[^(]*\( *([0-9]+), *([0-9]+)\).*/\2/' <<<"$line")
  xdotool mousemove "$(( x * 100 / 125 ))" "$(( y * 100 / 125 ))"; sleep 0.3
  xdotool mousedown 1; sleep 0.15; xdotool mouseup 1; sleep 0.5
  xdotool mousemove 4000 1500; sleep 0.8   # off the menu: the hover shade spoils the OCR of the item
}
# $1 = what was done, $2 = the toggle count before, $3 = the item text before: one more toggle line and the text flipped
expect() {
  local now after; now=$(toggles); after=$(item_text)
  if (( now > $2 )) && [[ -n "$after" && "$after" != "$3" ]]; then echo "PASS: $1 toggles ($3 -> $after)"
  else echo "FAIL: $1 (toggle lines $2 -> $now, item '$3' -> '$after')"; FAILS=$((FAILS + 1)); fi
}
step() { local n t; n=$(toggles); t=$(item_text); "${@:2}"; expect "$1" "$n" "$t"; }
FAILS=0
GAME_PID=""; RUN_PID=""
cleanup() {
  [[ -n "$GAME_PID" ]] && kill "$GAME_PID" 2>/dev/null
  echo quit > "$FIFO" 2>/dev/null; sleep 0.5; rm -f "$FIFO"
  cp "$OUT/options.ini.orig" "$ZOMBOID/options.ini"; rm -f "$ZOMBOID/joypads/$GUID.config"
}
console_tail() {
  grep -a "\[pzopt\] overlay\|overlay: \|LuaError\|Callframe\|attempt to\|ERROR: General\|$LUA" "$ZOMBOID/console.txt" | tail -30 > "$OUT/console-$1.txt"
}

rm -f "$FIFO"; mkfifo "$FIFO"
python3 harness/pad.py serve "$FIFO" > "$OUT/pad.log" 2>&1 &
sleep 1.5; grep -q "pad ready" "$OUT/pad.log" || { echo "pad failed: $(cat "$OUT/pad.log")"; exit 1; }
cp "$ZOMBOID/options.ini" "$OUT/options.ini.orig"
[[ -z "$(tail -c1 "$ZOMBOID/options.ini")" ]] || echo >> "$ZOMBOID/options.ini"   # the game leaves no newline after the last option
grep -q "^controller=$GUID" "$ZOMBOID/options.ini" || echo "controller=$GUID" >> "$ZOMBOID/options.ini"

# --- main menu ---
( cd "$PZ_DIR/.." && JAVA_TOOL_OPTIONS="-Dzomboid.steam=0 -Dpzopt.overlaySampling=true -Dpzopt.updateCheck=false" exec setsid ./projectzomboid.sh </dev/null >"$OUT/game.log" 2>&1 ) &
sleep 3; GAME_PID=$(pgrep -f '^([^ ]*/)?ProjectZomboid64( |$)' | head -1)
for i in $(seq 1 45); do sleep 2; ocr | grep -qi "PERFORMANCE OVERLAY" && break; done
sleep 3; shot menu 0
ocr > "$OUT/menu-ocr.txt"
grep -qE "(SHOW|HIDE) PERFORMANCE OVERLAY" "$OUT/menu-ocr.txt" && echo "PASS: main menu shows the item" || { echo "FAIL: no item in the main menu"; FAILS=$((FAILS + 1)); }

pad a 1.2                                            # activation: the stock focus lands on SOLO
for i in 1 2 3 4; do pad down 0.5; done; shot main-on-item 0.3   # MULTIPLAYER, HOST, OPTIONS, the item
step "main menu: pad A on the item" pad a 1.5; shot main-pad-a 0.3
step "main menu: pad A again" pad a 1.5
step "main menu: mouse click" click_item; shot main-click 0.3
step "main menu: mouse click again" click_item
console_tail main
kill "$GAME_PID" 2>/dev/null
for i in $(seq 1 20); do kill -0 "$GAME_PID" 2>/dev/null || break; sleep 1; done
kill -0 "$GAME_PID" 2>/dev/null && { kill -9 "$GAME_PID"; sleep 2; }
GAME_PID=""

# --- pause menu: bench save, the player holds still on a 1-tile route for 60 s ---
harness/run.sh --label ovitem-pause --mode bench --flag route=S:1 --flag speed=1 --flag hold=60 --route-seconds 1 --no-dashboard --no-mangohud > "$OUT/run.log" 2>&1 &
RUN_PID=$!
sleep 10   # console.txt is rewritten at launch
for i in $(seq 1 90); do sleep 2; grep -aq "harness: route start" "$ZOMBOID/console.txt" 2>/dev/null && break; done
sleep 8; GAME_PID=$(pgrep -f '^([^ ]*/)?ProjectZomboid64( |$)' | head -1)
xdotool search --name "Project Zomboid" windowactivate --sync 2>/dev/null; sleep 0.5
# the pad's Start first (a pad that opens the pause menu owns its joypad focus), Escape as the fallback
PAUSE_BY=none
pad start 1.5; ocr | grep -q "PERFORMANCE OVERLAY" && PAUSE_BY=pad
if [[ $PAUSE_BY == none ]]; then for i in 1 2 3; do key Escape 1.5; ocr | grep -q "PERFORMANCE OVERLAY" && { PAUSE_BY=escape; break; }; done; fi
echo "pause menu opened by: $PAUSE_BY"
shot pause 0
ocr > "$OUT/pause-ocr.txt"
grep -q "PERFORMANCE OVERLAY" "$OUT/pause-ocr.txt" && echo "PASS: pause menu shows the item" || { echo "FAIL: no item in the pause menu"; FAILS=$((FAILS + 1)); }
step "pause menu: mouse click" click_item; shot pause-click 0.3
xdotool mousemove 4000 1500   # the pointer off the menu so the pad owns the highlight
pad down 0.5; shot pause-pad-on-item 0.3   # the SP pause menu focus starts on OPTIONS
step "pause menu: pad A on the item" pad a 1.5; shot pause-pad-a 0.3
console_tail pause
key Escape 0.5
wait "$RUN_PID"; echo "run.sh exit $?"; GAME_PID=""
cleanup
echo "done: $FAILS failure(s); $(ls "$OUT" | tr '\n' ' ')"
exit $(( FAILS > 0 ? 2 : 0 ))
