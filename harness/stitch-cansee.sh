#!/usr/bin/env bash
# CanSee repro video (2026-09-22): the stock "Lure" action on a cow with the released overrides (Vineflower had
# dropped the (IsoObject) cast in IsoGameCharacter.CanSee(IsoMovingObject): the method called itself and threw a
# StackOverflowError on every lure tick, so the cow never reacts) beside the same scene with the one-line fix.
# Runs: harness/run.sh --mode bench --flag route=S:1 --flag speed=1 --flag hold=30 --flag zoom=0.5 --flag lure=cow
#       --flag lure_at=8 --route-seconds 1 --record --no-dashboard, one with each build installed.
# Both panels are cut from the lure instant (world ready + LURE_AFTER s, the recording's file birth as its epoch);
# a crop of the 5120x2160 capture around the player and the cow, AV1 10-bit PQ / BT.2020 like every docs/media video.
#
# Usage: harness/stitch-cansee.sh <broken-label> <fixed-label> <out.mp4>
# Env:   LURE_AFTER (9.5)  PRE (1.5)  LEN (24)  START_BAD / START_FIX (clip start in s, overrides the estimate: the
#        file birth is whole seconds; the cow's first frame in the crop is exact, 21.3 / 20.5 s for cansee-broken /
#        cansee-fixed2)
set -euo pipefail
cd "$(dirname "$0")/.."
RUN_BAD="${1:?broken run label}"; RUN_FIX="${2:?fixed run label}"; out="${3:?out.mp4}"
LURE_AFTER="${LURE_AFTER:-9.5}"; PRE="${PRE:-1.5}"; LEN="${LEN:-24}"
FONT=/usr/share/fonts/noto/NotoSans-Bold.ttf
[ -f "$FONT" ] || FONT=$(fc-match -f '%{file}' 'DejaVu Sans:bold')

run() { ls -d harness/runs/$1-* | tail -1; }
start() { # video second of the lure instant minus PRE
  local d="$1" wr birth
  wr=$(sed -n 's/^world_ready_epoch_ms=//p' "$d/pzopt-schedule.out")
  birth=$(stat -c %W "$d/recording.mp4")
  python3 -c "print(round($wr/1000 - $birth + $LURE_AFTER - $PRE, 2))"
}
B=$(run "$RUN_BAD"); F=$(run "$RUN_FIX")
SB=${START_BAD:-$(start "$B")}; SF=${START_FIX:-$(start "$F")}
NB=$(grep -ac 'StackOverflowError at IsoGameCharacter.CanSee' "$B/console.txt" || true)
NF=$(grep -ac 'StackOverflowError' "$F/console.txt" || true)
echo "broken $B from ${SB}s ($NB StackOverflowError lines) | fixed $F from ${SF}s ($NF)"

# crop: player at ~(2560,1100), the cow starts 8 tiles east at ~(3520,1560); below the overlay panels
CX=2080; CY=960; CWS=2240; CHS=1100
W=3840; PW=1920; PH=942; TITLE_H=110; LABEL_H=62; TXT_H=250
Y1=$TITLE_H; YT=$((Y1 + PH)); H=$((YT + TXT_H))

# PQ-space colours: full white is the display's peak, so text uses ~60 % code values.
TXT=0xb4b4b8; DIM=0x8a8a90; RED=0xb85c5c; GRN=0x5cb878
cell() { echo "[$1:v]fps=60,crop=${CWS}:${CHS}:${CX}:${CY},format=yuv420p10le,scale=${PW}:${PH}:flags=lanczos,setsar=1"; }
dt() { echo "drawtext=fontfile=$FONT:text='$1':fontsize=$2:fontcolor=$3:x=$4:y=$5"; }

filter="
color=c=0x060608:s=${W}x${H}:r=60:d=${LEN},format=yuv420p10le[bg];
$(cell 0)[l];
$(cell 1)[r];
[bg][l]overlay=0:${Y1}:shortest=1[b1];
[b1][r]overlay=${PW}:${Y1},drawbox=x=0:y=${Y1}:w=${W}:h=${LABEL_H}:color=0x060608@0.85:t=fill,drawbox=x=${PW}-2:y=${Y1}:w=4:h=${PH}:color=0x202026:t=fill[b2];
[b2]$(dt 'Project Zomboid B42.20  \\|  the stock Lure action on a cow  \\|  IsoGameCharacter.CanSee(IsoMovingObject)' 54 $TXT '(w-tw)/2' 30),
$(dt 'BEFORE  -  released overrides (CanSee calls itself)' 40 $RED "(${PW}-tw)/2" "${Y1}+10"),
$(dt 'AFTER  -  the (IsoObject) cast restored' 40 $GRN "${PW}+(${PW}-tw)/2" "${Y1}+10"),
$(dt "java.lang.StackOverflowError at IsoGameCharacter.CanSee  -  ${NB} times in the console" 34 $TXT "(${PW}-tw)/2" "${YT}+34"),
$(dt 'the player holds out the carrot, the cow never reacts  (lured=0, stays 8.6 tiles away)' 30 $DIM "(${PW}-tw)/2" "${YT}+86"),
$(dt 'no exception  -  the stock behaviour' 34 $TXT "${PW}+(${PW}-tw)/2" "${YT}+34"),
$(dt 'the cow takes the lure and walks up to the player  (lured=1, 8.6 to 2.5 tiles)' 30 $DIM "${PW}+(${PW}-tw)/2" "${YT}+86"),
$(dt 'Vineflower dropped the cast in  return this.CanSee((IsoObject)obj)  -  every IsoAnimal.tryLure hit it. Same save, scene and hardware; left audio = before, right = after.' 28 $DIM '(w-tw)/2' "${YT}+160"),
setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v];
[0:a]atrim=0:${LEN},pan=mono|c0=0.5*c0+0.5*c1[al];
[1:a]atrim=0:${LEN},pan=mono|c0=0.5*c0+0.5*c1[ar];
[al][ar]join=inputs=2:channel_layout=stereo,loudnorm=I=-16:TP=-1.5:LRA=11,afade=t=out:st=$(python3 -c "print(max(0, $LEN-2))"):d=2[a]
"

mkdir -p "$(dirname "$out")"
ffmpeg -hide_banner -v error -y \
  -ss "$SB" -t "$LEN" -i "$B/recording.mp4" \
  -ss "$SF" -t "$LEN" -i "$F/recording.mp4" \
  -filter_complex "$filter" -map '[v]' -map '[a]' -c:a aac -b:a 192k \
  -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 22 -b:v 0 -maxrate 80M -bufsize 160M \
  -pix_fmt p010le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv \
  -movflags +faststart+write_colr -r 60 \
  "$out"

ffmpeg -hide_banner -v error -y -ss 16 -i "$out" -frames:v 1 -vf "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p" -q:v 2 "${out%%.mp4}.jpg" || true
ls -la "$out" | awk '{print $5, $9}'
ffprobe -v error -show_entries format=duration:stream=width,height,codec_name,color_transfer -of csv=p=0 "$out"
