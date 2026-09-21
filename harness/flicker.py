#!/usr/bin/env python3
"""Transient-dropout detector for a harness recording (the "flicker" metric).

Reads a stretch of recording.mp4 in grayscale and counts, per frame, the pixels that change
by >= --thresh and return to their previous value within --maxk frames (A -> B -> ... -> A),
which is what a per-frame appear/disappear of a sprite looks like at 60 fps; steady motion
and fades (A -> B -> C) are not counted. Prints a per-second table and the busiest regions,
and can write a heat map of where the transients happened over a frame of the stretch.

Usage: flicker.py recording.mp4 START_S END_S [--scale W] [--maxk K] [--thresh T]
                  [--grid N] [--top N] [--heat out.png]
"""
import argparse
import subprocess
import sys

import numpy as np


def read_frames(path, start, end, width, fps=None):
    """Grayscale frames of [start, end) at `width`; fps resamples a variable-rate capture to a
    fixed rate so two recordings compare frame for frame."""
    height = width * 2160 // 5120
    vf = (f"fps={fps}," if fps else "") + f"scale={width}:{height},format=gray"
    cmd = [
        "ffmpeg", "-v", "error", "-ss", str(start), "-to", str(end), "-i", path,
        "-vf", vf, "-f", "rawvideo", "-",
    ]
    raw = subprocess.run(cmd, check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.uint8).reshape(-1, height, width)


def transients(f, t, K):
    """Boolean (n, h, w): pixel changed by >= t at frame i and returned within K frames."""
    n = f.shape[0]
    # transient at frame i (1 <= i <= n-1-K): |f[i]-f[i-1]| >= t and, for some k in 1..K,
    # |f[i+k]-f[i-1]| < t/2 while every frame between i and i+k-1 stays away from f[i-1]
    trans = np.zeros(f.shape, dtype=bool)
    for i in range(1, n - K):
        base = f[i - 1]
        changed = np.abs(f[i] - base) >= t
        if not changed.any():
            continue
        away = changed.copy()
        hit = np.zeros_like(changed)
        for k in range(1, K + 1):
            back = np.abs(f[i + k] - base) < t // 2
            hit |= away & back
            away &= ~back & (np.abs(f[i + k] - base) >= t)
        trans[i] = hit
    return trans


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("start", type=float)
    ap.add_argument("end", type=float)
    ap.add_argument("--scale", type=int, default=2560)
    ap.add_argument("--maxk", type=int, default=3, help="longest dropout in frames that still counts")
    ap.add_argument("--thresh", type=int, default=32, help="min |delta| to count as a change")
    ap.add_argument("--grid", type=int, default=32, help="grid cell size in reduced pixels")
    ap.add_argument("--fps", type=float, default=60.0)
    ap.add_argument("--top", type=int, default=8)
    ap.add_argument("--heat", help="write a heat-map PNG (frame of the stretch with transients in red)")
    args = ap.parse_args()

    f = read_frames(args.video, args.start, args.end, args.scale).astype(np.int16)
    n, h, w = f.shape
    print(f"{n} frames {w}x{h} from {args.start}s to {args.end}s, thresh {args.thresh}, maxk {args.maxk}")
    if n < args.maxk + 2:
        sys.exit("too few frames")
    t = args.thresh
    K = args.maxk
    trans = transients(f, t, K)
    per_frame = trans.sum(axis=(1, 2))
    change = (np.abs(f[1:] - f[:-1]) >= t).sum(axis=(1, 2))
    step = int(args.fps)
    print("sec   transient px/frame (mean, max)   changing px/frame (mean)")
    for s in range(0, n - 1, step):
        tg = per_frame[s:s + step]
        ch = change[s:s + step]
        print(f"{args.start + s / args.fps:5.1f}  {tg.mean():9.1f} {tg.max():7d}          {ch.mean():9.1f}")
    print(f"total transient px over the stretch: {int(per_frame.sum())} ({per_frame.sum() / max(1, n):.1f}/frame)")

    g = args.grid
    gh, gw = h // g, w // g
    cells = trans[:, :gh * g, :gw * g].reshape(n, gh, g, gw, g).sum(axis=(2, 4))
    total = cells.sum(axis=0)
    frames_hit = (cells > 0).sum(axis=0)
    order = np.argsort(total.ravel())[::-1][: args.top]
    sx = 5120 / w
    print(f"top cells ({g}x{g} reduced px, full-res box):")
    for idx in order:
        cy, cx = divmod(int(idx), gw)
        if total[cy, cx] == 0:
            break
        print(f"  x={int(cx * g * sx)}-{int((cx + 1) * g * sx)} y={int(cy * g * sx)}-{int((cy + 1) * g * sx)}: "
              f"{int(total[cy, cx])} px in {int(frames_hit[cy, cx])}/{n} frames")
    worst = np.argsort(per_frame)[::-1][:5]
    print("worst frames (abs frame no, time):",
          [(int(args.start * args.fps) + int(i), round(args.start + i / args.fps, 2)) for i in worst])

    if args.heat:
        from PIL import Image
        heat = trans.sum(axis=0)
        mid = np.repeat(f[n // 2].astype(np.uint8)[:, :, None], 3, axis=2).copy()
        m = heat > 0
        mid[m, 0] = 255
        mid[m, 1] = (np.clip(255 - heat[m] * 40, 0, 255)).astype(np.uint8)
        mid[m, 2] = 0
        Image.fromarray(mid).save(args.heat)
        print("heat map written to", args.heat)


if __name__ == "__main__":
    main()
