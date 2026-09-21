#!/bin/bash
# Side-by-side of a stock and an optimized bench recording (the Louisville preset: downtown, zombie
# population maxed, see_all view), aligned at the route, each run's in-game overlay inset at
# half resolution (unreadable after the 2.67x downscale). Alignment: the game quits the instant the route
# ends and the capture goes black, a hard sync point in both captures (the recorder's file birth time
# + a start delay is ~0.3-0.6 s off and the capture drifts ~6 ms/s); the clip is [black - route_seconds - PRE,
# black). The numbers in the header come from analyze.py's overlay line (in-game overlay log, route window).
# HDR end to end: the captures are AV1 10-bit PQ / BT.2020 and so is the output (no tone-map); text
# colours are PQ code values (~60 % = comfortable white).
#
#   harness/stitch-louisville-sbs.sh <stock-label> <opt-label> [out.mp4]
#   harness/stitch-louisville-sbs.sh show-louisville-stock-3 show-louisville-opt-3
set -e
cd "$(dirname "$0")/.."
SL=${1:?stock label}; OL=${2:?opt label}
OUT=${3:-docs/media/louisville-horde-spin-stock-vs-optimized.mp4}
S=$(ls -d harness/runs/"$SL"-* | tail -1); O=$(ls -d harness/runs/"$OL"-* | tail -1)
PRE=2   # seconds before the route start; the clip ends where the capture goes black (the quit at the route end)
FONT=/usr/share/fonts/TTF/DejaVuSans-Bold.ttf
[ -f "$FONT" ] || FONT=$(fc-match -f '%{file}' 'DejaVu Sans:bold')
mkdir -p "$(dirname "$OUT")"

# per run: clip start and length in the capture, fps mean / p99 / frames over 33 ms, zombies at the route end
info() {
  local d=$1
  local birth re
  birth=$(stat -c %W "$d/recording.mp4"); re=$(grep '^route_end_epoch_ms=' "$d/pzopt-bench.out" | cut -d= -f2)
  python3 - "$d" "$birth" "$re" "$PRE" <<'PY'
import re, subprocess, sys
import numpy as np
d, birth, rend, pre = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), float(sys.argv[4])
bench = open(d + '/pzopt-bench.out').read()
route_s = float(re.search(r'^route_seconds=([\d.]+)', bench, re.M).group(1))
# the black-out: first 20 fps thumbnail whose mean luma drops under 8, searched 4 s either side of the estimate
guess = rend / 1000 - birth - 4
w, h = 64, 27
raw = subprocess.run(['ffmpeg', '-v', 'error', '-ss', f'{guess:.2f}', '-t', '8', '-i', d + '/recording.mp4',
                      '-vf', f'fps=20,scale={w}:{h},format=gray', '-f', 'rawvideo', '-'], capture_output=True, check=True).stdout
n = len(raw) // (w * h)
m = np.frombuffer(raw[:n * w * h], dtype=np.uint8).reshape(n, h, w).astype(float).mean(axis=(1, 2))
black = next(i for i in range(1, n) if m[i] < 8 and m[i - 1] >= 8)
black_t = guess + black / 20
start = black_t - route_s - pre
dur = black_t - start - 0.1
out = subprocess.run(['python3', 'harness/analyze.py', d], capture_output=True, text=True).stdout
ov = out[out.index('overlay:'):]
fps = re.search(r'([\d.]+) fps mean', ov).group(1)
p99 = re.search(r'p99 ([\d.]+)ms', ov).group(1)
over = re.search(r'>33ms: (\d+)', ov).group(1)
z = re.search(r'^zombies_loaded=(\d+)', bench, re.M).group(1)
print(f'{start:.2f} {dur:.2f} {fps} {p99} {over} {z}')
PY
}
read -r S0 SDUR SFPS SP99 SOVER SZ < <(info "$S")
read -r O0 ODUR OFPS OP99 OOVER OZ < <(info "$O")
DUR=$(python3 -c "print(min($SDUR, $ODUR))")
echo "stock $S: clip from ${S0}s, $SFPS fps, p99 $SP99 ms, $SOVER frames >33 ms, $SZ zombies"
echo "opt   $O: clip from ${O0}s, $OFPS fps, p99 $OP99 ms, $OOVER frames >33 ms, $OZ zombies; ${DUR}s"

ffmpeg -loglevel error -y \
  -ss "$S0" -t "$DUR" -i "$S/recording.mp4" -ss "$O0" -t "$DUR" -i "$O/recording.mp4" \
  -filter_complex "\
[0:v]format=yuv420p10le,split[s_full][s_ovl];[s_ovl]crop=1420:400:0:0,scale=710:200[s_o];\
[s_full]scale=1920:810[s_p];[s_p][s_o]overlay=12:84[s_x];\
[s_x]drawbox=y=0:h=70:w=1920:color=black@0.75:t=fill,\
drawtext=fontfile=$FONT:fontsize=34:fontcolor=0xb4b4b8:x=20:y=18:text='STOCK  (every optimization off, uncapped)   $SFPS fps mean, p99 $SP99 ms, $SOVER frames over 33 ms'[l];\
[1:v]format=yuv420p10le,split[o_full][o_ovl];[o_ovl]crop=1420:400:0:0,scale=710:200[o_o];\
[o_full]scale=1920:810[o_p];[o_p][o_o]overlay=12:84[o_x];\
[o_x]drawbox=y=0:h=70:w=1920:color=black@0.75:t=fill,\
drawtext=fontfile=$FONT:fontsize=34:fontcolor=0xb4b4b8:x=20:y=18:text='OPTIMIZED  (uncapped)   $OFPS fps mean, p99 $OP99 ms, $OOVER frames over 33 ms'[r];\
[l][r]hstack=inputs=2,drawbox=y=780:h=30:w=3840:color=black@0.75:t=fill,\
drawtext=fontfile=$FONT:fontsize=22:fontcolor=0x8a8a90:x=(w-text_w)/2:y=784:text='Project Zomboid B42.20  downtown Louisville, zombie population x4 (~$OZ zombies loaded), spinning walk, 5120x2160 max zoom, RTX 4090 / 9800X3D  -  PZ_Optimization 2026-09-20',setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv" \
  -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 22 -b:v 0 -maxrate 100M -bufsize 200M \
  -pix_fmt p010le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv \
  -movflags +faststart+write_colr -r 60 -an "$OUT"
ffmpeg -loglevel error -y -ss 14 -i "$OUT" -frames:v 1 -vf "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p" -q:v 2 "${OUT%.mp4}.jpg"
ls -la "$OUT" | awk '{print $5, $9}'
ffprobe -v error -show_entries format=duration:stream=width,height -of csv=p=0 "$OUT"
