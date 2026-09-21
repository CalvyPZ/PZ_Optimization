#!/bin/bash
# 120 km/h multiplayer drive on this PC against harness/mp/server.sh (stock server, localhost).
#   harness/mp/run.sh <label> [stock]
# Drops harness/mp/pzopt_devjoin.lua into the game dir's media/lua/client/pzopt/ for the run (it presses
# Connect / spawn / character Next on OnFETick; exempt from the Lua checksum like the other pzopt files) and
# removes it after. The client is started with -Dzomboid.steam=0 and -Dargs.server.connect (what +connect
# sets); pzopt.Harness does the rest in its multiplayer path (teleport to start=, /addvehicle as admin, seat,
# turn the physics body once the client owns it). Route E:800: the server's own world has a wreck at ~8820.
# Judge with harness/mp/window.py <run>:27 ... (same window for every run; a run may hit a zombie and stop).
set -u
cd "$(dirname "$0")/../.."
label="$1"; kind="${2:-opt}"
PZ_DIR=/games/steamapps/common/ProjectZomboid/projectzomboid
props=(--prop instrument=true --prop overlay=true --prop uncappedFps=true)
if [[ "$kind" == stock ]]; then
  for k in parallel=false wake=false persistentVbo=false treesInChunkTexture=false windowsInChunkTexture=false translucentTilesInChunkTexture=false hotsaveIntervalSec=0 bakeBudget=0 lightingBudget=0 cutawayFast=false lightingRebakeMs=0 rebakeBudget=0 cutawayRadius=0 gridStackInterval=0 lightSwitchCheckFrames=0 weatherMaskIdleSkip=false treeBakeDirect=false cutawayInvalidateChanged=false cutawayVisitPrefilter=false lightInfoOncePerFrame=false lightInfoChunkGate=false occlusionSkipLightingOnly=false soundZoneCache=false chunkHandoffDivisor=0 hotsaveStaged=false weatherFxScalePct=100 vboBatchKb=4 vboFastQuads=false puddleCache=false rainTiles=false parallelDepthMaps=false loaderCpuFixes=false scriptParserFast=false fmodAsync=false noLoadFade=false noIntroWait=false bootPump=false earlyModels=false luaPrecompile=false preloadAnimSets=false animClipCache=false packIndex=false itemParamSwitch=false shaderCache=false mipmapArrays=false treeBakePass=false fogPass=false treeAppend=false puddleVbo=false puddleEarlyZ=false rainSplashesFast=false; do
    props+=(--prop "$k")
  done
fi
harness/mp/server.sh up | tail -2 || exit 1
cp harness/mp/pzopt_devjoin.lua "$PZ_DIR/media/lua/client/pzopt/pzopt_devjoin.lua"
trap 'rm -f "$PZ_DIR/media/lua/client/pzopt/pzopt_devjoin.lua"' EXIT
harness/run.sh --label "$label" --mode drive --flag route=E:800 --flag kmh=193 --flag zoom=max --flag start=8002,11204 --flag heading=E --route-seconds 60 --no-dashboard --no-mangohud \
  --option textureCompression=false "${props[@]}" --vmarg -Dzomboid.steam=0 --vmarg -Dargs.server.connect=127.0.0.1:16261 >/dev/null 2>&1
d=$(ls -td harness/runs/$label-* | head -1); echo "run dir: $d"
grep -n "harness: multiplayer\|rejected\|fixture valid\|route start\|route complete\|route timeout\|quit requested" "$d/console.txt" | grep -v "waiting for the vehicle" | cut -c1-200
