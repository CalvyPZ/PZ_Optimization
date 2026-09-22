#!/bin/bash
# Side-by-side of the two E:400 teleport bench recordings in a thunderstorm with the stock helicopter event over the
# route (2026-09-22 world-sound pass: worldSoundFast off = the stock addSound path, on = chunk walk clamped to the loaded
# grid + exact fast fish walk), aligned at the camera's teleport onset (harness/showcase-times.py), with the in-game
# overlay of each run inset at full resolution (it is unreadable after the 2.67x downscale).
# HDR end to end: the captures are AV1 10-bit PQ / BT.2020 and so is the output (no tone-map); text
# colours are PQ code values (~60 % = comfortable white).
set -e
cd "$(dirname "$0")/.."
S=harness/runs/heli-storm-e400-stock-20260922-021726/recording.mp4
O=harness/runs/heli-storm-e400-fast-20260922-021825/recording.mp4
S0=13.20   # 2 s before the stock teleport onset (15.20, showcase-times.py)
O0=13.29   # 2 s before the fixed teleport onset (15.29)
DUR=27     # 2 s lead + 22.2 s route + hold
FONT=/usr/share/fonts/TTF/DejaVuSans-Bold.ttf
[ -f "$FONT" ] || FONT=$(fc-match -f '%{file}' 'DejaVu Sans:bold')
OUT=${1:-docs/media/bench-e400-storm-helicopter-stock-vs-fixed.mp4}
mkdir -p "$(dirname "$OUT")"
ffmpeg -loglevel error -y \
  -ss $S0 -t $DUR -i "$S" -ss $O0 -t $DUR -i "$O" \
  -filter_complex "\
[0:v]format=yuv420p10le,split[s_full][s_ovl];[s_ovl]crop=1000:330:0:0,scale=800:264[s_o];\
[s_full]scale=1920:810[s_p];[s_p][s_o]overlay=12:84[s_x];\
[s_x]drawbox=y=0:h=70:w=1920:color=black@0.75:t=fill,\
drawtext=fontfile=$FONT:fontsize=31:fontcolor=0xb4b4b8:x=20:y=18:text='STOCK addSound  (worldSoundFast off)   435 fps mean, p99 7.8 ms, 10 frames over 33 ms'[l];\
[1:v]format=yuv420p10le,split[o_full][o_ovl];[o_ovl]crop=1000:330:0:0,scale=800:264[o_o];\
[o_full]scale=1920:810[o_p];[o_p][o_o]overlay=12:84[o_x];\
[o_x]drawbox=y=0:h=70:w=1920:color=black@0.75:t=fill,\
drawtext=fontfile=$FONT:fontsize=31:fontcolor=0xb4b4b8:x=20:y=18:text='FIXED  (worldSoundFast on)   446 fps mean, p99 7.3 ms, 10 frames over 33 ms'[r];\
[l][r]hstack=inputs=2,drawbox=y=780:h=30:w=3840:color=black@0.75:t=fill,\
drawtext=fontfile=$FONT:fontsize=22:fontcolor=0x8a8a90:x=(w-text_w)/2:y=784:text='Project Zomboid B42.20  thunderstorm + helicopter event, E\\:400 teleport route at 18 tiles/s, 5120x2160 max zoom, RTX 4090 / 9800X3D  -  helicopter world sound 0.33 ms/call stock vs 0.10 ms fixed, ~16 calls per route  -  PZ_Optimization 2026-09-22',setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv" \
  -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 22 -b:v 0 -maxrate 100M -bufsize 200M \
  -pix_fmt p010le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv \
  -movflags +faststart+write_colr -r 60 -an "$OUT"
ffmpeg -loglevel error -y -ss 12 -i "$OUT" -frames:v 1 -vf "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p" -q:v 2 "${OUT%.mp4}.jpg"
ls -la "$OUT" | awk '{print $5, $9}'
ffprobe -v error -show_entries format=duration:stream=width,height -of csv=p=0 "$OUT"
