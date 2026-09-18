#!/usr/bin/env bash
# System utilization sampler for a harness run (started by run.sh in the
# background, killed when the game exits). One CSV row every INTERVAL seconds:
#
#   epoch_ms, cpu_pct, cpu_busiest_core_pct, gpu_pct, gpu_sm_mhz, gpu_mem_mhz, gpu_w, gpu_c, vram_mib, game_cpu_pct
#
# cpu_pct is whole-machine busy time from /proc/stat (100 = every hardware
# thread busy); cpu_busiest_core_pct is the busiest single logical CPU, which
# is what a single-threaded game loop pins; game_cpu_pct is the game process
# (utime+stime, 100 = one core). GPU columns come from nvidia-smi; they are
# empty when it is not available. Independent of MangoHud.
#   harness/sysmon.sh <out.csv> [interval-seconds] [pid-pattern]
set -u
out="$1"; interval="${2:-0.5}"; pat="${3:-[P]rojectZomboid64}"
ncpu=$(nproc)
echo "epoch_ms,cpu_pct,cpu_busiest_core_pct,gpu_pct,gpu_sm_mhz,gpu_mem_mhz,gpu_w,gpu_c,vram_mib,game_cpu_pct" > "$out"
have_smi=0; command -v nvidia-smi >/dev/null && have_smi=1
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
  echo "$now,$cpu_pct,$busiest,$gpu,$game_pct" >> "$out"
done
