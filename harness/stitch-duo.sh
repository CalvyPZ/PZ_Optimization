#!/usr/bin/env bash
# Two-panel variant of stitch-tri.sh: the stock game (every optimization key off, overlay + game-thread profiler on)
# beside one optimized recording of the same scene, each 5120x2160 capture at half size (2560x1080) so the overlay's
# tree and flame graph stay readable, a results strip underneath. Same alignment (route motion onset), same result
# lines (analyze.py overlay line + game-thread block) and the same HDR chain (AV1 10-bit PQ / BT.2020, no tone-map;
# the poster .jpg is the only tone-mapped derivative) as stitch-tri.sh.
#
# Usage: harness/stitch-duo.sh <stock-label> <opt-label> <out.mp4>
# Env:   PRE (default 2)  WHAT  ROUTE  LABEL_STOCK  LABEL_OPT  TITLE_OPT  NOTE
set -euo pipefail
cd "$(dirname "$0")/.."
RUN_STOCK="${1:?stock run label}"; RUN_OPT="${2:?optimized run label}"; out="${3:?out.mp4}"
PRE="${PRE:-2}"
WHAT="${WHAT:-downtown Louisville, zombie population x4}"
ROUTE="${ROUTE:-Teleport into downtown Louisville, spectator view, max zoom, walking south at 6 tiles/s while the camera spins}"
LABEL_STOCK="${LABEL_STOCK:-STOCK GAME  (every optimization off, overlay + profiler on)}"
LABEL_OPT="${LABEL_OPT:-OPTIMIZED, WITH THE UNCOMMITTED ZOMBIE GAME-THREAD CHANGES OF 2026-09-22  (working tree)}"
TITLE_OPT="${TITLE_OPT:-OPTIMIZED, WITH THE ZOMBIE GAME-THREAD CHANGES}"
NOTE="${NOTE:-Optimized = the working tree of 2026-09-22 with its default keys (the opt-in zombie simulation LOD keys are off).}"
FONT=/usr/share/fonts/noto/NotoSans-Bold.ttf
[ -f "$FONT" ] || FONT=$(fc-match -f '%{file}' 'DejaVu Sans:bold')

run() { ls -d harness/runs/$1-* | tail -1; }
# The results strip mentions the JFR profile only when both runs recorded one (run.opts jfr=1).
JFR_NOTE=""
if grep -q '^jfr=1' "$(run "$RUN_STOCK")/run.opts" && grep -q '^jfr=1' "$(run "$RUN_OPT")/run.opts"; then
  JFR_NOTE="; both runs record a 1 ms JFR profile"
fi
info() {
  python3 - "$1" "$PRE" <<'PY'
import importlib.util, os, re, subprocess, sys
d, pre = sys.argv[1], float(sys.argv[2])
spec = importlib.util.spec_from_file_location('st', 'harness/showcase-times.py'); st = importlib.util.module_from_spec(spec); spec.loader.exec_module(st)
opts = st.kv(d + '/run.opts'); sched = st.kv(d + '/pzopt-schedule.out')
launch = int(opts['launch_epoch']); rs = int(sched['route_start_epoch_ms']) / 1000
guess = rs - launch - 1.0
on, peak, base = st.onset(d + '/recording.mp4', guess)
if on is None:
    raise SystemExit(f'{d}: no motion onset found near {guess:.1f} s (peak diff {peak})')
con = open(d + '/console.txt', errors='replace').read()
m = re.search(r'route (?:complete|done) in ([0-9.]+)s', con)
bench = open(d + '/pzopt-bench.out').read()
route_s = float(m.group(1)) if m else float(re.search(r'^route_seconds=([\d.]+)', bench, re.M).group(1))
out = subprocess.run(['python3', 'harness/analyze.py', d], capture_output=True, text=True).stdout
key = 'overlay:' if 'overlay:' in out else 'mangohud:'
ov = out[out.index(key):]
fps = re.search(r'([\d.]+) fps mean', ov).group(1)
p99 = re.search(r'p99 ([\d.]+)ms', ov).group(1)
p999 = re.search(r'p99\.9 ([\d.]+)ms', ov).group(1)
over = re.search(r'>33ms: (\d+)', ov).group(1)
low = re.search(r'1%-low (\d+) fps', ov).group(1)
gl = re.search(r'game_load (\d+)%', ov)
res = f'{fps} fps mean  -  p99 {p99} ms  -  p99.9 {p999} ms  -  {over} frames over 33 ms  -  1-percent low {low} fps'
prof = 'no game-thread profile'
if 'game thread (' in out:
    block = out[out.index('game thread ('):]
    block = block[:block.index('hottest methods')] if 'hottest methods' in block else block
    subs = []
    for l in block.splitlines()[1:]:
        ind = len(l) - len(l.lstrip())
        mm = re.match(r'\s+(\d+)%\s+(\S(?:.*?\S)?)(?:\s{2,}|\s+\[|$)', l)
        if mm and ind >= 6:
            subs.append((int(mm.group(1)), mm.group(2).strip()))
    subs.sort(key=lambda x: -x[0])
    top = ', '.join(f'{n} {p}' for p, n in subs[:3])
    prof = f'game thread {gl.group(1) if gl else "?"} pct busy  -  biggest {top} (pct of the game thread)'
z = re.search(r'^zombies_loaded=(\d+)', bench, re.M)
clean = lambda s: s.replace(':', ' ').replace("'", '').replace('%', ' pct')
print(f'{on - pre:.2f}|{route_s + pre:.2f}|{clean(res)}|{clean(prof)}|{z.group(1) if z else ""}')
PY
}
S=$(run "$RUN_STOCK"); A=$(run "$RUN_OPT")
IFS='|' read -r ST_STOCK D_STOCK RES_STOCK PROF_STOCK Z_STOCK < <(info "$S")
IFS='|' read -r ST_OPT D_OPT RES_OPT PROF_OPT Z_OPT < <(info "$A")
LEN=$(python3 -c "print(round(min($D_STOCK, $D_OPT) - 0.5, 2))")
echo "stock $S: from ${ST_STOCK}s: $RES_STOCK | $PROF_STOCK"
echo "opt   $A: from ${ST_OPT}s: $RES_OPT | $PROF_OPT; clip ${LEN}s"
zomb=""; [ -n "$Z_OPT" ] && zomb=", ~${Z_OPT} zombies loaded"

W=5120; CW=2560; CH=1080; TITLE_H=140; LABEL_H=60; RES_H=560; FOOT_H=90
Y1L=$TITLE_H; Y1=$((Y1L + LABEL_H)); YR=$((Y1 + CH + 20)); H=$((YR + RES_H + FOOT_H))

# PQ-space colours: full white is the display's peak, so text uses ~60 % code values.
TXT=0xb4b4b8; DIM=0x8a8a90; RED=0xb85c5c; GRN=0x5cb878
cell() { echo "[$1:v]fps=60,format=yuv420p10le,scale=${CW}:${CH}:flags=lanczos,setsar=1"; }
label() { echo "drawtext=fontfile=$FONT:text='$1':fontsize=44:fontcolor=$TXT:x=$2:y=$3"; }
ctext() { echo "drawtext=fontfile=$FONT:text='$1':fontsize=$3:fontcolor=$4:x=(w-tw)/2:y=${YR}+$2"; }

filter="
color=c=0x060608:s=${W}x${H}:r=60:d=${LEN},format=yuv420p10le[bg];
$(cell 0)[s];
$(cell 1)[g];
[bg][s]overlay=0:${Y1}:shortest=1[b1];
[b1][g]overlay=${CW}:${Y1},drawbox=x=${CW}-2:y=${Y1L}:w=4:h=${LABEL_H}+${CH}:color=0x202026:t=fill,drawbox=x=0:y=${YR}:w=${W}:h=${RES_H}:color=0x0c0c10:t=fill[b2];
[b2]drawtext=fontfile=$FONT:text='Project Zomboid B42.20  \\|  ${WHAT}  \\|  uncapped  \\|  stock vs optimized, game-thread profiler on screen':fontsize=62:fontcolor=$TXT:x=(w-tw)/2:y=36,
$(label "$LABEL_STOCK" "(${CW}-tw)/2" "${Y1L}+8"),
$(label "$LABEL_OPT" "${CW}+(${CW}-tw)/2" "${Y1L}+8"),
$(ctext "RESULTS  -  the route window${zomb}, no frame cap  -  ${ROUTE}" 30 38 $TXT),
$(ctext 'STOCK GAME' 110 50 $RED),
$(ctext "$RES_STOCK" 172 40 $TXT),
$(ctext "$PROF_STOCK" 222 34 $DIM),
$(ctext "$TITLE_OPT" 300 50 $GRN),
$(ctext "$RES_OPT" 362 40 $TXT),
$(ctext "$PROF_OPT" 412 34 $DIM),
$(ctext "Same save, route, settings and hardware${JFR_NOTE}. Stock = every optimization key off (the overlay needs the overrides loaded). $NOTE" 490 30 $DIM),
drawtext=fontfile=$FONT:text='RTX 4090, 5120x2160, NVIDIA GL, uncapped  -  each panel is the screen at half size  -  overlay numbers = the last 5 s, result lines = the whole route':fontsize=34:fontcolor=$DIM:x=(w-tw)/2:y=h-th-26,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]
"

mkdir -p "$(dirname "$out")"
ffmpeg -hide_banner -v error -y \
  -ss "$ST_STOCK" -t "$LEN" -i "$S/recording.mp4" \
  -ss "$ST_OPT"   -t "$LEN" -i "$A/recording.mp4" \
  -filter_complex "$filter;[1:a]atrim=0:${LEN},loudnorm=I=-16:TP=-1.5:LRA=11,afade=t=out:st=$(python3 -c "print(max(0, $LEN-3))"):d=3[a]" -map '[v]' -map '[a]' -c:a aac -b:a 192k \
  -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 22 -b:v 0 -maxrate 120M -bufsize 240M \
  -pix_fmt p010le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv \
  -movflags +faststart+write_colr -r 60 \
  "$out"

ffmpeg -hide_banner -v error -y -ss 12 -i "$out" -frames:v 1 -vf "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p" -q:v 2 "${out%%.mp4}.jpg" || true
ls -la "$out" | awk '{print $5, $9}'
ffprobe -v error -show_entries format=duration:stream=width,height,codec_name,color_transfer -of csv=p=0 "$out"
