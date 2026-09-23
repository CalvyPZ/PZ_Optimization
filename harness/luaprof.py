#!/usr/bin/env python3
"""Which Lua functions the game thread runs, over a run's route window (pzopt-lua.out, written by pzopt.LuaProfile when
luaProfile is on; instrumented runs have it by default).

Shares are of all game-thread samples in the window (pzopt-gamethread.out), so they compare with analyze.py's
game-thread tree. --by fn|file|chain groups by the innermost function, its file, or the whole three-frame chain.
Usage: luaprof.py RUN [--by fn|file|chain] [--top 25]
"""
import argparse, collections
from pathlib import Path

ap = argparse.ArgumentParser()
ap.add_argument("run")
ap.add_argument("--by", default="chain", choices=("fn", "file", "chain"))
ap.add_argument("--top", type=int, default=25)
a = ap.parse_args()
run = Path(a.run)
kv = dict(l.split("=", 1) for l in (run / "pzopt-bench.out").read_text().splitlines() if "=" in l)
A, B = int(kv["route_start_epoch_ms"]), int(kv["route_end_epoch_ms"])
game = 0
for l in (run / "pzopt-gamethread.out").read_text().splitlines():
    if l.startswith("#"):
        continue
    f = l.split("\t")
    if A <= int(f[0]) and int(f[0]) + 1000 <= B:
        game += int(f[1])
c = collections.Counter()
lua = 0
for l in (run / "pzopt-lua.out").read_text().splitlines():
    if l.startswith("#"):
        continue
    f = l.split("\t")
    if not (A <= int(f[0]) and int(f[0]) + 1000 <= B):
        continue
    lua += int(f[1])
    for kvp in f[2:]:
        k, n = kvp.rsplit("=", 1)
        if a.by == "fn":
            k = k.split(" < ")[0]
        elif a.by == "file":
            k = k.split(" < ")[0].split("@", 1)[-1].split(":")[0]
        c[k] += int(n)
print(f"{run.name}: {game} game-thread samples in the route, {lua} inside Kahlua ({100 * lua / max(1, game):.1f} %)")
for k, n in c.most_common(a.top):
    print(f"  {100 * n / max(1, game):5.2f}%  {k[:200]}")
