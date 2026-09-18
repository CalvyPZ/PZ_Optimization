#!/usr/bin/env bash
# Steam launch wrapper for Project Zomboid. Set the game's Steam launch options to:
#
#   /home/diegov/Documents/ZedProjects/PZ_Optimization/harness/steam-launch.sh %command%
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
env_file="${PZOPT_LAUNCH_ENV:-$HOME/Zomboid/pzopt-launch.env}"
log="$HOME/Zomboid/pzopt-launch.log"
if [[ -f "$env_file" ]]; then
  set -a
  # shellcheck disable=SC1090
  . "$env_file"
  set +a
  if [[ "${PZOPT_MANGOHUD:-0}" == 1 ]]; then
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
