#!/bin/bash
# Side-by-side of the two storm drive recordings (2026-09-21 storm parity pass: puddleVbo, puddleEarlyZ, rainSplashesFast, treeAppend), aligned at the car's motion onset, with the
# in-game overlay of each run inset at full resolution (it is unreadable after the 2.67x downscale).
# HDR end to end: the captures are AV1 10-bit PQ / BT.2020 and so is the output (no tone-map); text
# colours are PQ code values (~60 % = comfortable white).
set -e
cd "$(dirname "$0")/.."
S=harness/runs/sbs2-storm120-stock-1-20260921-152603/recording.mp4
O=harness/runs/sbs2-storm120-opt-1-20260921-152716/recording.mp4
S0=12.90   # 2 s before the stock motion onset (14.90, showcase-times.py)
O0=12.72   # 2 s before the optimized motion onset (14.72)
DUR=42
FONT=/usr/share/fonts/TTF/DejaVuSans-Bold.ttf
[ -f "$FONT" ] || FONT=$(fc-match -f '%{file}' 'DejaVu Sans:bold')
OUT=${1:-docs/media/drive-120kmh-storm-stock-vs-optimized-2026-09-21.mp4}
mkdir -p "$(dirname "$OUT")"
ffmpeg -loglevel error -y \
  -ss $S0 -t $DUR -i "$S" -ss $O0 -t $DUR -i "$O" \
  -filter_complex "\
[0:v]format=yuv420p10le,split[s_full][s_ovl];[s_ovl]crop=1000:330:0:0,scale=800:264[s_o];\
[s_full]scale=1920:810[s_p];[s_p][s_o]overlay=12:84[s_x];\
[s_x]drawbox=y=0:h=70:w=1920:color=black@0.75:t=fill,\
drawtext=fontfile=$FONT:fontsize=34:fontcolor=0xb4b4b8:x=20:y=18:text='STOCK  (every optimization off, in-game cap 300)   70 fps mean, p99 67 ms, 46 frames over 33 ms'[l];\
[1:v]format=yuv420p10le,split[o_full][o_ovl];[o_ovl]crop=1000:330:0:0,scale=800:264[o_o];\
[o_full]scale=1920:810[o_p];[o_p][o_o]overlay=12:84[o_x];\
[o_x]drawbox=y=0:h=70:w=1920:color=black@0.75:t=fill,\
drawtext=fontfile=$FONT:fontsize=34:fontcolor=0xb4b4b8:x=20:y=18:text='OPTIMIZED  (uncapped)   392 fps mean, p99 9.9 ms, 0 frames over 33 ms'[r];\
[l][r]hstack=inputs=2,drawbox=y=780:h=30:w=3840:color=black@0.75:t=fill,\
drawtext=fontfile=$FONT:fontsize=22:fontcolor=0x8a8a90:x=(w-text_w)/2:y=784:text='Project Zomboid B42.20  thunderstorm, 120 km/h, 5120x2160 max zoom, RTX 4090 / 9800X3D, no Steam  -  PZ_Optimization 2026-09-21',setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv" \
  -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 22 -b:v 0 -maxrate 100M -bufsize 200M \
  -pix_fmt p010le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv \
  -movflags +faststart+write_colr -r 60 -an "$OUT"
ffmpeg -loglevel error -y -ss 26 -i "$OUT" -frames:v 1 -vf "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p" -q:v 2 "${OUT%.mp4}.jpg"
ls -la "$OUT" | awk '{print $5, $9}'
ffprobe -v error -show_entries format=duration:stream=width,height -of csv=p=0 "$OUT"
