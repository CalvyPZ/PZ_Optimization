#!/usr/bin/env python3
"""The load after Continue, phase by phase, from a run's pzopt-loadtrace.out.

  harness/loadtime.py <run-dir>... [--all]

pzopt.LoadTrace stamps every console line with an epoch ms (Zomboid/pzopt-loadtrace.out,
collected into the run directory). This prints, per run:

  boot        launch (first line) -> main menu ("continuing latest save")
  load        Continue -> world ready (the harness's "world ready" line, i.e. the first
              IngameState frame with the player on a square)
  and the phases in between, from the game's own "X start"/"X end" markers and the
  harness lines (see PHASES). --all lists every stamped marker line with its offset.

With several runs the phases are printed side by side, so a change is compared to
the baseline in one table.
"""
import argparse
import re
import sys
from pathlib import Path

# (label, start pattern, end pattern); patterns are regexes on the line text after the stamp.
# A phase is the interval from the first line matching start to the first line after it matching end.
PHASES = [
    ("A Continue -> loader thread started", r"continuing latest save", r"IsoMetaGrid\.Create: begin loading"),
    ("C1 metagrid step1, tile definitions", r"IsoMetaGrid\.Create: begin loading", r"LoadTileDefinitions end"),
    ("C2 tiledefs end -> GameTime/radio/dictionary", r"LoadTileDefinitions end", r"IsoMetaGrid\.Create: finished loading"),
    ("  C2a loadAnimalDefinitions (73 models; render-thread round trips)", r"loadAnimalDefinitions start", r"loadAnimalDefinitions end"),
    ("C3 map zones (Lua OnLoadMapZones, checkVehiclesZones, map_meta/zones)", r"IsoMetaGrid\.Create: finished loading", r"MapCollisionData\.init\(\) start"),
    ("C4 MapCollisionData.init", r"MapCollisionData\.init\(\) start", r"MapCollisionData\.init\(\) end"),
    ("C5 popman/pathfind/streamer create", r"MapCollisionData\.init\(\) end", r"WorldStreamer\.isBusy\(\) loop start"),
    ("C6 initial chunk load (streamer busy loop)", r"WorldStreamer\.isBusy\(\) loop start", r"WorldStreamer\.isBusy\(\) loop end"),
    ("C7 player load -> OnGameTimeLoaded", r"WorldStreamer\.isBusy\(\) loop end", r"triggerEvent OnGameTimeLoaded"),
    ("C8 global objects, tutorial, save check", r"triggerEvent OnGameTimeLoaded", r"bWaitForAssetLoadingToFinish2 start"),
    ("D  wait for animations + file tasks (assetLock2)", r"bWaitForAssetLoadingToFinish2 start", r"bWaitForAssetLoadingToFinish2 end"),
    ("E  gameLoaded, physics meshes, SendDone", r"bWaitForAssetLoadingToFinish2 end", r"game loading took"),
    ("E2 click-to-start -> IngameState", r"game loading took", r"STATE: exit zombie\.gameStates\.GameLoadingState|Game Mode:"),
    ("F  IngameState.enter -> world ready", r"STATE: exit zombie\.gameStates\.GameLoadingState|Game Mode:", r"harness: world ready"),
    ("V  Continue -> world visible (player's chunk lit)", r"continuing latest save", r"load step: world visible"),
    ("V2 Continue -> world complete (loaded chunks lit)", r"continuing latest save", r"load step: world complete"),
]
TOTALS = [
    ("boot: launch -> Continue", None, r"continuing latest save"),
    ("LOAD: Continue -> world ready", r"continuing latest save", r"harness: world ready"),
    ("  game's own 'game loading took'", r"continuing latest save", r"game loading took"),
]
MARKERS = r"(\bstart\b|\bend\b|STATE:|game loading took|harness:|continuing latest save|IsoMetaGrid\.Create|loading \d+ zones|Loading .*texture pack|Loading Lua|Loading Mods)"


def load(run):
    p = Path(run) / "pzopt-loadtrace.out"
    if not p.exists():
        sys.exit(f"{p}: missing (run with instrument=true or as a harness run; needs the LoadTrace build)")
    rows = []
    with p.open(errors="replace") as f:
        for line in f:
            t, _, text = line.rstrip("\n").partition("\t")
            if t.isdigit():
                rows.append((int(t), text))
    return rows


def first(rows, pattern, after=0):
    rx = re.compile(pattern)
    for i in range(after, len(rows)):
        if rx.search(rows[i][1]):
            return i
    return None


def phase_ms(rows, start, end):
    i = 0 if start is None else first(rows, start)
    if i is None:
        return None
    j = first(rows, end, i if start is None else i + 1)
    if j is None:
        return None
    return rows[j][0] - rows[i][0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--all", action="store_true", help="list every marker line with its offset from Continue")
    a = ap.parse_args()
    data = {Path(r).name: load(r) for r in a.runs}
    names = list(data)
    w = max(len(n) for n in names)
    print("phase".ljust(62) + "".join(n[:w].rjust(w + 2) for n in names))
    for label, s, e in TOTALS + PHASES:
        cells = []
        for n in names:
            ms = phase_ms(data[n], s, e)
            cells.append(("-" if ms is None else f"{ms / 1000:.2f}s").rjust(w + 2))
        print(label.ljust(62) + "".join(cells))
    if a.all:
        for n in names:
            rows = data[n]
            i0 = first(rows, r"continuing latest save") or 0
            t0 = rows[i0][0]
            print(f"\n== {n}: markers, offset from Continue")
            rx = re.compile(MARKERS)
            for t, text in rows:
                if rx.search(text):
                    print(f"{(t - t0) / 1000:+8.3f}  {text[:150]}")


if __name__ == "__main__":
    main()
