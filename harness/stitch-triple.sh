#!/usr/bin/env bash
# Stitch three bench recordings (stock settings, optimized before the game-thread
# pass, optimized after it) into one 2:1 (3840x1920) quad-view video: stock top
# left, optimized (2026-09-19) top right, optimized + game-thread pass (2026-09-20)
# bottom left, and a results panel bottom right. Same cell geometry as
# stitch-quad.sh: each 5120x2160 recording is scaled to 1920x810.
#
# Usage: harness/stitch-triple.sh [out.mp4]
# Env:   RUN_STOCK RUN_OPT RUN_GT   run labels (latest run dir of each is used)
#        ST_STOCK ST_OPT ST_GT      seconds into each recording where its clip
#                                   begins. gpu-screen-recorder starts ~1 s after
#                                   run.opts launch_epoch, so the route starts at
#                                   (pzopt-schedule.out route_start_epoch_ms/1000 -
#                                   launch_epoch - 1) s; default = that minus 1.5 s
#        LEN                        clip length (default route + 3 s)
#        RES_STOCK RES_OPT RES_GT   result lines for the panel (from analyze.py)
set -euo pipefail
cd "$(dirname "$0")/.."

out="${1:-docs/media/rosewood-spin-stock-vs-optimized-vs-game-thread.mp4}"
RUN_STOCK="${RUN_STOCK:-gtshow-stock-2}"; RUN_OPT="${RUN_OPT:-gtshow-opt-1}"; RUN_GT="${RUN_GT:-gtshow-gt-1}"
FONT=/usr/share/fonts/noto/NotoSans-Bold.ttf

run() { ls -d harness/runs/$1-* | tail -1; }
start() { # route start in the recording minus 1.5 s lead
  local d; d=$(run "$1")
  python3 - "$d" <<'PY'
import sys
d=sys.argv[1]
le=int([l for l in open(d+'/run.opts') if l.startswith('launch_epoch=')][0].split('=')[1])
rs=int([l for l in open(d+'/pzopt-schedule.out') if l.startswith('route_start_epoch_ms=')][0].split('=')[1])
print(f"{rs/1000 - le - 1.0 - 1.5:.2f}")
PY
}
S=$(run "$RUN_STOCK")/recording.mp4; O=$(run "$RUN_OPT")/recording.mp4; G=$(run "$RUN_GT")/recording.mp4
ST_STOCK="${ST_STOCK:-$(start "$RUN_STOCK")}"; ST_OPT="${ST_OPT:-$(start "$RUN_OPT")}"; ST_GT="${ST_GT:-$(start "$RUN_GT")}"
LEN="${LEN:-28}"
RES_STOCK="${RES_STOCK:-105 fps mean   \\|   frame 9.5 ms   \\|   p99 28.3 ms   \\|   74 percent of frames below the 240 cap}"
RES_OPT="${RES_OPT:-197 fps mean   \\|   frame 5.1 ms   \\|   p99 18.0 ms   \\|   32 percent of frames below the 240 cap}"
RES_GT="${RES_GT:-226 fps mean   \\|   frame 4.4 ms   \\|   p99 11.6 ms   \\|   29 percent of frames below the 240 cap}"

W=3840; H=1920; CW=1920; CH=810
TITLE_H=110; LABEL_H=50; PAD=$(( (H - TITLE_H - 2*LABEL_H - 2*CH) / 3 ))
Y1L=$TITLE_H; Y1=$((Y1L + LABEL_H)); Y2L=$((Y1 + CH + PAD)); Y2=$((Y2L + LABEL_H))

# HDR end to end: the captures are AV1 10-bit PQ / BT.2020 and so is the output (no tone-map).
# PQ-space colours: full white is the display's peak, so text uses ~60 % code values.
TXT=0xb4b4b8; DIM=0x8a8a90; RED=0xb85c5c; AMB=0xb89a5c; GRN=0x5cb878
cell() { echo "[$1:v]scale=${CW}:${CH}:flags=lanczos,setsar=1,fps=60,format=yuv420p10le"; }
label() { echo "drawtext=fontfile=$FONT:text='$1':fontsize=36:fontcolor=$TXT:x=$2:y=$3"; }
ptext() { # $1=text $2=y $3=size $4=color
  echo "drawtext=fontfile=$FONT:text='$1':fontsize=$3:fontcolor=$4:x=${CW}+(${CW}-tw)/2:y=${Y2}+$2"
}

filter="
color=c=0x0d0d10:s=${W}x${H}:r=60:d=${LEN},format=yuv420p10le[bg];
$(cell 0)[s];
$(cell 1)[o];
$(cell 2)[g];
[bg][s]overlay=0:${Y1}:shortest=1[b1];
[b1][o]overlay=${CW}:${Y1}[b2];
[b2][g]overlay=0:${Y2},drawbox=x=${CW}-2:y=0:w=4:h=${Y2}+${CH}:color=0x303038:t=fill,drawbox=x=${CW}:y=${Y2}:w=${CW}:h=${CH}:color=0x16161c:t=fill[b3];
[b3]drawtext=fontfile=$FONT:text='Project Zomboid B42  \\|  game-thread pass  \\|  stock vs optimized vs optimized + game-thread pass':fontsize=56:fontcolor=$TXT:x=(w-tw)/2:y=28,
$(label 'STOCK SETTINGS' "(${CW}-tw)/2" "${Y1L}+6"),
$(label 'OPTIMIZED  (2026-09-19)' "${CW}+(${CW}-tw)/2" "${Y1L}+6"),
$(label 'OPTIMIZED + GAME-THREAD PASS  (2026-09-20)' "(${CW}-tw)/2" "${Y2L}+6"),
$(label 'RESULTS  -  25 s route, 55 chunks/s' "${CW}+(${CW}-tw)/2" "${Y2L}+6"),
$(ptext 'Teleport route south through Rosewood, max zoom, player facing spinning 90 deg/s' 40 34 $TXT),
$(ptext 'STOCK SETTINGS' 130 40 $RED),
$(ptext "$RES_STOCK" 180 34 $TXT),
$(ptext 'OPTIMIZED (2026-09-19)' 270 40 $AMB),
$(ptext "$RES_OPT" 320 34 $TXT),
$(ptext 'OPTIMIZED + GAME-THREAD PASS (2026-09-20)' 410 40 $GRN),
$(ptext "$RES_GT" 460 34 $TXT),
$(ptext 'What the pass added (all game thread) - weather-mask scan gate, re-bake budget for lighting / redraw / cutaway' 560 30 $DIM),
$(ptext 'changes, lighting-only re-bakes held 250 ms, cutaway radius 6 chunks, light-switch power check cached,' 600 30 $DIM),
$(ptext 'single-lookup Lua table reads, occluder masks kept on the chunk. Same save, route, hardware and MangoHud overlay.' 640 30 $DIM),
drawtext=fontfile=$FONT:text='Ryzen 7 9800X3D, 32 GB DDR5 8000 MT/s, RTX 4090, 5120x2160, NVIDIA GL, 240 fps cap  -  the HUD shows frame time and FPS live':fontsize=30:fontcolor=$DIM:x=(w-tw)/2:y=h-th-22,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]
"

mkdir -p "$(dirname "$out")"
ffmpeg -hide_banner -y \
  -ss "$ST_STOCK" -t "$LEN" -i "$S" \
  -ss "$ST_OPT"   -t "$LEN" -i "$O" \
  -ss "$ST_GT"    -t "$LEN" -i "$G" \
  -filter_complex "$filter;[2:a]atrim=0:${LEN},loudnorm=I=-16:TP=-1.5:LRA=11,afade=t=out:st=$((LEN-3)):d=3[a]" -map '[v]' -map '[a]' -c:a aac -b:a 192k \
  -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 22 -b:v 0 -maxrate 100M -bufsize 200M \
  -pix_fmt p010le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv \
  -movflags +faststart+write_colr -r 60 \
  "$out"

ffmpeg -hide_banner -v error -y -ss 12 -i "$out" -frames:v 1 -vf "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p" -q:v 2 "${out%%.mp4}.jpg"
echo "clip starts: stock $ST_STOCK  opt $ST_OPT  gt $ST_GT"
echo "wrote $out (AV1 10-bit HDR PQ/BT.2020) and ${out%%.mp4}.jpg"
