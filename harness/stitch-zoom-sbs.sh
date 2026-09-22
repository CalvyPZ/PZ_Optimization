#!/bin/bash
# Side-by-side of the two camera-zoom recordings (2026-09-22 zoom pass): 0.25 <-> 2.5 wheel spins every 3 s while the bench
# route teleport-drives south through Rosewood, uncapped. Left: the stock zoom paths on our build (zoomRetain=false: chunk
# textures freed off screen and re-baked the frame they reappear; zoomEaseMs=0: the stock 0.03-per-frame step and snap).
# Right: zoomRetain (textures kept, nearest-first re-bake plan) + the 300 ms cubic Bezier ease. Aligned at the start of the first zoom-out motion (frame
# differencing of 64x27 thumbnails at 30 fps; showcase-times.py's onset was 1.5 s early on these runs), the in-game overlay of each run inset at full resolution (unreadable after the 2.67x
# downscale). HDR end to end: AV1 10-bit PQ / BT.2020 in and out, text colours are PQ code values.
set -e
cd "$(dirname "$0")/.."
S=harness/runs/zoom-rec-stock-20260922-050617/recording.mp4
O=harness/runs/zoom-rec-new-20260922-051032/recording.mp4
S0=14.0    # the first zoom-out starts moving at 19.0 (frame differencing; stock takes ~1.5 s for the 9 levels) = route start + 3 s; 2 s of lead
O0=14.7    # the first zoom-out starts moving at 19.7 (the 300 ms Bezier ends at 20.0)
DUR=30     # 2 s lead + 25 s route + hold
FONT=/usr/share/fonts/TTF/DejaVuSans-Bold.ttf
[ -f "$FONT" ] || FONT=$(fc-match -f '%{file}' 'DejaVu Sans:bold')
OUT=${1:-docs/media/bench-zoom-spin-stock-vs-new.mp4}
mkdir -p "$(dirname "$OUT")"
ffmpeg -loglevel error -y \
  -ss $S0 -t $DUR -i "$S" -ss $O0 -t $DUR -i "$O" \
  -filter_complex "\
[0:v]format=yuv420p10le,split[s_full][s_ovl];[s_ovl]crop=1000:330:0:0,scale=800:264[s_o];\
[s_full]scale=1920:810[s_p];[s_p][s_o]overlay=12:84[s_x];\
[s_x]drawbox=y=0:h=70:w=1920:color=black@0.75:t=fill,\
drawtext=fontfile=$FONT:fontsize=29:fontcolor=0xb4b4b8:x=20:y=19:text='STOCK ZOOM  (textures freed off screen, 0.03/frame step + snap)   469 fps, p99 9.7 ms, p99.9 27.2 ms, 6 frames > 33 ms'[l];\
[1:v]format=yuv420p10le,split[o_full][o_ovl];[o_ovl]crop=1000:330:0:0,scale=800:264[o_o];\
[o_full]scale=1920:810[o_p];[o_p][o_o]overlay=12:84[o_x];\
[o_x]drawbox=y=0:h=70:w=1920:color=black@0.75:t=fill,\
drawtext=fontfile=$FONT:fontsize=29:fontcolor=0xb4b4b8:x=20:y=19:text='NEW  (textures kept, nearest-first re-bake plan, 300 ms Bezier ease)   520 fps, p99 8.6 ms, p99.9 25.2 ms, 3 frames > 33 ms'[r];\
[l][r]hstack=inputs=2,drawbox=y=780:h=30:w=3840:color=black@0.75:t=fill,\
drawtext=fontfile=$FONT:fontsize=22:fontcolor=0x8a8a90:x=(w-text_w)/2:y=784:text='Project Zomboid B42.20  camera zoom 0.25 <-> 2.5 every 3 s (a full wheel spin) while driving south through Rosewood, 5120x2160 uncapped, RTX 4090 / 9800X3D  -  zoomRetain + zoomEase  -  PZ_Optimization 2026-09-22',setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv" \
  -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 22 -b:v 0 -maxrate 100M -bufsize 200M \
  -pix_fmt p010le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv \
  -movflags +faststart+write_colr -r 60 -an "$OUT"
ffmpeg -loglevel error -y -ss 5.6 -i "$OUT" -frames:v 1 -vf "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p" -q:v 2 "${OUT%.mp4}.jpg"
ls -la "$OUT" | awk '{print $5, $9}'
ffprobe -v error -show_entries format=duration:stream=width,height -of csv=p=0 "$OUT"
