#!/usr/bin/env bash
# Stitch the three uncapped thunderstorm spin-route recordings with a ringing house alarm (the stock
# Alarm.update pattern, a 600-radius world sound every frame: --flag sound=600 sound_fixed=true) into one
# 2:1 (3840x1920) quad-view video: the stock game (every optimization key off), all optimizations with
# worldSoundFast off (the stock addSound path), all optimizations (2026-09-22 world-sound pass). HDR end to end: the gpu-screen-recorder captures are AV1
# 10-bit PQ / BT.2020 and the output is encoded with NVENC AV1 10-bit with the same
# colour tags (no tone-map, unlike stitch-triple.sh which writes SDR H.264). Panels:
# stock game top left, optimized without the fix top right, all optimizations bottom left,
# results bottom right. Panes start 2 s before each run's camera motion onset (showcase-times.py). Each 5120x2160 recording is scaled to 1920x810. The on-screen numbers
# are the in-game pzopt overlay (F9), not MangoHud.
#
# Usage: harness/stitch-alarm-storm-triple.sh [out.mp4]
# Env:   RUN_STOCK RUN_OPT RUN_ALL   run labels (latest run dir of each is used)
#        ST_STOCK ST_OPT ST_ALL      clip start seconds (default: route start - 1.5 s,
#                                    from run.opts launch_epoch and pzopt-schedule.out;
#                                    the recorder starts ~1 s after launch_epoch)
#        LEN                         clip length (default 28)
#        RES_STOCK RES_OPT RES_ALL   result lines for the panel (from analyze.py)
#        HUD_W HUD_H HUD_SCALE       the in-game overlay sits top-left of each capture; that
#                                    region (default 1420x400 px) is cut out and pasted 1:1
#                                    scaled by HUD_SCALE (default 1.25) into the panel's top-left
#                                    corner, so the numbers stay readable after the 0.375x
#                                    panel scale
set -euo pipefail
cd "$(dirname "$0")/.."

out="${1:-docs/media/bench-storm-house-alarm-stock-vs-optimized-vs-fixed.mp4}"
RUN_STOCK="${RUN_STOCK:-alarm-storm-rec-stockgame}"; RUN_OPT="${RUN_OPT:-alarm-storm-rec-stock}"; RUN_ALL="${RUN_ALL:-alarm-storm-rec-fast}"
FONT=/usr/share/fonts/noto/NotoSans-Bold.ttf

run() { ls -d harness/runs/$1-* | tail -1; }
start() {
  local d; d=$(run "$1")
  python3 - "$d" <<'PY'
import sys
d=sys.argv[1]
le=int([l for l in open(d+'/run.opts') if l.startswith('launch_epoch=')][0].split('=')[1])
rs=int([l for l in open(d+'/pzopt-schedule.out') if l.startswith('route_start_epoch_ms=')][0].split('=')[1])
print(f"{max(0.0, rs/1000 - le - 1.0 - 1.5):.2f}")
PY
}
S=$(run "$RUN_STOCK")/recording.mp4; O=$(run "$RUN_OPT")/recording.mp4; A=$(run "$RUN_ALL")/recording.mp4
ST_STOCK="${ST_STOCK:-21.18}"; ST_OPT="${ST_OPT:-14.43}"; ST_ALL="${ST_ALL:-13.46}"   # onset - 2 s: 23.18 / 16.43 / 15.46 (showcase-times.py)
LEN="${LEN:-29}"
RES_STOCK="${RES_STOCK:-38 fps mean  -  p99 105 ms  -  p99.9 132 ms  -  178 frames over 33 ms  -  1-percent low 8 fps}"
RES_OPT="${RES_OPT:-242 fps mean  -  p99 17.8 ms  -  p99.9 52.6 ms  -  16 frames over 33 ms  -  1-percent low 30 fps}"
RES_ALL="${RES_ALL:-290 fps mean  -  p99 14.1 ms  -  p99.9 28.2 ms  -  2 frames over 33 ms  -  1-percent low 50 fps}"

W=3840; H=1920; CW=1920; CH=810
HUD_W="${HUD_W:-1420}"; HUD_H="${HUD_H:-400}"; HUD_SCALE="${HUD_SCALE:-1.25}"
HW=$(python3 -c "print(int($HUD_W*$HUD_SCALE))"); HH=$(python3 -c "print(int($HUD_H*$HUD_SCALE))")
TITLE_H=110; LABEL_H=50; PAD=$(( (H - TITLE_H - 2*LABEL_H - 2*CH) / 3 ))
Y1L=$TITLE_H; Y1=$((Y1L + LABEL_H)); Y2L=$((Y1 + CH + PAD)); Y2=$((Y2L + LABEL_H))

# PQ-space colours: full white is the display's peak, so text uses ~60 % code values.
TXT=0xb4b4b8; DIM=0x8a8a90; RED=0xb85c5c; AMB=0xb89a5c; GRN=0x5cb878
cell() { # scaled panel with the 1:1 overlay region pasted over its top-left corner
  echo "[$1:v]fps=60,format=yuv420p10le,split[c$1a][c$1b];[c$1a]scale=${CW}:${CH}:flags=lanczos,setsar=1[p$1];[c$1b]crop=${HUD_W}:${HUD_H}:0:0,scale=${HW}:${HH}:flags=lanczos,setsar=1[h$1];[p$1][h$1]overlay=0:0"; }
label() { echo "drawtext=fontfile=$FONT:text='$1':fontsize=36:fontcolor=$TXT:x=$2:y=$3"; }
ptext() { echo "drawtext=fontfile=$FONT:text='$1':fontsize=$3:fontcolor=$4:x=${CW}+(${CW}-tw)/2:y=${Y2}+$2"; }

filter="
color=c=0x060608:s=${W}x${H}:r=60:d=${LEN},format=yuv420p10le[bg];
$(cell 0)[s];
$(cell 1)[o];
$(cell 2)[g];
[bg][s]overlay=0:${Y1}:shortest=1[b1];
[b1][o]overlay=${CW}:${Y1}[b2];
[b2][g]overlay=0:${Y2},drawbox=x=${CW}-2:y=0:w=4:h=${Y2}+${CH}:color=0x202026:t=fill,drawbox=x=${CW}:y=${Y2}:w=${CW}:h=${CH}:color=0x0c0c10:t=fill[b3];
[b3]drawtext=fontfile=$FONT:text='Project Zomboid B42.20  \\|  thunderstorm + a ringing house alarm  \\|  uncapped  \\|  stock vs optimized vs the world-sound fix':fontsize=50:fontcolor=$TXT:x=(w-tw)/2:y=28,
$(label 'STOCK GAME  (every optimization off)' "(${CW}-tw)/2" "${Y1L}+6"),
$(label 'ALL OPTIMIZATIONS, worldSoundFast OFF  (the stock addSound path)' "${CW}+(${CW}-tw)/2" "${Y1L}+6"),
$(label 'ALL OPTIMIZATIONS  (worldSoundFast on, 2026-09-22)' "(${CW}-tw)/2" "${Y2L}+6"),
$(label 'RESULTS  -  25 s route, lightning every 6 s, no frame cap' "${CW}+(${CW}-tw)/2" "${Y2L}+6"),
$(ptext 'Teleport route south through Rosewood in a thunderstorm, max zoom, spinning 90 deg/s, a house alarm ringing' 40 30 $TXT),
$(ptext 'STOCK GAME' 130 40 $RED),
$(ptext "$RES_STOCK" 180 34 $TXT),
$(ptext 'ALL OPTIMIZATIONS, WORLD-SOUND FIX OFF' 270 40 $AMB),
$(ptext "$RES_OPT" 320 34 $TXT),
$(ptext 'ALL OPTIMIZATIONS, WORLD-SOUND FIX ON' 410 40 $GRN),
$(ptext "$RES_ALL" 460 34 $TXT),
$(ptext 'Stock addSound walks (radius/3)^2 squares to scare fish and asks the cell for (2 radius/8)^2 chunks on every call,' 560 30 $DIM),
$(ptext 'and a ringing house alarm makes that call every frame - 0.47 ms per frame at radius 600, 9 percent of the game thread.' 600 30 $DIM),
$(ptext 'The fix walks only the loaded chunk grid and skips a repeat of an identical fish walk - 0.05 ms. Same save, route, hardware.' 640 30 $DIM),
drawtext=fontfile=$FONT:text='Ryzen 7 9800X3D, 32 GB DDR5 8000 MT/s, RTX 4090, 5120x2160, NVIDIA GL, uncapped  -  the overlay shows frame time and FPS live':fontsize=30:fontcolor=$DIM:x=(w-tw)/2:y=h-th-22,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]
"

mkdir -p "$(dirname "$out")"
ffmpeg -hide_banner -y \
  -ss "$ST_STOCK" -t "$LEN" -i "$S" \
  -ss "$ST_OPT"   -t "$LEN" -i "$O" \
  -ss "$ST_ALL"   -t "$LEN" -i "$A" \
  -filter_complex "$filter;[2:a]atrim=0:${LEN},loudnorm=I=-16:TP=-1.5:LRA=11,afade=t=out:st=$((LEN-3)):d=3[a]" -map '[v]' -map '[a]' -c:a aac -b:a 192k \
  -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 22 -b:v 0 -maxrate 100M -bufsize 200M \
  -pix_fmt p010le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv \
  -movflags +faststart+write_colr -r 60 \
  "$out"

ffmpeg -hide_banner -v error -y -ss 12 -i "$out" -frames:v 1 -vf "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p" -q:v 2 "${out%%.mp4}.jpg" || true
echo "clip starts: stock $ST_STOCK  opt $ST_OPT  all $ST_ALL"
echo "wrote $out (AV1 10-bit HDR PQ/BT.2020) and ${out%%.mp4}.jpg"
