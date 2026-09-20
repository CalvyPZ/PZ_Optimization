#!/usr/bin/env bash
# Steam launch wrapper for Project Zomboid. Set the game's Steam launch options to:
#
#   <repo>/harness/steam-launch.sh %command%
#
# Environment variables cannot be handed to a game started through the running
# Steam client, so harness/run.sh writes the ones a run needs (the renderer
# selection, PZOPT_MANGOHUD=1, ...) to ~/Zomboid/pzopt-launch.env just before
# launching and deletes the file afterwards. Without that file this wrapper
# changes nothing: the game starts exactly as Steam would start it.
#
# This runs inside the Steam Linux Runtime container. MangoHud for an OpenGL
# game needs its shim in LD_PRELOAD (what the `mangohud` launcher does); the
# host's /usr is visible in the container under /run/host.
# Under Proton "$@" is the proton launcher and the preload (with $LIB, so the
# 32- and 64-bit wine processes each get their own copy) reaches the wine
# process that calls host glXSwapBuffers; whether it hooks there is untested
# (run a --mode verify smoke first: pzopt-launch.log and console.txt's
# "harness: MangoHud" line tell). run.sh writes the env file to ~/Zomboid in
# both layouts.
env_file="${PZOPT_LAUNCH_ENV:-$HOME/Zomboid/pzopt-launch.env}"
log="$HOME/Zomboid/pzopt-launch.log"
if [[ -f "$env_file" ]]; then
  set -a
  # shellcheck disable=SC1090
  . "$env_file"
  set +a
  if [[ "${PZOPT_MANGOHUD:-0}" == 1 && "${PZOPT_MANGOHUD_LIB:-opengl}" == none ]]; then
    # Zink: the game presents through Vulkan, so MangoHud's implicit Vulkan layer does the hooking;
    # preloading the OpenGL library as well killed the game at start-up (native-zink-1, 2026-09-18).
    export MANGOHUD=1
    echo "$(date +%T) mangohud: Vulkan layer only (PZOPT_MANGOHUD_LIB=none)" >> "$log"
  elif [[ "${PZOPT_MANGOHUD:-0}" == 1 ]]; then
    shim=""
    # the dlsym shim does not catch LWJGL's dlopen'd libGL; the OpenGL library hooks glXSwapBuffers directly
    lib="libMangoHud_${PZOPT_MANGOHUD_LIB:-opengl}.so"
    for c in "/usr/\$LIB/mangohud/$lib" "/usr/lib/mangohud/$lib"; do
      probe="${c//\$LIB/lib}"
      if [[ -f "$probe" ]]; then shim="$c"; break; fi
    done
    if [[ -n "$shim" ]]; then
      export MANGOHUD=1
      export LD_PRELOAD="${LD_PRELOAD:+$LD_PRELOAD:}$shim"
    fi
    { echo "$(date +%T) mangohud shim: ${shim:-NOT FOUND}; LD_PRELOAD=$LD_PRELOAD"; ls /usr/lib/mangohud /run/host/usr/lib/mangohud 2>&1 | head -8; } >> "$log"
  fi
  echo "$(date +%T) env: $(tr '\n' ' ' < "$env_file") exec: $*" >> "$log"
fi
exec "$@"
