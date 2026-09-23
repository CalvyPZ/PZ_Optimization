#!/usr/bin/env python3
"""Per-thread scheduler monitor for the game process (no root needed).

Waits for the ProjectZomboid64 JVM, then every PERIOD seconds writes one line per sampled thread:
  epoch_ms  tid  comm  run_ns  wait_ns  minflt  majflt  state
from /proc/<pid>/task/<tid>/{schedstat,stat}, cumulative counters (analysed by harness/schedmon-report.py).
run_ns = time on the CPU, wait_ns = time runnable but waiting for a CPU (run-queue delay), majflt = major
page faults (swap / file page-ins). Plus one line per sample with the machine's PSI and vmstat:
  epoch_ms  -  PSI  cpu_some_us  mem_some_us  mem_full_us  io_some_us  pswpin  pswpout  pgmajfault  ctxt
Every 10 s also one memory line from /proc/<pid>/smaps, resident kB by mapping kind:
  epoch_ms  -  MEM  anon=  nvidia=  shmem=  so=  file=  total=  swap=
(anon = Java heap + native malloc, nvidia = /dev/nvidia* mappings, shmem = memfd / SysV / dri, so = libraries).
Usage: schedmon.py OUT [PERIOD=0.05]
"""
import os, sys, time

out = open(sys.argv[1], "w", buffering=1 << 16)
period = float(sys.argv[2]) if len(sys.argv) > 2 else 0.05


def find_pid():
    for p in os.listdir("/proc"):
        if not p.isdigit():
            continue
        try:
            cmd = open(f"/proc/{p}/cmdline", "rb").read().split(b"\0")
        except OSError:
            continue
        if cmd and os.path.basename(cmd[0]) == b"ProjectZomboid64":
            return p
    return None


def psi(name):
    some = full = 0
    try:
        for line in open(f"/proc/pressure/{name}"):
            f = line.split()
            v = int(f[4].split("=")[1])
            if f[0] == "some":
                some = v
            else:
                full = v
    except OSError:
        pass
    return some, full


VM = ("pswpin", "pswpout", "pgmajfault")

pid = None
t_end = time.time() + 900
while pid is None and time.time() < t_end:
    pid = find_pid()
    if pid is None:
        time.sleep(0.5)
if pid is None:
    sys.exit(0)
out.write(f"# pid={pid} period={period} clk_tck={os.sysconf('SC_CLK_TCK')}\n")
task_dir = f"/proc/{pid}/task"
names = {}
next_mem = 0.0


def mem_line(now):
    kinds = {"anon": 0, "nvidia": 0, "shmem": 0, "so": 0, "file": 0}
    swap = 0
    kind = "anon"
    try:
        with open(f"/proc/{pid}/smaps") as f:
            for line in f:
                c = line[0]
                if c.isdigit() or c in "abcdef":  # a mapping header: range perms offset dev inode [path]
                    parts = line.split(None, 5)
                    path = parts[5].strip() if len(parts) > 5 else ""
                    if not path or path.startswith("[heap]") or path.startswith("[stack") or path.startswith("[anon"):
                        kind = "anon"
                    elif "nvidia" in path:
                        kind = "nvidia"
                    elif path.startswith("/memfd:") or path.startswith("/SYSV") or path.startswith("/dev/dri") or "(deleted)" in path:
                        kind = "shmem"
                    elif ".so" in path:
                        kind = "so"
                    else:
                        kind = "file"
                elif line.startswith("Rss:"):
                    kinds[kind] += int(line.split()[1])
                elif line.startswith("Swap:"):
                    swap += int(line.split()[1])
    except OSError:
        return ""
    return f"{now} - MEM " + " ".join(f"{k}={v}" for k, v in kinds.items()) + f" total={sum(kinds.values())} swap={swap}\n"
next_t = time.time()
while os.path.exists(task_dir):
    now = int(time.time() * 1000)
    try:
        tids = os.listdir(task_dir)
    except OSError:
        break
    lines = []
    for tid in tids:
        try:
            ss = open(f"{task_dir}/{tid}/schedstat").read().split()
            st = open(f"{task_dir}/{tid}/stat").read()
        except OSError:
            continue
        r = st.rfind(")")
        comm = st[st.find("(") + 1:r].replace(" ", "_")
        f = st[r + 2:].split()
        # f[0]=state, f[7]=minflt, f[9]=majflt
        lines.append(f"{now} {tid} {comm} {ss[0]} {ss[1]} {f[7]} {f[9]} {f[0]}\n")
    cs, _ = psi("cpu")
    ms, mf = psi("memory")
    ios, _ = psi("io")
    vm = {}
    try:
        for line in open("/proc/vmstat"):
            k, v = line.split()
            if k in VM:
                vm[k] = v
    except OSError:
        pass
    ctxt = "0"
    try:
        for line in open("/proc/stat"):
            if line.startswith("ctxt"):
                ctxt = line.split()[1]
                break
    except OSError:
        pass
    if time.time() >= next_mem:
        next_mem = time.time() + 10.0
        ml = mem_line(now)
        if ml:
            lines.append(ml)
    lines.append(f"{now} - PSI {cs} {ms} {mf} {ios} {vm.get('pswpin',0)} {vm.get('pswpout',0)} {vm.get('pgmajfault',0)} {ctxt}\n")
    out.write("".join(lines))
    next_t += period
    d = next_t - time.time()
    if d > 0:
        time.sleep(d)
    else:
        next_t = time.time()
out.close()
