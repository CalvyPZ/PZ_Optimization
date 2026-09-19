#!/usr/bin/env bash
# Report whether a Proton (Windows build) harness run can be launched right now. Read-only: nothing
# is installed, moved or started. Prints one line per check and exits 1 if any hard check fails.
#
#   harness/proton-preflight.sh
#
# Background (2026-09-19): Steam keeps one platform depot in common/ProjectZomboid and swaps it when
# the game's compat tool changes. Since 2026-09-18 the native Linux depot (108603) is installed and
# the Windows one is gone, so a Proton run first needs Steam > Properties > Compatibility > "Force the
# use of a specific Steam Play compatibility tool" and the re-download that follows. The Proton user
# dir (compatdata prefix) and its bench save template are still on disk. See docs/proton-run-prep-2026-09-19.md.
set -uo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="${PZ_ROOT:-/games/steamapps/common/ProjectZomboid}"
PREFIX=/games/steamapps/compatdata/108600/pfx/drive_c/users/steamuser/Zomboid
fail=0
ok()   { echo "ok    $*"; }
bad()  { echo "FAIL  $*"; fail=1; }
warn() { echo "warn  $*"; }

# 1. depot
if [[ -f "$ROOT/projectzomboid.jar" && -f "$ROOT/ProjectZomboid64.exe" ]]; then
  ok "Windows depot present in $ROOT"
else
  bad "Windows depot not installed ($ROOT has no projectzomboid.jar + ProjectZomboid64.exe); force a Proton tool in Steam and let it download"
fi
[[ -f "$ROOT/projectzomboid/projectzomboid.jar" ]] && warn "native depot still present in $ROOT/projectzomboid: scripts/pz-env.sh prefers it, export PZ_DIR=$ROOT for the Proton layout"
acf=/games/steamapps/appmanifest_108600.acf
[[ -f "$acf" ]] && echo "      installed depots: $(grep -A40 InstalledDepots "$acf" | grep -oE '"10860[0-9]"' | tr -d '"' | tr '\n' ' ')(108603 = Linux)"

# 2. compat tool mapping for the app (global "0" only applies to non-native titles)
cfg="$HOME/.local/share/Steam/config/config.vdf"
if [[ -f "$cfg" ]]; then
  tool=$(python3 - "$cfg" <<'PY'
import re,sys
s=open(sys.argv[1]).read(); i=s.find('"CompatToolMapping"'); blk=s[i:i+20000]
m=re.search(r'"108600"\s*\{\s*"name"\s*"([^"]*)"',blk); print(m.group(1) if m else "")
PY
)
  if [[ -n "$tool" ]]; then ok "Steam compat tool for 108600: $tool"; else bad "no per-app compat tool set for 108600 in config.vdf (the game runs native); set one in Steam > Properties > Compatibility"; fi
fi

# 3. Steam client logged in (only it can start the Proton build) and launch options
log="$HOME/.local/share/Steam/logs/connection_log.txt"
if [[ -f "$log" ]] && grep -a -o '\[Logged O[nf]*' "$log" | tail -1 | grep -q 'Logged On'; then ok "Steam client logged in"; else bad "Steam client not running / not logged in"; fi
if grep -a -q 'harness/steam-launch.sh %command%' "$HOME"/.local/share/Steam/userdata/*/config/localconfig.vdf 2>/dev/null; then
  ok "launch options use harness/steam-launch.sh"
else
  warn "launch options do not contain harness/steam-launch.sh %command% (MangoHud/env cannot reach the game)"
fi

# 4. user dir: prefix, bench template, harness mod
if [[ -d "$PREFIX" ]]; then ok "Proton user dir $PREFIX"; else bad "Proton user dir missing: $PREFIX"; fi
t="$PREFIX/Saves/Sandbox/pzopt-bench-template"
if [[ -d "$t" ]]; then
  have=$(md5sum "$t/map_t.bin" 2>/dev/null | cut -c1-8)
  want=$(zstd -dc "$REPO/harness/bench-save/pzopt-bench-template.tar.zst" 2>/dev/null | tar -xOf - pzopt-bench-template/map_t.bin 2>/dev/null | md5sum | cut -c1-8)
  if [[ "$have" == "$want" ]]; then ok "bench template in the prefix matches harness/bench-save (map_t $have)"; else warn "bench template in the prefix differs from harness/bench-save ($have vs $want): rm -rf it and extract the tarball into $PREFIX/Saves/Sandbox"; fi
else
  warn "no bench template in the prefix: zstd -dc harness/bench-save/pzopt-bench-template.tar.zst | tar -C $PREFIX/Saves/Sandbox -xf -"
fi

# 5. pzopt overrides in the Proton dir (the harness Java side lives there; stock runs switch it off with --prop)
if [[ -f "$ROOT/pzopt-installed.txt" ]]; then
  inst=$(sed -n 's/^# revision=\([^ ]*\) installed=\(.*\)$/\1 (\2)/p' "$ROOT/pzopt-installed.txt")
  built=$(sed -n 's/^revision=//p' "$REPO/build/classes/pzopt/build-info.properties" 2>/dev/null)
  warn "pzopt overrides in $ROOT are from an earlier install: $inst; build/classes is for ${built:-?}. Reinstall before the run: PZ_DIR=$ROOT scripts/pzopt.sh uninstall && PZ_DIR=$ROOT scripts/pzopt.sh install"
else
  warn "no pzopt overrides in $ROOT: PZ_DIR=$ROOT scripts/pzopt.sh install (needed for the harness even on stock runs)"
fi
[[ -f "$ROOT/pzopt.properties" ]] && warn "$ROOT/pzopt.properties exists ($(tr '\n' ' ' < "$ROOT/pzopt.properties")); run.sh replaces it for the run and restores it"

# 6. JVM / launcher JSON in the Proton dir
if [[ -L "$ROOT/jre64" ]]; then warn "$ROOT/jre64 -> $(readlink "$ROOT/jre64") (not the depot's JVM; Sep-15 Proton baselines ran on it)"; fi
[[ -f "$ROOT/ProjectZomboid64.json" ]] || warn "$ROOT/ProjectZomboid64.json missing (Steam restores it with the depot)"

# 7. MangoHud
[[ -f /usr/lib/mangohud/libMangoHud_opengl.so ]] && ok "libMangoHud_opengl.so present" || warn "libMangoHud_opengl.so missing"

if (( fail )); then echo "not ready for a Proton run"; exit 1; fi
echo "ready: PZ_DIR=$ROOT harness/run.sh --label proton-smoke-1 --mode verify   (then the bench/drive commands in docs/proton-run-prep-2026-09-19.md)"
