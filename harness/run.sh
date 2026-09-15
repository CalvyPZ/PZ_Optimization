#!/usr/bin/env bash
# Launch one hands-off game run and collect its log.
#
#   harness/run.sh --label <name> [--quit-after <secs>] [--mode <mode>] [--source-save <Mode/Name>]
#                  [--flag k=v]... [--prop k=v]...
#
# What it does:
#   1. installs the pzopt-harness Lua mod into ~/Zomboid/mods (flag file goes to ~/Zomboid/Lua/) and enables it
#      in mods/default.txt (so it runs on the main menu),
#   2. creates the dedicated bench save Saves/Sandbox/pzopt-bench by copying
#      --source-save (default: whatever latestSave.ini points at) the first
#      time, and enables the harness mod in that save's mods.txt,
#   3. points latestSave.ini at the bench save and writes the flag file the
#      Lua mod reads, so the game auto-continues into the bench save,
#   4. launches the game through Steam, waits for it to exit,
#   5. copies console.txt (and any pzopt output files) into
#      harness/runs/<label>-<timestamp>/ and restores latestSave.ini.
#
# The player's real saves are never loaded or written by a harness run.
set -euo pipefail

ZOMBOID="${ZOMBOID:-/games/steamapps/compatdata/108600/pfx/drive_c/users/steamuser/Zomboid}"
APPID=108600
REPO="$(cd "$(dirname "$0")/.." && pwd)"
MOD_SRC="$REPO/harness/mod/pzopt-harness"
MOD_ID=pzopt-harness
BENCH_SAVE="Sandbox/pzopt-bench"
RUNS="$REPO/harness/runs"

label=""; quit_after=""; mode="verify"; source_save=""; extra_flags=(); props=(); mangohud_secs=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --label) label="$2"; shift 2 ;;
    --quit-after) quit_after="$2"; shift 2 ;;
    --mode) mode="$2"; shift 2 ;;
    --source-save) source_save="$2"; shift 2 ;;
    --flag) extra_flags+=("$2"); shift 2 ;;   # extra key=value for pzopt-harness.txt
    --prop) props+=("$2"); shift 2 ;;         # key=value for the game dir's pzopt.properties (see pzopt.Config)
    --mangohud) mangohud_secs="$2"; shift 2 ;; # external frame-time log via MangoHud for N seconds from launch
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
[[ -n "$label" ]] || { echo "usage: $0 --label <name> [--quit-after secs] [--mode m] [--source-save Mode/Name] [--flag k=v]..." >&2; exit 2; }
[[ -d "$ZOMBOID" ]] || { echo "Zomboid user dir not found: $ZOMBOID" >&2; exit 1; }
if pgrep -f '[P]rojectZomboid64' >/dev/null; then echo "the game is already running" >&2; exit 1; fi

# 1. harness mod
rm -rf "$ZOMBOID/mods/$MOD_ID"
cp -r "$MOD_SRC" "$ZOMBOID/mods/$MOD_ID"
enable_mod() { # $1 = mods.txt path
  # files are CRLF (written by the Windows build under Proton)
  grep -q "mod = $MOD_ID," "$1" || sed -i "0,/^mods\r$/{n;s/^{\r$/{\r\n    mod = $MOD_ID,\r/}" "$1"
  grep -q "mod = $MOD_ID," "$1" || { echo "could not enable $MOD_ID in $1" >&2; exit 1; }
}
[[ -f "$ZOMBOID/mods/default.txt.pzopt-orig" ]] || cp "$ZOMBOID/mods/default.txt" "$ZOMBOID/mods/default.txt.pzopt-orig"
enable_mod "$ZOMBOID/mods/default.txt"

# 2. bench save: a pristine template is made once from the source save, and
#    the actual bench save is recreated from it before every run, so each run
#    loads byte-identical chunk data regardless of what earlier runs wrote.
TEMPLATE="$ZOMBOID/Saves/${BENCH_SAVE}-template"
if [[ ! -d "$TEMPLATE" ]]; then
  if [[ -z "$source_save" ]]; then
    # latestSave.ini: line 1 = save name, line 2 = game mode
    source_save="$(sed -n 2p "$ZOMBOID/latestSave.ini" | tr -d '\r')/$(sed -n 1p "$ZOMBOID/latestSave.ini" | tr -d '\r')"
  fi
  [[ -d "$ZOMBOID/Saves/$source_save" ]] || { echo "source save not found: $ZOMBOID/Saves/$source_save" >&2; exit 1; }
  echo "creating bench save template from $source_save ($(du -sh "$ZOMBOID/Saves/$source_save" | cut -f1))"
  cp -r "$ZOMBOID/Saves/$source_save" "$TEMPLATE"
  enable_mod "$TEMPLATE/mods.txt"
fi
rm -rf "$ZOMBOID/Saves/$BENCH_SAVE"
cp -r "$TEMPLATE" "$ZOMBOID/Saves/$BENCH_SAVE"

# 3. point the game at the bench save and write the flag file
cp "$ZOMBOID/latestSave.ini" "$ZOMBOID/latestSave.ini.pzopt-orig"
trap 'restore' EXIT
printf '%s\r\n%s\r\n' "$(basename "$BENCH_SAVE")" "$(dirname "$BENCH_SAVE")" > "$ZOMBOID/latestSave.ini"
{
  echo "mode=$mode"
  [[ -n "$quit_after" ]] && echo "quit_after=$quit_after"
  for f in "${extra_flags[@]}"; do echo "$f"; done
} > "$ZOMBOID/Lua/pzopt-harness.txt"   # Lua getFileReader resolves under Zomboid/Lua/
rm -f "$ZOMBOID"/pzopt-*.out

# runtime settings for this run; the previous pzopt.properties comes back afterwards
PZ_DIR="${PZ_DIR:-/games/steamapps/common/ProjectZomboid}"
if [[ -f "$PZ_DIR/pzopt.properties" ]]; then cp "$PZ_DIR/pzopt.properties" "$PZ_DIR/pzopt.properties.pzopt-orig"; fi
restore() {
  restore_mangohud
  mv -f "$ZOMBOID/latestSave.ini.pzopt-orig" "$ZOMBOID/latestSave.ini" 2>/dev/null || true
  if [[ -f "$PZ_DIR/pzopt.properties.pzopt-orig" ]]; then mv -f "$PZ_DIR/pzopt.properties.pzopt-orig" "$PZ_DIR/pzopt.properties"; else rm -f "$PZ_DIR/pzopt.properties"; fi
}
printf '%s\n' "${props[@]}" > "$PZ_DIR/pzopt.properties"
cp "$PZ_DIR/pzopt.properties" "$RUNS/.last-props" 2>/dev/null || true

# external frame-time log: MangoHud is injected by the Steam launch options
# (MANGOHUD=1); its global config gets autostart/duration keys for this run.
MH_CONF="$HOME/.config/MangoHud/MangoHud.conf"
MH_OUT="$HOME/Documents/mangohud/benchmarks"
if [[ -n "$mangohud_secs" ]]; then
  [[ -f "$MH_CONF" ]] || { echo "MangoHud config not found: $MH_CONF" >&2; exit 1; }
  cp "$MH_CONF" "$MH_CONF.pzopt-orig"
  sed -i '/^log_duration=/d;/^autostart_log=/d;/^output_folder=/d;/^log_interval=/d' "$MH_CONF"
  printf 'output_folder=%s/\nautostart_log=2\nlog_duration=%s\nlog_interval=0\n' "$MH_OUT" "$mangohud_secs" >> "$MH_CONF"
  mkdir -p "$MH_OUT"
fi
restore_mangohud() { [[ -f "$MH_CONF.pzopt-orig" ]] && mv -f "$MH_CONF.pzopt-orig" "$MH_CONF"; return 0; }

# 4. launch and wait
out="$RUNS/$label-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$out"
launch_epoch=$(date +%s)
echo "launching app $APPID (mode=$mode quit_after=${quit_after:-none}); output -> $out"
start=$(date +%s)
steam -applaunch $APPID >/dev/null 2>&1 &
for _ in $(seq 1 120); do pgrep -f '[P]rojectZomboid64' >/dev/null && break; sleep 1; done
pgrep -f '[P]rojectZomboid64' >/dev/null || { echo "game process did not appear within 120s" >&2; exit 1; }
echo "game running (pid $(pgrep -f '[P]rojectZomboid64' | head -1)); waiting for exit"
# the Proton process tree is replaced a few times during startup; only treat
# the game as gone after several consecutive checks find nothing
gone=0
while (( gone < 5 )); do
  if pgrep -f '[P]rojectZomboid64' >/dev/null; then gone=0; else gone=$((gone+1)); fi
  sleep 2
done
end=$(date +%s)
sleep 2

# 5. collect
cp "$ZOMBOID/console.txt" "$out/console.txt"
cp "$ZOMBOID"/pzopt-*.out "$out/" 2>/dev/null || true
cp "$PZ_DIR/pzopt.properties" "$out/pzopt.properties"
if [[ -n "$mangohud_secs" ]]; then
  # newest MangoHud csv written since launch
  mh=$(find "$MH_OUT" -name '*.csv' -newermt "@$launch_epoch" -printf '%T@ %p\n' 2>/dev/null | sort -n | tail -1 | cut -d' ' -f2-)
  if [[ -n "$mh" ]]; then cp "$mh" "$out/mangohud.csv"; basename "$mh" > "$out/mangohud.name"; echo "mangohud log: $mh"; else echo "no MangoHud log found in $MH_OUT" >&2; fi
fi
echo "run took $((end-start))s; log at $out/console.txt"
echo "--- [pzopt] lines:"
grep -a -F '[pzopt' "$out/console.txt" | head -40 || true
echo "--- errors:"
grep -a -iE 'exception|error' "$out/console.txt" | grep -a -v 'ERROR: 0:0\|GL_' | head -10 || true
