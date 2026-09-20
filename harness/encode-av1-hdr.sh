#!/usr/bin/env bash
# Re-encode a video as AV1 10-bit HDR (PQ / BT.2020), the format every published video uses.
#   harness/encode-av1-hdr.sh <in.mp4> <out.mp4> [width]
# An HDR input (transfer smpte2084) is kept as is, only scaled when a width is given; an SDR
# input (BT.709) is mapped to PQ/BT.2020 with SDR reference white at 203 nits (BT.2408), which
# is the same conversion stitch-quad.sh applies to its H.264 captures. Audio is copied.
# Also writes <out>.jpg (tone-mapped poster, 12 s in) unless NOJPG=1. CQ (default 22) sets the
# NVENC constant quality; the README copies committed to git use CQ=30 to stay small.
set -euo pipefail
in="$1"; out="$2"; width="${3:-}"
trc=$(ffprobe -v error -select_streams v:0 -show_entries stream=color_transfer -of csv=p=0 "$in")
vf=""
[[ -n "$width" ]] && vf="scale=${width}:-2:flags=lanczos,"
if [[ "$trc" == smpte2084 ]]; then
  vf="${vf}format=yuv420p10le"
else
  vf="${vf}format=yuv420p,setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=tv,zscale=t=linear:npl=203,format=gbrpf32le,zscale=pin=bt709:tin=linear:p=bt2020:t=smpte2084:m=bt2020nc:r=tv:npl=203,format=yuv420p10le"
fi
vf="${vf},setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv"
mkdir -p "$(dirname "$out")"
ffmpeg -hide_banner -v error -y -i "$in" -vf "$vf" -map 0:v:0 -map '0:a?' -c:a copy \
  -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq "${CQ:-22}" -b:v 0 -maxrate 100M -bufsize 200M \
  -pix_fmt p010le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv \
  -movflags +faststart+write_colr "$out"
if [[ "${NOJPG:-0}" != 1 ]]; then
  ffmpeg -hide_banner -v error -y -ss 12 -i "$out" -frames:v 1 -vf "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p" -q:v 2 "${out%.mp4}.jpg" || true
fi
echo "wrote $out ($(ffprobe -v error -select_streams v:0 -show_entries stream=codec_name,pix_fmt,color_transfer,width,height -of csv=p=0 "$out")) from $in ($trc)"
