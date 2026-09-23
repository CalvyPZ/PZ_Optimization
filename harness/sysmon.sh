#!/usr/bin/env bash
# System utilization sampler for a harness run (started by run.sh in the
# background, killed when the game exits). One CSV row every INTERVAL seconds:
#
#   epoch_ms, cpu_pct, cpu_busiest_core_pct, gpu_pct, gpu_sm_mhz, gpu_mem_mhz, gpu_w, gpu_c, vram_mib, game_cpu_pct, bat_w
#
# cpu_pct is whole-machine busy time from /proc/stat (100 = every hardware
# thread busy); cpu_busiest_core_pct is the busiest single logical CPU, which
# is what a single-threaded game loop pins; game_cpu_pct is the game process
# (utime+stime, 100 = one core). GPU columns come from nvidia-smi, or from the
# amdgpu sysfs (gpu_busy_percent + hwmon) on an AMD GPU/APU; empty otherwise.
# bat_w is the battery discharge rate (W, empty when on mains or no battery).
# Independent of MangoHud.
#   harness/sysmon.sh <out.csv> [interval-seconds] [pid-pattern]
set -u
out="$1"; interval="${2:-0.5}"; pat="${3:-^([^ ]*[/\\])?ProjectZomboid64(\.exe)?( |$)}"   # argv[0] only: a shell mentioning the name is not the game
ncpu=$(nproc)
echo "epoch_ms,cpu_pct,cpu_busiest_core_pct,gpu_pct,gpu_sm_mhz,gpu_mem_mhz,gpu_w,gpu_c,vram_mib,game_cpu_pct,bat_w" > "$out"
have_smi=0; command -v nvidia-smi >/dev/null && have_smi=1
# amdgpu: first card with a busy counter; hwmon gives power (uW), temp (mC), sclk (Hz)
amd_dev=""; amd_hwmon=""
if (( !have_smi )); then
  for d in /sys/class/drm/card*/device; do
    [[ -r "$d/gpu_busy_percent" ]] || continue
    amd_dev="$d"; amd_hwmon=$(ls -d "$d"/hwmon/hwmon* 2>/dev/null | head -1); break
  done
fi
bat=""; for b in /sys/class/power_supply/BAT*; do [[ -r "$b/power_now" || -r "$b/current_now" ]] && { bat="$b"; break; }; done
read_bat() { # W while discharging, else empty
  [[ -n "$bat" ]] || return 0
  [[ "$(cat "$bat/status" 2>/dev/null)" == Discharging ]] || return 0
  if [[ -r "$bat/power_now" ]]; then awk -v p="$(cat "$bat/power_now")" 'BEGIN{printf "%.2f", p/1e6}'
  else awk -v i="$(cat "$bat/current_now")" -v v="$(cat "$bat/voltage_now")" 'BEGIN{printf "%.2f", i*v/1e12}'; fi
}
read_amd() { # gpu_pct,gpu_sm_mhz,gpu_mem_mhz,gpu_w,gpu_c,vram_mib
  local pct mhz w c vram
  pct=$(cat "$amd_dev/gpu_busy_percent" 2>/dev/null)
  mhz=""; [[ -r "$amd_hwmon/freq1_input" ]] && mhz=$(awk -v f="$(cat "$amd_hwmon/freq1_input")" 'BEGIN{printf "%d", f/1e6}')
  w=""; if [[ -r "$amd_hwmon/power1_average" ]]; then w=$(cat "$amd_hwmon/power1_average"); elif [[ -r "$amd_hwmon/power1_input" ]]; then w=$(cat "$amd_hwmon/power1_input"); fi
  [[ -n "$w" ]] && w=$(awk -v p="$w" 'BEGIN{printf "%.2f", p/1e6}')
  c=""; [[ -r "$amd_hwmon/temp1_input" ]] && c=$(awk -v t="$(cat "$amd_hwmon/temp1_input")" 'BEGIN{printf "%d", t/1000}')
  vram=""; [[ -r "$amd_dev/mem_info_vram_used" ]] && vram=$(awk -v m="$(cat "$amd_dev/mem_info_vram_used")" 'BEGIN{printf "%d", m/1048576}')
  echo "$pct,$mhz,,$w,$c,$vram"
}
read_cpus() { # prints "idx busy total" per cpu line plus the aggregate as idx=all
  awk '/^cpu/ { idle=$5+$6; tot=0; for(i=2;i<=NF;i++) tot+=$i; print $1, tot-idle, tot }' /proc/stat
}
prev=$(read_cpus)
prev_game=""; game_pid=""
clk=$(getconf CLK_TCK)
while :; do
  sleep "$interval"
  now=$(date +%s%3N)
  cur=$(read_cpus)
  # per-cpu deltas -> aggregate and busiest core
  read -r cpu_pct busiest < <(paste <(echo "$prev") <(echo "$cur") | awk '
    { db=$5-$2; dt=$6-$3; p=(dt>0)?100*db/dt:0; if($1=="cpu"){agg=p} else if(p>mx){mx=p} }
    END { printf "%.1f %.1f\n", agg, mx }')
  prev=$cur
  gpu=",,,,,"
  if (( have_smi )); then
    gpu=$(nvidia-smi --query-gpu=utilization.gpu,clocks.sm,clocks.mem,power.draw,temperature.gpu,memory.used --format=csv,noheader,nounits 2>/dev/null | head -1 | tr -d ' ')
    [[ -n "$gpu" ]] || gpu=",,,,,"
  elif [[ -n "$amd_dev" ]]; then
    gpu=$(read_amd)
  fi
  [[ -n "$game_pid" && -d /proc/$game_pid ]] || { game_pid=$(pgrep -f "$pat" | head -1 || true); prev_game=""; }
  game_pct=""
  if [[ -n "$game_pid" && -r /proc/$game_pid/stat ]]; then
    t=$(awk '{print $14+$15}' /proc/$game_pid/stat 2>/dev/null || echo "")
    if [[ -n "$t" && -n "$prev_game" ]]; then
      game_pct=$(awk -v a="$prev_game" -v b="$t" -v c="$clk" -v dt="$interval" 'BEGIN{printf "%.1f", 100*(b-a)/c/dt}')
    fi
    prev_game=$t
  fi
  echo "$now,$cpu_pct,$busiest,$gpu,$game_pct,$(read_bat)" >> "$out"
done
