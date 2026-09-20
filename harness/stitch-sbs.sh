#!/usr/bin/env bash
# Side-by-side video of one stock and one optimized 120 km/h drive recording, from the
# game window appearing through boot, load and the E:1200 route: stock (244 fps cap, the
# game's maximum) on the left, all optimizations uncapped on the right. Both panes start
# together at their own launch; live BOOT / LOAD counters run under each pane and freeze
# on the measured totals; the optimized pane holds its frame at its route start until the
# stock run has loaded, then both drive the route in sync. A hardware panel sits below.
#
# Usage: harness/stitch-sbs.sh [out.mp4]
#
# Run selection (2026-09-19 23:00): sbs-stock120-1 (every pzopt runtime/boot flag off,
# --option frameRate=244, config/mangohud-showcase-stock.conf) and sbs-opt120-uncap-1
# (build defaults, --prop uncappedFps=true, config/mangohud-showcase-opt.conf).
#
# Timing constants below are recording (video) seconds. The recordings are monitor captures
# started right after `date +%s` in run.sh, so launch_epoch is only whole-second accurate;
# the sub-second offset between the recording and the log epochs was read from the MangoHud
# clock in the HUD (the frame in which its seconds digit changes is an integer epoch second;
# crop of the seconds digits + tblend difference), giving video_t = (epoch - launch_epoch)
# - 0.60 s for the stock run and - 0.95 s for the optimized run at the route start (the
# capture drifts ~6 ms/s against the wall clock, so the offset was taken near the route).
# Log epochs: pzopt-loadtrace.out (first line, "continuing latest save", "harness: world
# ready") and pzopt-schedule.out (route_start_epoch_ms); route time from console.txt.
# The first ~2.3 s of each capture show the desktop before the game window appears: the
# panes start after that (S_START / O_START), never earlier.
# Audio is the game's main-menu theme, not the runs' sound: stream 138
# (mx_menu_the_zombie_threat_ducked, 1:32) of media/sound/banks/Desktop/ZomboidMusic.bank,
# extracted once with vgmstream (~/.local/bin/vgmstream-cli, prebuilt r2117):
#   vgmstream-cli -s 138 -i -o ~/Zomboid/pzopt/menu-138.wav <game>/media/sound/banks/Desktop/ZomboidMusic.bank
# It stays outside the repo (game content).
set -euo pipefail
cd "$(dirname "$0")/.."

out="${1:-docs/media/drive-120kmh-stock-244cap-vs-optimized-uncapped.mp4}"
S=harness/runs/sbs-stock120-1-20260919-230045/recording.mp4
O=harness/runs/sbs-opt120-uncap-1-20260919-230229/recording.mp4
MUSIC="${MUSIC:-$HOME/Zomboid/pzopt/menu-138.wav}"
[[ -f "$MUSIC" ]] || { echo "missing $MUSIC (see header for the vgmstream extraction)" >&2; exit 1; }

# stock: video seconds of window-appear, first log line, Continue, world ready, route start
S_START=2.40; S_LOG=2.41; S_CONT=9.73; S_READY=18.47; S_ROUTE=23.47; S_ROUTE_LEN=38.76
S_BOOT=7.31; S_LOAD=8.74; S_FPS=122; S_P99=18.9
# optimized
O_START=2.30; O_LOG=2.11; O_CONT=8.12; O_READY=11.80; O_ROUTE=16.80; O_ROUTE_LEN=38.63
O_BOOT=6.01; O_LOAD=3.68; O_FPS=412; O_P99=6.3
TAIL=3.6   # seconds held after the stock route completes

f() { python3 -c "print(round($1, 3))"; }
T_R=$(f "$S_ROUTE - $S_START")            # output time of the (shared) route start
O_ARRIVE=$(f "$O_ROUTE - $O_START")       # when the optimized pane reaches its route start
HOLD=$(f "$T_R - $O_ARRIVE")              # how long it waits for the stock run
LEN=$(f "$T_R + $S_ROUTE_LEN + $TAIL")
S_DONE=$(f "$T_R + $S_ROUTE_LEN"); O_DONE=$(f "$T_R + $O_ROUTE_LEN")
SB0=$(f "$S_LOG - $S_START"); SL0=$(f "$S_CONT - $S_START")
OB0=$(f "$O_LOG - $O_START"); OL0=$(f "$O_CONT - $O_START")
OWAIT=$(f "$O_READY - $O_START")

FONT=/usr/share/fonts/noto/NotoSans-Bold.ttf
MONO=/usr/share/fonts/noto/NotoSansMono-Bold.ttf
W=3840; H=1450; CW=1920; CH=810; PY=170
# PQ-space colours (the output is HDR): full white is the display's peak, so ~60 % code values
GREY=0x8a8a90; WHITE=0xb4b4b8; STOCK_C=0xb88a40; OPT_C=0x40b840

# HDR (PQ / BT.2020, AV1 10-bit) capture kept as is: the output is HDR too (no tone-map)
tm="scale=${CW}:${CH}:flags=lanczos,format=yuv420p10le,fps=60,setsar=1"

txt() { # $1=text $2=x $3=y $4=size $5=color [$6=font] [$7=extra]
  echo "drawtext=fontfile=${6:-$FONT}:text='$1':fontsize=$4:fontcolor=$5:x=$2:y=$3${7:+:$7}"
}
# live counter: value = clamp(t - start, 0, total), shown as s.ss while running (white)
counter() { # $1=start $2=total $3=x $4=y $5=final-color
  local v="min(max(t-($1)\,0)\,$2)"
  echo "drawtext=fontfile=$MONO:fontsize=54:fontcolor=$WHITE:x=$3:y=$4:text='%{eif\:trunc($v)\:d}.%{eif\:trunc(mod($v*100\,100))\:d\:2} s':enable='lt(t,($1)+$2)',"
  echo "drawtext=fontfile=$MONO:fontsize=54:fontcolor=$5:x=$3:y=$4:text='$2 s':enable='gte(t,($1)+$2)'"
}
VX_S=$((CW-90)); VX_O=$((2*CW-90))   # right edges of the counter values
LY1=$((PY+CH+32)); LY2=$((LY1+66)); LY3=$((LY2+70))

filter="
color=c=0x0d0d10:s=${W}x${H}:r=60:d=${LEN},format=yuv420p10le[bg];
[0:v]${tm}[s];
[1:v]split[o1][o2];
[o1]trim=0:${O_ARRIVE},setpts=PTS-STARTPTS,tpad=stop_mode=clone:stop_duration=${HOLD}[oA];
[o2]trim=start=${O_ARRIVE},setpts=PTS-STARTPTS[oB];
[oA][oB]concat=n=2:v=1:a=0,${tm}[o];
[bg][s]overlay=0:${PY}:shortest=1[b1];
[b1][o]overlay=${CW}:${PY},drawbox=x=${CW}-2:y=${PY}:w=4:h=${CH}:color=0x303038:t=fill[b2];
[b2]$(txt 'Project Zomboid B42  \|  120 km/h drive  \|  stock (244 fps cap) vs all optimizations (uncapped)' '(w-tw)/2' 26 58 $WHITE),
$(txt 'STOCK   -   244 fps cap (game maximum)' "(${CW}-tw)/2" 112 40 $STOCK_C),
$(txt 'OPTIMIZED   -   uncapped' "${CW}+(${CW}-tw)/2" 112 40 $OPT_C),
$(txt 'BOOT   launch to main menu' 90 $LY1 44 $GREY),
$(txt 'LOAD   Continue to world ready' 90 $LY2 44 $GREY),
$(txt 'BOOT   launch to main menu' $((CW+90)) $LY1 44 $GREY),
$(txt 'LOAD   Continue to world ready' $((CW+90)) $LY2 44 $GREY),
$(counter $SB0 $S_BOOT "${VX_S}-tw" $LY1 $STOCK_C),
$(counter $SL0 $S_LOAD "${VX_S}-tw" $LY2 $STOCK_C),
$(counter $OB0 $O_BOOT "${VX_O}-tw" $LY1 $OPT_C),
$(counter $OL0 $O_LOAD "${VX_O}-tw" $LY2 $OPT_C),
$(txt 'world ready  -  waiting for the stock run to load...' "${CW}+90" $LY3 40 $OPT_C '' "enable='between(t,${OWAIT},${T_R})'"),
$(txt 'loading...' 90 $LY3 40 $GREY '' "enable='between(t,${SL0},${T_R})'"),
$(txt "ROUTE COMPLETE   1200 m in ${S_ROUTE_LEN} s   ·   avg ${S_FPS} fps   ·   p99 frame ${S_P99} ms" 90 $LY3 40 $STOCK_C '' "enable='gte(t,${S_DONE})'"),
$(txt "ROUTE COMPLETE   1200 m in ${O_ROUTE_LEN} s   ·   avg ${O_FPS} fps   ·   p99 frame ${O_P99} ms" "${CW}+90" $LY3 40 $OPT_C '' "enable='gte(t,${O_DONE})'"),
drawbox=x=0:y=$((LY3+70)):w=${W}:h=2:color=0x303038:t=fill,
$(txt 'CPU    AMD Ryzen 7 9800X3D  ·  8 cores / 16 threads  ·  up to 5.45 GHz' 260 $((LY3+108)) 40 $WHITE),
$(txt 'GPU    NVIDIA GeForce RTX 4090  ·  24 GB  ·  driver 615.71' 260 $((LY3+170)) 40 $WHITE),
$(txt 'RAM    32 GB DDR5  ·  8000 MT/s' 2060 $((LY3+108)) 40 $WHITE),
$(txt 'SSD    Crucial T705 2 TB  ·  PCIe 5.0 x4  ·  13.5 GB/s sequential read (measured)' 2060 $((LY3+170)) 40 $WHITE),
$(txt 'same save, same route, same machine  ·  CachyOS, native Linux build, NVIDIA GL under XWayland  ·  5120x2160 at max zoom  ·  MangoHud overlay shows live frame time and fps  ·  boot and load counters come from the game log' '(w-tw)/2' 'h-th-30' 30 $GREY),setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]
"

mkdir -p "$(dirname "$out")"
ffmpeg -hide_banner -y \
  -ss "$S_START" -t "$LEN" -i "$S" \
  -ss "$O_START" -t "$(f "$LEN - $HOLD")" -i "$O" \
  -i "$MUSIC" \
  -filter_complex "$filter;[2:a]atrim=0:${LEN},asetpts=PTS-STARTPTS,loudnorm=I=-16:TP=-1.5:LRA=11,afade=t=in:d=1.5,afade=t=out:st=$(f "$LEN - 4"):d=4[a]" \
  -map '[v]' -map '[a]' -c:a aac -b:a 192k \
  -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 22 -b:v 0 -maxrate 100M -bufsize 200M \
  -pix_fmt p010le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv \
  -movflags +faststart+write_colr -r 60 -t "$LEN" \
  "$out"

ffmpeg -hide_banner -v error -y -ss "$(f "$T_R + 15")" -i "$out" -frames:v 1 -vf "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p" -q:v 2 "${out%.mp4}.jpg"
echo "wrote $out (AV1 10-bit HDR PQ/BT.2020) and ${out%.mp4}.jpg (route start at ${T_R}s, length ${LEN}s)"
