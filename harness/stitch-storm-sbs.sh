#!/bin/bash
# Side-by-side of the two storm drive recordings, aligned at the car's motion onset, with the
# in-game overlay of each run inset at full resolution (it is unreadable after the 2.67x downscale).
set -e
cd "$(dirname "$0")/.."
S=harness/runs/sbs-storm120-stock-20260920-170506/recording.mp4
O=harness/runs/sbs-storm120-opt-20260920-170623/recording.mp4
S0=12.75   # 2 s before the stock motion onset (14.75)
O0=12.48   # 2 s before the optimized motion onset (14.48)
DUR=42
FONT=/usr/share/fonts/TTF/DejaVuSans-Bold.ttf
[ -f "$FONT" ] || FONT=$(fc-match -f '%{file}' 'DejaVu Sans:bold')
OUT=${1:-docs/media/drive-120kmh-storm-stock-vs-optimized.mp4}
mkdir -p "$(dirname "$OUT")"
ffmpeg -loglevel error -y \
  -ss $S0 -t $DUR -i "$S" -ss $O0 -t $DUR -i "$O" \
  -filter_complex "\
[0:v]split[s_full][s_ovl];[s_ovl]crop=1000:330:0:0,scale=800:264[s_o];\
[s_full]scale=1920:810[s_p];[s_p][s_o]overlay=12:84[s_x];\
[s_x]drawbox=y=0:h=70:w=1920:color=black@0.75:t=fill,\
drawtext=fontfile=$FONT:fontsize=34:fontcolor=white:x=20:y=18:text='STOCK  (every optimization off, in-game cap 300)   71 fps mean, p99 67 ms, 42 frames over 33 ms'[l];\
[1:v]split[o_full][o_ovl];[o_ovl]crop=1000:330:0:0,scale=800:264[o_o];\
[o_full]scale=1920:810[o_p];[o_p][o_o]overlay=12:84[o_x];\
[o_x]drawbox=y=0:h=70:w=1920:color=black@0.75:t=fill,\
drawtext=fontfile=$FONT:fontsize=34:fontcolor=white:x=20:y=18:text='OPTIMIZED  (uncapped)   269 fps mean, p99 8.8 ms, 0 frames over 33 ms'[r];\
[l][r]hstack=inputs=2,drawbox=y=780:h=30:w=3840:color=black@0.75:t=fill,\
drawtext=fontfile=$FONT:fontsize=22:fontcolor=white:x=(w-text_w)/2:y=784:text='Project Zomboid B42.20  thunderstorm, 120 km/h, 5120x2160 max zoom, RTX 4090 / 9800X3D, no Steam  -  PZ_Optimization 2026-09-20'" \
  -r 60 -c:v libx264 -preset medium -crf 20 -pix_fmt yuv420p -an "$OUT"
ls -la "$OUT" | awk '{print $5, $9}'
ffprobe -v error -show_entries format=duration:stream=width,height -of csv=p=0 "$OUT"
