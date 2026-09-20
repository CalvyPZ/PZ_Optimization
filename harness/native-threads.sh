#!/usr/bin/env bash
# Snapshot the game's native threads (all of them, not just the Java ones pzopt-threads.out sees)
# while a harness run is in progress: per-thread CPU over a window, plus the GL-related
# environment the process actually got. Run it next to harness/run.sh:
#   harness/native-threads.sh <out-file> [start-after-secs=60] [window-secs=40]
# Output: <out-file> with the process environment lines matching __GL/JAVA_TOOL/MANGOHUD/MESA,
# then one line per thread "cpu-seconds  cpu-percent  tid  comm" sorted by CPU, for the window.
set -u
out="$1"; start_after="${2:-60}"; window="${3:-40}"
pid=""
for _ in $(seq 1 120); do pid=$(pgrep -f '[P]rojectZomboid64' | head -1); [[ -n "$pid" ]] && break; sleep 1; done
[[ -z "$pid" ]] && { echo "no game process" > "$out"; exit 1; }
sleep "$start_after"
[[ -d /proc/$pid ]] || { echo "game exited before the window" > "$out"; exit 1; }
{
  echo "pid=$pid window=${window}s starting ${start_after}s after the process appeared"
  tr '\0' '\n' < /proc/$pid/environ | grep -E '^(__GL|JAVA_TOOL|MANGOHUD|MESA|LD_PRELOAD|SDL_VIDEODRIVER|WAYLAND_DISPLAY|DISPLAY)' | sort
} > "$out"
tck=$(getconf CLK_TCK)
snap() { for t in /proc/$pid/task/*; do [[ -r $t/stat ]] || continue; tid=${t##*/}; comm=$(cat $t/comm 2>/dev/null); s=$(cat $t/stat 2>/dev/null) || continue; s=${s##*) }; set -- $s; echo "$tid $((${12}+${13})) $comm"; done; }
a=$(snap); sleep "$window"; b=$(snap)
python3 - "$a" "$b" "$tck" "$window" >> "$out" <<'PY'
import sys
a,b,tck,win=sys.argv[1],sys.argv[2],int(sys.argv[3]),float(sys.argv[4])
def parse(s):
    d={}
    for l in s.splitlines():
        p=l.split(' ',2)
        if len(p)==3: d[p[0]]=(int(p[1]),p[2])
    return d
A,B=parse(a),parse(b); rows=[]
for tid,(t1,comm) in B.items():
    t0=A.get(tid,(0,comm))[0]; secs=(t1-t0)/tck
    rows.append((secs,tid,comm))
rows.sort(reverse=True)
tot=sum(r[0] for r in rows)
print(f"threads={len(rows)} process_cpu={tot/win*100:.0f}% of a core over the window")
for secs,tid,comm in rows[:25]:
    print(f"{secs:7.2f}s {secs/win*100:5.1f}%  {tid:>7}  {comm}")
PY
