#!/usr/bin/env python3
"""Where and what flickers: the frames with the most transient pixels inside one 4x4 screen cell of
a recording, dumped as before / during / after crops so a person (or a peer session) can see what
blinked, plus a per-second transient count for the cell.

  harness/transient-crops.py <run-or-mp4> --cell "upper-middle centre-left" [--seconds 20 | --window START END]
                             [--scale 1280] [--top 6] [--out <dir>]

Uses parity-judge.py's window rule (the last --seconds of on-screen activity) and flicker.py's A-B-A
detector at the same threshold (32, K=3, 60 fps resample). For each of the --top frames it writes
<out>/t<sec>_<frame>_{before,during,after}.png: the cell crop at full capture resolution (the
recording decoded once more for those three frames only), and prints the list with the transient
pixel count and the sub-cell (2x2 of the cell) where they sit.
"""
import subprocess
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import importlib.util as _ilu  # noqa: E402

from flicker import read_frames, transients  # noqa: E402

_spec = _ilu.spec_from_file_location("parity_judge", Path(__file__).parent / "parity-judge.py")
_pj = _ilu.module_from_spec(_spec)
_spec.loader.exec_module(_pj)
video_of, active_window, GRID, NAMES, COLS = _pj.video_of, _pj.active_window, _pj.GRID, _pj.NAMES, _pj.COLS

FPS = 60.0
W, H = 5120, 2160


def cell_index(name):
    for cy, r in NAMES.items():
        for cx, c in COLS.items():
            if f"{r} {c}" == name:
                return cy, cx
    sys.exit(f"unknown cell {name!r}; names are '<{'|'.join(NAMES.values())}> <{'|'.join(COLS.values())}>'")


def crop(video, t, cy, cx, out):
    gh, gw = H // GRID, W // GRID
    vf = f"crop={gw}:{gh}:{cx * gw}:{cy * gh}"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{t:.4f}", "-i", str(video), "-frames:v", "1", "-vf", vf, str(out)], check=True)


def main(argv):
    spec, cell, window, scale, top, seconds, out = None, None, None, 1280, 6, 20.0, None
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--cell":
            cell = argv[i + 1]; i += 2
        elif a == "--window":
            window = (float(argv[i + 1]), float(argv[i + 2])); i += 3
        elif a == "--scale":
            scale = int(argv[i + 1]); i += 2
        elif a == "--top":
            top = int(argv[i + 1]); i += 2
        elif a == "--seconds":
            seconds = float(argv[i + 1]); i += 2
        elif a == "--out":
            out = Path(argv[i + 1]); i += 2
        else:
            spec = a; i += 1
    if not spec or not cell:
        print(__doc__)
        return 2
    cy, cx = cell_index(cell)
    video = video_of(spec)
    s, e = window if window else active_window(video, seconds)
    f = read_frames(str(video), s, e, scale, fps=FPS).astype(np.int16)
    n, h, w = f.shape
    gh, gw = h // GRID, w // GRID
    tr = transients(f, 32, 3)[:, cy * gh:(cy + 1) * gh, cx * gw:(cx + 1) * gw]
    per_frame = tr.sum(axis=(1, 2))
    per_sec = [int(per_frame[k:k + int(FPS)].sum()) for k in range(0, n, int(FPS))]
    print(f"{video}: window {s:.1f}-{e:.1f}s, cell '{cell}' ({gw}x{gh} px at scale {scale}): "
          f"{int(per_frame.sum())} transient px over {n} frames; per second: {per_sec}")
    out = out or (video.parent / "transient-crops")
    out.mkdir(parents=True, exist_ok=True)
    order = np.argsort(per_frame)[::-1]
    picked = []
    for idx in order:
        if per_frame[idx] == 0 or len(picked) >= top:
            break
        if any(abs(int(idx) - p) < 6 for p in picked):   # one burst, one entry
            continue
        picked.append(int(idx))
    for idx in picked:
        q = tr[idx][:gh // 2 * 2, :gw // 2 * 2]                 # even crop for the 2x2 split
        sub = q.reshape(2, gh // 2, 2, gw // 2).sum(axis=(1, 3))
        where = max(((r, c) for r in range(2) for c in range(2)), key=lambda rc: sub[rc])
        t = s + idx / FPS
        tag = f"t{t:06.2f}_f{idx:04d}"
        for k, name in ((-1, "before"), (0, "during"), (1, "after")):
            crop(video, t + k / FPS, cy, cx, out / f"{tag}_{name}.png")
        print(f"  frame {idx} at {t:.2f}s: {int(per_frame[idx])} px, densest quarter {'top' if where[0] == 0 else 'bottom'}-{'left' if where[1] == 0 else 'right'} "
              f"({int(sub[where])} px) -> {out / tag}_{{before,during,after}}.png")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
