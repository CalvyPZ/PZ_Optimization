#!/usr/bin/env python3
"""Count chunk-texture black-out in a --shot-at capture against a control capture taken at the same
camera position: pixels that are black in the run but drawn in the control, and 32 px tiles that are
entirely black in the run while lit in the control (2026-09-20, persistent VBO artifact bisect).
usage: blacktiles.py <control.png> <run.png>..."""
import sys
import numpy as np
from PIL import Image

def load(p):
    return np.asarray(Image.open(p).convert("L"), dtype=np.uint8)[40:, :]  # drop the top HUD strip

ctrl_lit = load(sys.argv[1]) >= 8
for p in sys.argv[2:]:
    a = load(p)
    nb = (a < 8) & ctrl_lit
    h, w = a.shape
    t = 32
    blk = nb[:h // t * t, :w // t * t].reshape(h // t, t, w // t, t).mean(axis=(1, 3))
    print(f"{p}: newly-black px {nb.sum()}  black 32px tiles {(blk > 0.98).sum()}")
