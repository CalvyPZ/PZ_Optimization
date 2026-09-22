#!/usr/bin/env bash
# Stitch the responsive-overlay recordings (2026-09-22) into one video: the same Rosewood spin route with the
# in-game overlay shown, at four window sizes. The 5120x2160 capture is the full screen shown at half size; the
# 1920x1080 / 1280x720 / 1024x768 captures were windowed (options.ini borderless=false, centered on the desktop)
# and are cropped to the game's client area and shown at 1:1, so each overlay appears at the pixel size a
# screen of that size would show it. HDR end to end (AV1 10-bit PQ / BT.2020, no tone-map; the poster .jpg is the
# only tone-mapped derivative). Each clip starts PRE seconds before the route start (showcase-times.py onset).
#
# Usage: harness/stitch-overlay-sizes.sh [out.mp4]
# Env:   RUN_5K RUN_1080 RUN_720 RUN_768   run labels (latest run dir of each)
#        PRE                               seconds before the route start (default 2)
set -euo pipefail
cd "$(dirname "$0")/.."
out="${1:-docs/media/overlay-responsive-4-screen-sizes.mp4}"
RUN_5K="${RUN_5K:-ovr-resp-5120x2160}"; RUN_1080="${RUN_1080:-ovr-resp4-1920x1080}"
RUN_720="${RUN_720:-ovr-resp4-1280x720}"; RUN_768="${RUN_768:-ovr-resp4-1024x768}"
PRE="${PRE:-2}"
FONT=/usr/share/fonts/noto/NotoSans-Bold.ttf
[ -f "$FONT" ] || FONT=$(fc-match -f '%{file}' 'DejaVu Sans:bold')

run() { ls -d harness/runs/$1-* | tail -1; }
# clip start (s into the capture) and the route length
info() {
  python3 - "$1" "$PRE" <<'PY'
import importlib.util, re, sys
d, pre = sys.argv[1], float(sys.argv[2])
spec = importlib.util.spec_from_file_location('st', 'harness/showcase-times.py'); st = importlib.util.module_from_spec(spec); spec.loader.exec_module(st)
opts = st.kv(d + '/run.opts'); sched = st.kv(d + '/pzopt-schedule.out')
guess = int(sched['route_start_epoch_ms']) / 1000 - int(opts['launch_epoch']) - 1.0
on, peak, base = st.onset(d + '/recording.mp4', guess)
if on is None:
    on = guess + 1.0  # a small window changes few pixels: fall back to the schedule
bench = open(d + '/pzopt-bench.out').read()
m = re.search(r'route (?:complete|done) in ([0-9.]+)s', open(d + '/console.txt', errors='replace').read())
route_s = float(m.group(1)) if m else float(re.search(r'^route_seconds=([\d.]+)', bench, re.M).group(1))
print(f'{max(0, on - pre):.2f}|{route_s + pre:.2f}')
PY
}
A=$(run "$RUN_5K"); B=$(run "$RUN_1080"); C=$(run "$RUN_720"); D=$(run "$RUN_768")
IFS='|' read -r SA LA < <(info "$A"); IFS='|' read -r SB LB < <(info "$B")
IFS='|' read -r SC LC < <(info "$C"); IFS='|' read -r SD LD < <(info "$D")
LEN=$(python3 -c "print(round(min($LA, $LB, $LC, $LD) - 0.5, 2))")
echo "5k $A from $SA; 1080 $B from $SB; 720 $C from $SC; 768 $D from $SD; clip ${LEN}s"

# canvas 5120x2600: title; row 1 = 5K at half size (2560x1080) | 1920x1080 at 1:1; row 2 = 1280x720 | 1024x768 at 1:1
W=5120; H=2600; TITLE_H=150; LABEL_H=64; GAP=40
Y1L=$TITLE_H; Y1=$((Y1L + LABEL_H)); Y2L=$((Y1 + 1080 + GAP)); Y2=$((Y2L + LABEL_H))
XA=$(( (W/2 - 2560) / 2 + 40 )); XB=$(( W/2 + (W/2 - 1920) / 2 ))
XC=$(( (W/2 - 1280) / 2 + 300 )); XD=$(( W/2 + (W/2 - 1024) / 2 - 300 ))
TXT=0xb4b4b8; DIM=0x8a8a90
label() { echo "drawtext=fontfile=$FONT:text='$1':fontsize=44:fontcolor=$TXT:x=$2+($3-tw)/2:y=$4+8"; }
filter="
color=c=0x060608:s=${W}x${H}:r=60:d=${LEN},format=yuv420p10le[bg];
[0:v]fps=60,format=yuv420p10le,scale=2560:1080:flags=lanczos,setsar=1[a];
[1:v]fps=60,format=yuv420p10le,crop=1920:1080:1600:540,setsar=1[b];
[2:v]fps=60,format=yuv420p10le,crop=1280:720:1920:720,setsar=1[c];
[3:v]fps=60,format=yuv420p10le,crop=1024:768:2048:696,setsar=1[d];
[bg][a]overlay=${XA}:${Y1}:shortest=1[v1];
[v1][b]overlay=${XB}:${Y1}[v2];
[v2][c]overlay=${XC}:${Y2}[v3];
[v3][d]overlay=${XD}:${Y2},
drawtext=fontfile=$FONT:text='Project Zomboid B42.20  \\|  PZ Optimization performance overlay, fitted to the screen size  \\|  Rosewood spin route, uncapped':fontsize=62:fontcolor=$TXT:x=(w-tw)/2:y=40,
$(label '5120x2160 (shown at half size)  -  CodeLarge, flame column 1400 px' $XA 2560 $Y1L),
$(label '1920x1080 (1-to-1)  -  CodeMedium, flame column a third of the width' $XB 1920 $Y1L),
$(label '1280x720 (1-to-1)  -  CodeSmall, flame column a third, frame graph dropped for height' $XC 1280 $Y2L),
$(label '1024x768 (1-to-1)  -  CodeSmall, graphs dropped for height' $XD 1024 $Y2L),
drawtext=fontfile=$FONT:text='overlayFont=auto picks the font by screen height; the frame graph, flame graph and tree give way in a fixed order and long lines end in ...':fontsize=38:fontcolor=$DIM:x=(w-tw)/2:y=h-th-80,
drawtext=fontfile=$FONT:text='Ryzen 7 9800X3D, RTX 4090, NVIDIA GL, 5120x2160 desktop; the smaller sizes are windows of that size cropped from the capture':fontsize=34:fontcolor=$DIM:x=(w-tw)/2:y=h-th-26,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]
"
mkdir -p "$(dirname "$out")"
ffmpeg -hide_banner -v error -y \
  -ss "$SA" -t "$LEN" -i "$A/recording.mp4" \
  -ss "$SB" -t "$LEN" -i "$B/recording.mp4" \
  -ss "$SC" -t "$LEN" -i "$C/recording.mp4" \
  -ss "$SD" -t "$LEN" -i "$D/recording.mp4" \
  -filter_complex "$filter" -map '[v]' -an \
  -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 22 -b:v 0 -maxrate 120M -bufsize 240M \
  -pix_fmt p010le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv \
  -movflags +faststart+write_colr -r 60 \
  "$out"
ffmpeg -hide_banner -v error -y -ss 10 -i "$out" -frames:v 1 -vf "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p" -q:v 2 "${out%%.mp4}.jpg" || true
ls -la "$out" | awk '{print $5, $9}'
ffprobe -v error -show_entries format=duration:stream=width,height,codec_name,color_transfer -of csv=p=0 "$out"
