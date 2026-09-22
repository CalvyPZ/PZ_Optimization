#!/usr/bin/env python3
"""Ghosting / trail and softness of a drive recording (temporal-upscaler image quality).

Written for the upscaler comparison (docs/plan-upscalers.md): does a mode leave trails behind
moving content, and how much fine detail does it keep while driving?

  asym   temporal asymmetry: after aligning the previous and the next capture frame onto the
         current one (phase correlation, integer px, edge pixels only), does the current frame
         resemble its PAST more than its FUTURE? A renderer with no history is symmetric;
         one that leaks history leans on the past. > 0 = trailing.
  aniso  along/perp gradient energy: a trail smears detail ALONG the scroll direction.
         trail = 1 - aniso(run)/aniso(off).
  sharp  Laplacian RMS = crispness of the moving image; soft = 1 - sharp(run)/sharp(off).

Use --from -1 for the whole route (route_start+2 .. route_end-2 from pzopt-bench.out), so every
run averages the same scenery; runs drift apart on the route, so shorter windows compare
different places. The first run named, or the first whose label contains "upsd-off", is the
reference for trail / soft.

Result on the 2026-09-22 upsd-* set (15 runs, 5120x2160, ~2100 frames each): asym within
+-0.002 (SE 0.006) and trail within 1.2 % for EVERY mode including all seven DLSS presets, i.e.
no detectable trailing; soft separates far outside the repeat-pair noise (two runs of the same
mode: 1.8 % off, 0.3 % fsr1) at 2.5 % for fsr1 up to 31 % for DLSS ultra. Read a null asym as
"no trail above the noise floor of this rig", not as proof of none: the capture is 60 fps AV1
(same encoder for every run) and the route has no zombie crowd, so character ghosting is out
of scope here.

Usage: ghostscan.py <run dir>... [--from -1 | --from 8 --len 12] [--json out.json]
"""
import json
import subprocess
import sys

import numpy as np

CROP = "crop=4000:1500:600:200"   # world area of the 5120x2160 capture, HUD corners excluded
W, H = 1000, 375


def frames(mp4, start, length):
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-ss", f"{start:.3f}", "-i", mp4,
           "-t", f"{length:.3f}", "-vf", f"{CROP},scale={W}:{H},format=gray",
           "-f", "rawvideo", "-pix_fmt", "gray", "-"]
    raw = subprocess.run(cmd, capture_output=True).stdout
    n = len(raw) // (W * H)
    return np.frombuffer(raw[: n * W * H], dtype=np.uint8).reshape(n, H, W).astype(np.float32)


def shift_of(a, b):
    fa, fb = np.fft.rfft2(a), np.fft.rfft2(b)
    cross = fa.conj() * fb
    cross /= np.abs(cross) + 1e-6
    corr = np.fft.irfft2(cross, s=a.shape)
    dy, dx = np.unravel_index(np.argmax(corr), corr.shape)
    if dy > a.shape[0] // 2:
        dy -= a.shape[0]
    if dx > a.shape[1] // 2:
        dx -= a.shape[1]
    return int(dy), int(dx)


def scan(run, t_from, t_len):
    opts = dict(l.strip().split("=", 1) for l in open(f"{run}/run.opts") if "=" in l)
    bench = dict(l.strip().split("=", 1) for l in open(f"{run}/pzopt-bench.out") if "=" in l)
    launch = float(opts["launch_epoch"])
    rstart = float(bench["route_start_epoch_ms"]) / 1000 - launch
    rend = float(bench["route_end_epoch_ms"]) / 1000 - launch
    if t_from < 0:                      # --from -1: the whole route, so every run averages the same scenery
        start, length = rstart + 2, (rend - 2) - (rstart + 2)
    else:
        start, length = rstart + t_from, t_len
    fr = frames(f"{run}/recording.mp4", start, length)

    shifts = [shift_of(fr[i - 1], fr[i]) for i in range(1, len(fr), 4)]
    dy = float(np.median([s[0] for s in shifts]))
    dx = float(np.median([s[1] for s in shifts]))
    norm = (dy * dy + dx * dx) ** 0.5
    uy, ux = (dy / norm, dx / norm) if norm else (0.0, 1.0)

    # temporal asymmetry: does a frame resemble its aligned PAST more than its aligned FUTURE?
    # a renderer with no history is symmetric in time; one that accumulates leans on the past.
    asym = []
    for i in range(1, len(fr) - 1):
        cur, prev, nxt = fr[i], fr[i - 1], fr[i + 1]
        dyp, dxp = shift_of(prev, cur)
        dyn, dxn = shift_of(nxt, cur)
        if max(abs(dyp), abs(dxp)) < 1 or max(abs(dyn), abs(dxn)) < 1:
            continue
        m = max(abs(dyp), abs(dxp), abs(dyn), abs(dxn)) + 2
        sl = (slice(m, -m), slice(m, -m))
        pw = np.roll(np.roll(prev, dyp, 0), dxp, 1)[sl]
        nw = np.roll(np.roll(nxt, dyn, 0), dxn, 1)[sl]
        gy, gx = np.gradient(cur[sl])          # a trail lives on edges; flat sky/road only adds noise
        edge = np.hypot(gy, gx) > np.percentile(np.hypot(gy, gx), 90)
        ep = np.abs(cur[sl] - pw)[edge].mean()
        en = np.abs(cur[sl] - nw)[edge].mean()
        if ep + en > 1e-6:
            asym.append(float((en - ep) / (en + ep)))

    along, perp, sharp = [], [], []
    for f in fr[::2]:
        gy, gx = np.gradient(f)
        a = uy * gy + ux * gx            # derivative along the scroll
        p = -ux * gy + uy * gx           # derivative across it
        along.append(float(np.sqrt((a ** 2).mean())))
        perp.append(float(np.sqrt((p ** 2).mean())))
        lap = 4 * f[1:-1, 1:-1] - f[:-2, 1:-1] - f[2:, 1:-1] - f[1:-1, :-2] - f[1:-1, 2:]
        sharp.append(float(np.sqrt((lap ** 2).mean())))
    A, P = float(np.mean(along)), float(np.mean(perp))
    return {"run": run.rstrip("/").split("/")[-1], "frames": len(fr),
            "window_s": round(length, 1), "scroll_px": round(norm, 2), "along": A, "perp": P, "aniso": A / P,
            "asym": float(np.median(asym)) if asym else None, "asym_n": len(asym),
            "asym_se": float(np.std(asym) / max(1, len(asym)) ** 0.5) if asym else None,
            "sharp": float(np.mean(sharp))}


if __name__ == "__main__":
    argv = sys.argv[1:]
    args, i = [], 0
    while i < len(argv):
        if argv[i].startswith("--"):
            i += 2            # every flag here takes a value
        else:
            args.append(argv[i]); i += 1
    def opt(name, default):
        return float(sys.argv[sys.argv.index(name) + 1]) if name in sys.argv else default
    rows = [scan(r, opt("--from", 8.0), opt("--len", 12.0)) for r in args]
    base = next((r for r in rows if "upsd-off" in r["run"]), rows[0])
    print(f'{"run":34} {"scroll":>6} {"asym":>13} {"aniso":>6} {"trail":>7} {"sharp":>6} {"soft":>6}')
    for r in rows:
        r["trail"] = 1 - r["aniso"] / base["aniso"]
        r["soft"] = 1 - r["sharp"] / base["sharp"]
        a = f'{r["asym"]:+.3f}+-{r["asym_se"]:.3f}' if r["asym"] is not None else '           -'
        print(f'{r["run"][:34]:34} {r["scroll_px"]:6.1f} {a:>13} {r["aniso"]:6.3f} {r["trail"]:+7.1%} '
              f'{r["sharp"]:6.2f} {r["soft"]:+6.1%}', flush=True)
    if "--json" in sys.argv:
        json.dump(rows, open(sys.argv[sys.argv.index("--json") + 1], "w"), indent=1)
