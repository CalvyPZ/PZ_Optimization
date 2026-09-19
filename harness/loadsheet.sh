#!/usr/bin/env bash
# Contact sheet of a run's recording around the load: frames at Continue +0/+3/+6/+9 s and at world ready
# +0.5/+3/+6/+9 s, written to <run>/loadsheet.jpg (8 tiles, 2 rows). The recording starts with the launch
# (run.opts launch_epoch); the instants come from pzopt-loadtrace.out (pzopt.LoadTrace).
#   harness/loadsheet.sh <run-dir>
set -euo pipefail
run="$1"
launch=$(sed -n 's/^launch_epoch=//p' "$run/run.opts")
cont=$(grep -m1 'continuing latest save' "$run/pzopt-loadtrace.out" | cut -f1)
ready=$(grep -m1 'harness: world ready' "$run/pzopt-loadtrace.out" | cut -f1)
[[ -n "$launch" && -n "$cont" && -n "$ready" ]] || { echo "missing launch_epoch / trace lines in $run" >&2; exit 1; }
c=$(python3 -c "print(($cont/1000.0)-$launch)")
r=$(python3 -c "print(($ready/1000.0)-$launch)")
tmp=$(mktemp -d)
i=0
for off in "$c+0" "$c+3" "$c+6" "$c+9" "$r+0.5" "$r+3" "$r+6" "$r+9"; do
  t=$(python3 -c "print(max(0.0,$off))")
  ffmpeg -v error -y -ss "$t" -i "$run/recording.mp4" -frames:v 1 -vf "scale=1280:-1,drawtext=text='t=${t%.*}s':x=20:y=20:fontsize=48:fontcolor=yellow:box=1:boxcolor=black@0.5" "$tmp/f$i.png" || ffmpeg -v error -y -ss "$t" -i "$run/recording.mp4" -frames:v 1 -vf "scale=1280:-1" "$tmp/f$i.png"
  i=$((i+1))
done
ffmpeg -v error -y -i "$tmp/f%d.png" -filter_complex "tile=4x2" -frames:v 1 -q:v 4 "$run/loadsheet.jpg"
rm -rf "$tmp"
echo "$run/loadsheet.jpg  (Continue at ${c}s, world ready at ${r}s of the recording)"
