#!/bin/bash
# 2x2 of four Louisville-preset bench recordings (downtown, zombie population maxed, see_all view), each
# aligned at its quit-to-black (the hard sync point: the game quits the instant the route ends), each
# run's in-game overlay inset at half resolution, a caption per tile with analyze.py's overlay numbers.
# Same recipe as stitch-louisville-sbs.sh, four tiles: HDR end to end (AV1 10-bit PQ / BT.2020 in and out).
#
#   harness/stitch-louisville-quad.sh <label1> <caption1> <label2> <caption2> <label3> <caption3> <label4> <caption4> [out.mp4]
#   tiles are top-left, top-right, bottom-left, bottom-right; a caption gets "  <fps> fps mean, p99 <ms> ms, <n> frames over 33 ms" appended
set -e
cd "$(dirname "$0")/.."
[ $# -ge 8 ] || { sed -n '2,9p' "$0"; exit 2; }
L=("$1" "$3" "$5" "$7"); C=("$2" "$4" "$6" "$8")
OUT=${9:-docs/media/louisville-horde-stock-vs-zbetterfps-vs-both.mp4}
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
# the black-out: first 20 fps thumbnail whose mean luma drops under 20 (the desktop behind the closed game is ~10 on a dark wallpaper), searched 4 s either side of the estimate
guess = rend / 1000 - birth - 4
w, h = 64, 27
raw = subprocess.run(['ffmpeg', '-v', 'error', '-ss', f'{guess:.2f}', '-t', '8', '-i', d + '/recording.mp4',
                      '-vf', f'fps=20,scale={w}:{h},format=gray', '-f', 'rawvideo', '-'], capture_output=True, check=True).stdout
n = len(raw) // (w * h)
m = np.frombuffer(raw[:n * w * h], dtype=np.uint8).reshape(n, h, w).astype(float).mean(axis=(1, 2))
black = next(i for i in range(1, n) if m[i] < 20 and m[i - 1] >= 20)
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

IN=(); FC=""; DUR=999; Z=0
for i in 0 1 2 3; do
  D=$(ls -d harness/runs/"${L[$i]}"-* | tail -1)
  read -r T0 TDUR FPS P99 OVER ZL < <(info "$D")
  echo "tile $i ${L[$i]}: $D clip from ${T0}s (${TDUR}s), $FPS fps, p99 $P99 ms, $OVER frames >33 ms, $ZL zombies"
  DUR=$(python3 -c "print(min($DUR, $TDUR))"); Z=$ZL
  IN+=(-ss "$T0" -i "$D/recording.mp4")
  TXT="${C[$i]}   $FPS fps mean, p99 $P99 ms, $OVER frames over 33 ms"
  FC+="[$i:v]format=yuv420p10le,split[f$i][v$i];[v$i]crop=1420:400:0:0,scale=710:200[o$i];[f$i]scale=1920:810[p$i];[p$i][o$i]overlay=12:84[x$i];"
  FC+="[x$i]drawbox=y=0:h=70:w=1920:color=black@0.75:t=fill,drawtext=fontfile=$FONT:fontsize=30:fontcolor=0xb4b4b8:x=20:y=20:text='$TXT'[t$i];"
done
echo "clip length ${DUR}s"
FC+="[t0][t1]hstack=inputs=2[top];[t2][t3]hstack=inputs=2[bot];[top][bot]vstack=inputs=2,"
FC+="drawbox=y=1590:h=30:w=3840:color=black@0.75:t=fill,"
FC+="drawtext=fontfile=$FONT:fontsize=22:fontcolor=0x8a8a90:x=(w-text_w)/2:y=1594:text='Project Zomboid B42.20  downtown Louisville, zombie population x4 (~$Z zombies loaded), spinning walk, 5120x2160 max zoom, uncapped, RTX 4090 / 9800X3D  -  PZ_Optimization $(date +%Y-%m-%d)',"
FC+="setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv"

ffmpeg -loglevel error -y "${IN[@]}" -t "$DUR" -filter_complex "$FC" \
  -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 22 -b:v 0 -maxrate 100M -bufsize 200M \
  -pix_fmt p010le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv \
  -movflags +faststart+write_colr -r 60 -an "$OUT"
ffmpeg -loglevel error -y -ss 14 -i "$OUT" -frames:v 1 -vf "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p" -q:v 2 "${OUT%.mp4}.jpg"
ls -la "$OUT" | awk '{print $5, $9}'
ffprobe -v error -show_entries format=duration:stream=width,height -of csv=p=0 "$OUT"
