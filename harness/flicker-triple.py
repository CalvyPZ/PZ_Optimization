#!/usr/bin/env python3
"""Dump one frame triple (n, n+1, n+2) of a recording as crops around the densest toggle cluster:
fl-crop-1/2/3.png (the three frames) and fl-crop-mark.png (frame 2 with A->B->A pixels in red).
Usage: flicker-triple.py recording.mp4 FRAME [--thresh T] [--out DIR] [--size WxH]"""
import argparse, subprocess, os
import numpy as np
from PIL import Image

ap = argparse.ArgumentParser()
ap.add_argument("video"); ap.add_argument("frame", type=int)
ap.add_argument("--thresh", type=int, default=24); ap.add_argument("--out", default="/tmp")
ap.add_argument("--size", default="1024x768")
a = ap.parse_args()
cw, ch = map(int, a.size.split("x"))
subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", a.video, "-vf", f"select='between(n,{a.frame},{a.frame + 2})'",
                "-fps_mode", "passthrough", os.path.join(a.out, "fl-%d.png")], check=True)
imgs = [Image.open(os.path.join(a.out, f"fl-{i}.png")) for i in (1, 2, 3)]
f1, f2, f3 = [np.asarray(im.convert("L")).astype(np.int16) for im in imgs]
t = a.thresh
tog = (np.abs(f2 - f1) >= t) & (np.abs(f3 - f2) >= t) & (np.abs(f3 - f1) < t // 2)
print("frame", a.frame, "toggles:", int(tog.sum()), "changed 1->2:", int((np.abs(f2 - f1) >= t).sum()))
ys, xs = np.nonzero(tog)
if len(xs) == 0:
    raise SystemExit("no toggles")
H = np.zeros((f1.shape[0] // 128 + 1, f1.shape[1] // 128 + 1), int)
np.add.at(H, (ys // 128, xs // 128), 1)
cy, cx = np.unravel_index(H.argmax(), H.shape)
x0 = max(0, min(f1.shape[1] - cw, cx * 128 + 64 - cw // 2)); y0 = max(0, min(f1.shape[0] - ch, cy * 128 + 64 - ch // 2))
mark = np.asarray(imgs[1].convert("RGB")).copy(); mark[tog] = [255, 0, 0]
Image.fromarray(mark[y0:y0 + ch, x0:x0 + cw]).save(os.path.join(a.out, "fl-crop-mark.png"))
for i, im in enumerate(imgs, 1):
    im.crop((x0, y0, x0 + cw, y0 + ch)).save(os.path.join(a.out, f"fl-crop-{i}.png"))
print("crop origin", x0, y0, "densest 128px cell", cx * 128, cy * 128, "with", int(H.max()), "toggles")
