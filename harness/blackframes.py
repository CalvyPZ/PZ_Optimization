#!/usr/bin/env python3
"""Black chunk squares over time in a --record capture (2026-09-22, Louisville never-baked levels).

Decodes the recording at a few frames a second, scaled to a quarter, and counts the 8 px blocks (32 px at the
recorded 5120x2160) that are entirely black inside the scene area (the top HUD strip and the overlay corner are
left out). A never-baked chunk level is a black rectangle of many such blocks; the void beyond the loaded grid is
black too, so compare runs of the same route rather than reading the absolute number: the stock recording gives
the floor, the excess in an optimized recording is the starved bakes.

usage: blackframes.py <recording.mp4>... [--fps 4] [--from S] [--to S] [--csv out.csv]
prints per recording: frames, black blocks mean / p50 / p90 / max, frames above the floor (> 1.5x the recording's own
p10), and the seconds with the most black.
"""
import argparse
import subprocess
import sys

import numpy as np

W, H = 1280, 540          # decode size (a quarter of 5120x2160)
T = 8                     # block: 32 px at full size
TOP, BOTTOM = 40, 520     # scene rows kept (HUD strip above, taskbar / black bars below)
OVERLAY_W, OVERLAY_H = 340, 260   # top-right overlay corner left out


def frames(path, fps, t_from, t_to):
    args = ["ffmpeg", "-v", "error", "-nostdin"]
    if t_from:
        args += ["-ss", str(t_from)]
    if t_to:
        args += ["-to", str(t_to)]
    args += ["-i", path, "-vf", f"fps={fps},scale={W}:{H}:flags=area,format=gray", "-f", "rawvideo", "-"]
    p = subprocess.Popen(args, stdout=subprocess.PIPE)
    n = W * H
    while True:
        buf = p.stdout.read(n)
        if len(buf) < n:
            break
        yield np.frombuffer(buf, dtype=np.uint8).reshape(H, W)
    p.wait()


def black_blocks(a):
    """(whole scene, centre) counts of entirely black blocks; the centre (middle 50 % x 50 %) is always inside the
    loaded chunk grid, so black there is a never-baked level (or a loading screen), never the void."""
    scene = a[TOP:BOTTOM, :].copy()
    scene[: OVERLAY_H - TOP, W - OVERLAY_W:] = 255   # the overlay corner never counts
    h, w = scene.shape
    blk = scene[: h // T * T, : w // T * T].reshape(h // T, T, w // T, T).max(axis=(1, 3)) < 8
    bh, bw = blk.shape
    centre = blk[bh // 4: bh * 3 // 4, bw // 4: bw * 3 // 4]
    return int(blk.sum()), int(centre.sum())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("videos", nargs="+")
    ap.add_argument("--fps", type=float, default=4)
    ap.add_argument("--from", dest="t_from", type=float, default=0)
    ap.add_argument("--to", dest="t_to", type=float, default=0)
    ap.add_argument("--csv")
    a = ap.parse_args()
    rows = []
    for v in a.videos:
        both = np.array([black_blocks(f) for f in frames(v, a.fps, a.t_from, a.t_to)])
        if both.size == 0:
            print(f"{v}: no frames"); continue
        counts, centre = both[:, 0], both[:, 1]
        floor = np.percentile(counts, 10)
        above = int((counts > floor * 1.5 + 4).sum())
        t = (np.arange(counts.size) / a.fps) + a.t_from
        worst = sorted(zip(counts, t), reverse=True)[:6]
        print(f"{v}: {counts.size} frames @ {a.fps}/s; black blocks mean {counts.mean():.0f}  p50 {np.percentile(counts, 50):.0f}"
              f"  p90 {np.percentile(counts, 90):.0f}  max {counts.max()}  floor(p10) {floor:.0f}  frames > floor {above}"
              f" ({100 * above / counts.size:.0f} %)")
        print("   worst: " + ", ".join(f"{c} @ {s:.2f}s" for c, s in worst))
        inplay = centre[(counts < 6000)]   # loading screens and the quit fade are all black everywhere
        print(f"   centre (middle 50 % x 50 %, loading screens excluded): mean {inplay.mean():.1f}  p90 {np.percentile(inplay, 90):.0f}"
              f"  max {inplay.max()}  frames with any {(inplay > 0).sum()} of {inplay.size}"
              f"  frames >= 20 blocks {(inplay >= 20).sum()}")
        rows += [(v, s, c, k) for s, c, k in zip(t, counts, centre)]
    if a.csv:
        with open(a.csv, "w") as f:
            f.write("video,t,black_blocks,centre_black_blocks\n")
            for v, s, c, k in rows:
                f.write(f"{v},{s:.2f},{c},{k}\n")


if __name__ == "__main__":
    main()
