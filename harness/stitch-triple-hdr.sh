#!/usr/bin/env bash
# Stitch three uncapped bench recordings (stock settings, all optimizations before the
# 2026-09-20 evening pass, all optimizations including it) into one 2:1 (3840x1920)
# quad-view video, kept in HDR end to end: the gpu-screen-recorder captures are AV1
# 10-bit PQ / BT.2020 and the output is encoded with NVENC AV1 10-bit with the same
# colour tags (no tone-map, unlike stitch-triple.sh which writes SDR H.264). Panels:
# stock top left, optimized-before top right, all optimizations bottom left, results
# bottom right. Each 5120x2160 recording is scaled to 1920x810. The on-screen numbers
# are the in-game pzopt overlay (F9), not MangoHud.
#
# Usage: harness/stitch-triple-hdr.sh [out.mp4]
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

out="${1:-docs/media/rosewood-spin-uncapped-stock-vs-optimized-vs-all-hdr.mp4}"
RUN_STOCK="${RUN_STOCK:-u400show-stock-2}"; RUN_OPT="${RUN_OPT:-u400show-prev-1}"; RUN_ALL="${RUN_ALL:-u400show-all-1}"
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
ST_STOCK="${ST_STOCK:-$(start "$RUN_STOCK")}"; ST_OPT="${ST_OPT:-$(start "$RUN_OPT")}"; ST_ALL="${ST_ALL:-$(start "$RUN_ALL")}"
LEN="${LEN:-28}"
RES_STOCK="${RES_STOCK:-stock}"; RES_OPT="${RES_OPT:-optimized before}"; RES_ALL="${RES_ALL:-all optimizations}"

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
[b3]drawtext=fontfile=$FONT:text='Project Zomboid B42  \\|  uncapped  \\|  stock vs optimized (before 2026-09-20 evening) vs all optimizations':fontsize=54:fontcolor=$TXT:x=(w-tw)/2:y=28,
$(label 'STOCK SETTINGS' "(${CW}-tw)/2" "${Y1L}+6"),
$(label 'OPTIMIZED  (before the 400 fps pass)' "${CW}+(${CW}-tw)/2" "${Y1L}+6"),
$(label 'ALL OPTIMIZATIONS  (2026-09-20 evening)' "(${CW}-tw)/2" "${Y2L}+6"),
$(label 'RESULTS  -  25 s route, ~59 chunks/s, no frame cap' "${CW}+(${CW}-tw)/2" "${Y2L}+6"),
$(ptext 'Teleport route south through Rosewood, max zoom, player facing spinning 90 deg/s' 40 34 $TXT),
$(ptext 'STOCK SETTINGS' 130 40 $RED),
$(ptext "$RES_STOCK" 180 34 $TXT),
$(ptext 'OPTIMIZED BEFORE THE PASS' 270 40 $AMB),
$(ptext "$RES_OPT" 320 34 $TXT),
$(ptext 'ALL OPTIMIZATIONS' 410 40 $GRN),
$(ptext "$RES_ALL" 460 34 $TXT),
$(ptext 'The pass added - offscreen UI (stock option), cutaway re-bakes only on change, cutaway wall prefilter,' 560 30 $DIM),
$(ptext 'lighting queries once per square and gated per chunk level, occlusion grid kept on lighting-only frames,' 600 30 $DIM),
$(ptext 'ambient zone memo, chunk hand-off budget. Same save, route, hardware; numbers from the in-game overlay.' 640 30 $DIM),
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
