#!/usr/bin/env bash
# Before / after video of the vehicle-spawn bug (Workshop reports 2026-09-23: "cars barely spawn"): two recorded
# bench runs over the same never-visited parking lot (Louisville airport, car_spawn=5 = High, zombies off,
# see_all), one with the released IsoChunk (Vineflower's AddVehicles_OnZone: one row of stalls per chunk, High
# = a flat 2 %) and one with the fix. Side by side at half size, aligned on the route's motion onset, a results
# strip with each run's vehicles_loaded (pzopt-bench.out). Same HDR chain as stitch-duo.sh (AV1 10-bit PQ /
# BT.2020, no tone-map; the poster .jpg is the only tone-mapped derivative). Run it through the queue (media).
#
# Usage: harness/stitch-vehspawn.sh <before-run-dir> <after-run-dir> <out.mp4>
# Env:   PRE (default 4: seconds of the still lot before the route starts)
set -euo pipefail
cd "$(dirname "$0")/.."
B="${1:?before run dir}"; A="${2:?after run dir}"; out="${3:?out.mp4}"
PRE="${PRE:-4}"
FONT=/usr/share/fonts/noto/NotoSans-Bold.ttf
[ -f "$FONT" ] || FONT=$(fc-match -f '%{file}' 'DejaVu Sans:bold')

info() {
  python3 - "$1" "$PRE" <<'PY'
import importlib.util, re, sys
d, pre = sys.argv[1], float(sys.argv[2])
spec = importlib.util.spec_from_file_location('st', 'harness/showcase-times.py'); st = importlib.util.module_from_spec(spec); spec.loader.exec_module(st)
opts = st.kv(d + '/run.opts'); sched = st.kv(d + '/pzopt-schedule.out')
launch = int(opts['launch_epoch']); rs = int(sched['route_start_epoch_ms']) / 1000
on, peak, base = st.onset(d + '/recording.mp4', rs - launch - 1.0)
if on is None:
    raise SystemExit(f'{d}: no motion onset found (peak diff {peak})')
con = open(d + '/console.txt', errors='replace').read()
route_s = float(re.search(r'route (?:complete|done) in ([0-9.]+)s', con).group(1))
bench = open(d + '/pzopt-bench.out').read()
veh = re.search(r'^vehicles_loaded=(\d+)', bench, re.M).group(1)
rate = re.search(r'^car_spawn=(\d+)', bench, re.M).group(1)
print(f'{on - pre:.2f}|{route_s + pre:.2f}|{veh}|{rate}')
PY
}
IFS='|' read -r ST_B D_B VEH_B RATE_B < <(info "$B")
IFS='|' read -r ST_A D_A VEH_A RATE_A < <(info "$A")
[ "$RATE_B" = "$RATE_A" ] || { echo "car_spawn differs: $RATE_B vs $RATE_A" >&2; exit 1; }
LEN=$(python3 -c "print(round(min($D_B, $D_A) + 0.5, 2))")
echo "before $B: from ${ST_B}s, $VEH_B vehicles | after $A: from ${ST_A}s, $VEH_A vehicles; clip ${LEN}s"

W=5120; CW=2560; CH=1080; TITLE_H=150; LABEL_H=70; RES_H=330; FOOT_H=80
Y1L=$TITLE_H; Y1=$((Y1L + LABEL_H)); YR=$((Y1 + CH + 20)); H=$((YR + RES_H + FOOT_H))

# PQ-space colours: full white is the display's peak, so text uses ~60 % code values.
TXT=0xb4b4b8; DIM=0x8a8a90; RED=0xb85c5c; GRN=0x5cb878
cell() { echo "[$1:v]fps=60,format=yuv420p10le,scale=${CW}:${CH}:flags=lanczos,setsar=1"; }
text() { echo "drawtext=fontfile=$FONT:text='$1':fontsize=$2:fontcolor=$3:x=$4:y=$5"; }

filter="
color=c=0x060608:s=${W}x${H}:r=60:d=${LEN},format=yuv420p10le[bg];
$(cell 0)[b];
$(cell 1)[a];
[bg][b]overlay=0:${Y1}:shortest=1[v1];
[v1][a]overlay=${CW}:${Y1},drawbox=x=${CW}-2:y=${Y1L}:w=4:h=${LABEL_H}+${CH}:color=0x202026:t=fill,drawbox=x=0:y=${YR}:w=${W}:h=${RES_H}:color=0x0c0c10:t=fill[v2];
[v2]$(text 'PZ Optimization  \\|  parked cars on a never-visited lot  \\|  Louisville airport, sandbox car spawn rate HIGH' 62 $TXT '(w-tw)/2' 40),
$(text 'BEFORE  -  the released mod (IsoChunk.AddVehicles_OnZone as decompiled)' 46 $RED "(${CW}-tw)/2" "${Y1L}+10"),
$(text 'AFTER  -  with the fix (matches the game jar)' 46 $GRN "${CW}+(${CW}-tw)/2" "${Y1L}+10"),
$(text "${VEH_B} vehicles loaded" 84 $RED "(${CW}-tw)/2" "${YR}+40"),
$(text "${VEH_A} vehicles loaded" 84 $GRN "${CW}+(${CW}-tw)/2" "${YR}+40"),
$(text 'only the first row of parking stalls in each chunk was filled, and HIGH gave every stall a flat 2 pct chance' 36 $DIM "(${CW}-tw)/2" "${YR}+160"),
$(text 'every row filled, HIGH doubles each car type spawn chance, as in the unmodded game' 36 $DIM "${CW}+(${CW}-tw)/2" "${YR}+160"),
$(text 'Two decompiler errors in a method the mod never meant to change (a loop that stopped after one row, x2 rendered as 2). Cars spawn once per chunk, so lots already visited stay as they are.' 32 $DIM '(w-tw)/2' "${YR}+250"),
drawtext=fontfile=$FONT:text='Same save copy, spot, settings and hardware  -  zombies removed, whole lot revealed  -  each panel is the 5120x2160 screen at half size  -  vehicle counts = the loaded cell at route end':fontsize=32:fontcolor=$DIM:x=(w-tw)/2:y=h-th-24,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]
"

mkdir -p "$(dirname "$out")"
ffmpeg -hide_banner -v error -y \
  -ss "$ST_B" -t "$LEN" -i "$B/recording.mp4" \
  -ss "$ST_A" -t "$LEN" -i "$A/recording.mp4" \
  -filter_complex "$filter" -map '[v]' -an \
  -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 22 -b:v 0 -maxrate 120M -bufsize 240M \
  -pix_fmt p010le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv \
  -movflags +faststart+write_colr -r 60 \
  "$out"

ffmpeg -hide_banner -v error -y -ss 3 -i "$out" -frames:v 1 -vf "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p" -q:v 2 "${out%%.mp4}.jpg" || true
ls -la "$out" | awk '{print $5, $9}'
ffprobe -v error -show_entries format=duration:stream=width,height,codec_name,color_transfer -of csv=p=0 "$out"
