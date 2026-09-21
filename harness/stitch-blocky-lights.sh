#!/bin/bash
# "Blocky lights" (2026-09-21): the maintainer's video showed a torch beam as a patchwork of stale chunk pieces. Two
# segments, each BEFORE | AFTER | STOCK, aligned at the harness route start (pzopt-schedule.out route_start_epoch_ms
# minus the recording start = file mtime - duration; +2 s on the torch segment, +6 s on the drive so the car is up to speed):
#   1. night-torch spinning bench at zoom 1 (bl-torch-opt / bl-torch-fix4 / bl-torch-stock), crop around the player;
#   2. 120 km/h night drive in a SportsCar at max zoom (bl-drive-before / bl-drive-fix4 / bl-drive-stock), crop ahead
#      of the car;
#   3. the same drive in an ambulance with headlights + lightbar mode 1 (bl-amb-before / bl-amb-fix / bl-amb-stock),
#      the maintainer's vehicle. "Before" is the same build with the fix keys at their old values
#      (lightingStrongDelta=100000 lightingGlobalDeltaPct=100 lightingFlush=false).
# HDR end to end: the captures are AV1 10-bit PQ / BT.2020 and so is the output (no tone-map); text colours are PQ
# code values (~60 % = comfortable white). Writes a tone-mapped poster jpg next to the mp4.
set -e
export LC_ALL=C
cd "$(dirname "$0")/.."
R=harness/runs
TB=$R/bl-torch-opt-20260921-174510/recording.mp4;    TB0=20.61
TA=$R/bl-torch-fix4-20260921-183357/recording.mp4;   TA0=20.47
TS=$R/bl-torch-stock-20260921-174650/recording.mp4;  TS0=31.70
DB=$R/bl-drive-before-20260921-192240/recording.mp4; DB0=23.71
DA=$R/bl-drive-fix4-20260921-183031/recording.mp4;   DA0=24.22
DS=$R/bl-drive-stock-20260921-183202/recording.mp4;  DS0=35.99
AB=$R/bl-amb-before-20260921-192737/recording.mp4;   AB0=23.42
AA=$R/bl-amb-fix-20260921-192908/recording.mp4;      AA0=23.74
AS=$R/bl-amb-stock-20260921-193033/recording.mp4;    AS0=35.07
DUR=20
FONT=/usr/share/fonts/TTF/DejaVuSans-Bold.ttf
[ -f "$FONT" ] || FONT=$(fc-match -f '%{file}' 'DejaVu Sans:bold')
OUT=${1:-docs/media/blocky-lights-before-vs-after.mp4}
mkdir -p "$(dirname "$OUT")"
# one panel: crop, scale to 1280x720, 70 px title bar
panel() { # in-label crop-x crop-y text
  echo "[$1]format=yuv420p10le,crop=2560:1440:$2:$3,scale=1280:720,pad=1280:790:0:70:black,drawbox=y=0:h=70:w=1280:color=black@0.75:t=fill,drawtext=fontfile=$FONT:fontsize=32:fontcolor=0xb4b4b8:x=20:y=18:text='$4'"
}
ffmpeg -loglevel error -y \
  -ss $TB0 -t $DUR -i "$TB" -ss $TA0 -t $DUR -i "$TA" -ss $TS0 -t $DUR -i "$TS" \
  -ss $DB0 -t $DUR -i "$DB" -ss $DA0 -t $DUR -i "$DA" -ss $DS0 -t $DUR -i "$DS" \
  -ss $AB0 -t $DUR -i "$AB" -ss $AA0 -t $DUR -i "$AA" -ss $AS0 -t $DUR -i "$AS" \
  -filter_complex "\
$(panel 0:v 1280 360 'BEFORE  held lighting re-bakes (up to 250 ms / 30 frames per chunk)')[t0];\
$(panel 1:v 1280 360 'AFTER  strong light changes re-bake at once (pzopt.LightDirt)')[t1];\
$(panel 2:v 1280 360 'STOCK  every optimization off')[t2];\
[t0][t1][t2]hstack=inputs=3,drawbox=y=760:h=30:w=3840:color=black@0.75:t=fill,\
drawtext=fontfile=$FONT:fontsize=22:fontcolor=0x8a8a90:x=(w-text_w)/2:y=764:text='1 of 3  -  hand torch, turning in place at 01\:00, zoom 1  -  Project Zomboid B42.20, 5120x2160, RTX 4090  -  PZ_Optimization 2026-09-21'[seg1];\
$(panel 3:v 1500 500 'BEFORE  held re-bakes + refresh queue losing dirty bits')[d0];\
$(panel 4:v 1500 500 'AFTER  LightDirt + queue flushed before each lighting pass')[d1];\
$(panel 5:v 1500 500 'STOCK  every optimization off')[d2];\
[d0][d1][d2]hstack=inputs=3,drawbox=y=760:h=30:w=3840:color=black@0.75:t=fill,\
drawtext=fontfile=$FONT:fontsize=22:fontcolor=0x8a8a90:x=(w-text_w)/2:y=764:text='2 of 3  -  headlights at 120 km/h, 01\:00, max zoom  -  Project Zomboid B42.20, 5120x2160, RTX 4090  -  PZ_Optimization 2026-09-21'[seg2];\
$(panel 6:v 1500 500 'BEFORE  held re-bakes + refresh queue losing dirty bits')[a0];\
$(panel 7:v 1500 500 'AFTER  LightDirt + queue flushed before each lighting pass')[a1];\
$(panel 8:v 1500 500 'STOCK  every optimization off')[a2];\
[a0][a1][a2]hstack=inputs=3,drawbox=y=760:h=30:w=3840:color=black@0.75:t=fill,\
drawtext=fontfile=$FONT:fontsize=22:fontcolor=0x8a8a90:x=(w-text_w)/2:y=764:text='3 of 3  -  ambulance, headlights + lightbar, 70 km/h, 01\\:00, max zoom  -  Project Zomboid B42.20, 5120x2160, RTX 4090  -  PZ_Optimization 2026-09-21'[seg3];\
[seg1][seg2][seg3]concat=n=3:v=1:a=0,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv" \
  -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 22 -b:v 0 -maxrate 100M -bufsize 200M \
  -pix_fmt p010le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv \
  -movflags +faststart+write_colr -r 60 -an "$OUT"
ffmpeg -loglevel error -y -ss 9 -i "$OUT" -frames:v 1 -vf "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p" -q:v 2 "${OUT%.mp4}.jpg"
ls -la "$OUT" | awk '{print $5, $9}'
ffprobe -v error -show_entries format=duration:stream=width,height,color_transfer -of csv=p=0 "$OUT"
