#!/usr/bin/env python3
"""JVM start -> first log line of a run (the part of boot before pzopt-loadtrace.out begins).

  harness/jvmstart.py <run-dir>...

gc.log's first line carries an absolute timestamp and the JVM uptime; the trace's first line is the first console
line the overrides saw. The difference is class loading, static init and display creation before any log.
"""
import re, sys
from datetime import datetime
from pathlib import Path
for run in sys.argv[1:]:
    r = Path(run)
    gc = (r / "gc.log").read_text(errors="replace").splitlines()[0]
    m = re.match(r"\[(\S+)\]\[(\d+\.\d+)s\]", gc)
    ts = datetime.fromisoformat(m.group(1).replace("+0200", "+02:00").replace("+0100", "+01:00"))
    jvm_start_ms = ts.timestamp() * 1000 - float(m.group(2)) * 1000
    first = None
    for line in (r / "pzopt-loadtrace.out").open(errors="replace"):
        t, _, _ = line.partition("\t")
        if t.isdigit():
            first = int(t); break
    print(f"{r.name}: JVM start -> first log line {(first - jvm_start_ms) / 1000:.2f} s")
