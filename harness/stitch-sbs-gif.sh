#!/usr/bin/env bash
# README GIFs of the side-by-side video (harness/stitch-sbs.sh output), each under GitHub's
# 10 MB image limit, 830 px wide (GitHub's README column), 8 fps, 128-colour palette from
# frame differences, ordered dither:
#   <out>-load.gif   launch -> both worlds ready -> route start, real time (mostly static)
#   <out>-drive.gif  the first DRIVE seconds of the route, then a jump to the ROUTE COMPLETE lines
# A full-length GIF lands at 34-54 MB, so the drive part is the cut that makes it fit
# (2026-09-19: load 21 s = 1.1 MB, drive 10 s + 3.8 s tail = 9.4 MB).
#
# Usage: harness/stitch-sbs-gif.sh [in.mp4] [out-prefix]
set -euo pipefail
cd "$(dirname "$0")/.."
in="${1:-docs/media/drive-120kmh-stock-244cap-vs-optimized-uncapped.mp4}"
prefix="${2:-${in%.mp4}}"
ROUTE=21.07; DRIVE="${DRIVE:-10.0}"; TAIL_FROM=59.6; END=63.43
gif() { # $1=filter producing [v]  $2=out
  local tmp; tmp=$(mktemp --suffix=.mp4)
  ffmpeg -hide_banner -v error -y -i "$in" -filter_complex "$1" -map '[v]' -an -c:v h264_nvenc -cq 19 "$tmp"
  ffmpeg -hide_banner -v error -y -i "$tmp" -filter_complex "
[0:v]fps=8,scale=830:-1:flags=lanczos,split[a][b];
[a]palettegen=max_colors=128:stats_mode=diff[p];
[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" "$2"
  rm -f "$tmp"
  echo "wrote $2 ($(stat -c %s "$2") bytes, $(ffprobe -v error -show_entries format=duration -of csv=p=0 "$2") s)"
}
gif "[0:v]trim=0:${ROUTE},setpts=PTS-STARTPTS[v]" "${prefix}-load.gif"
gif "[0:v]trim=${ROUTE}:$(python3 -c "print(${ROUTE}+${DRIVE})"),setpts=PTS-STARTPTS[b];
[0:v]trim=${TAIL_FROM}:${END},setpts=PTS-STARTPTS[c];
[b][c]concat=n=2:v=1:a=0[v]" "${prefix}-drive.gif"
