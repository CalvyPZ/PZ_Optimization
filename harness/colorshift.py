#!/usr/bin/env python3
"""Colour statistics of two recordings over the same route phase: the colour-shift check that
parity-judge.py (grayscale metrics) cannot make.

  harness/colorshift.py <run-or-mp4 A> <run-or-mp4 B> [--seconds 20 | --window START END | --window-a S E --window-b S E] [--scale 640] [--json out.json]

Same window rule as parity-judge.py (the last --seconds of on-screen activity, aligned at the
quit-to-black), HUD corners of a 4x4 grid excluded. Per recording: mean R/G/B, mean saturation,
the share of saturated pixels and their hue histogram (12 bins of 30 degrees), and the share of
"skin / clothing" mid-tones. Reports B against A: per-channel mean ratios, saturation ratio and the
L1 distance between the hue histograms (0 = identical, 2 = disjoint). Two runs of the same build on
the Louisville route sit around 0.05; a palette that comes out grey, black or tinted moves the
saturation ratio and the hue distance by an order of magnitude. Exit 0 = within the same-build
band (hue L1 < 0.2 and every ratio within 0.85..1.15), 1 = a colour shift, 2 = usage.
"""
import json
import subprocess
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import importlib.util as _ilu  # noqa: E402

_spec = _ilu.spec_from_file_location("parity_judge", Path(__file__).parent / "parity-judge.py")
_pj = _ilu.module_from_spec(_spec)
_spec.loader.exec_module(_pj)
video_of, active_window, GRID, HUD_CELLS = _pj.video_of, _pj.active_window, _pj.GRID, _pj.HUD_CELLS

FPS = 10.0
BINS = 12


def read_rgb(path, start, end, width, fps):
    height = width * 2160 // 5120
    vf = f"fps={fps},scale={width}:{height},format=rgb24"
    cmd = ["ffmpeg", "-v", "error", "-ss", str(start), "-to", str(end), "-i", str(path), "-vf", vf, "-f", "rawvideo", "-"]
    raw = subprocess.run(cmd, check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.uint8).reshape(-1, height, width, 3)


def hud_mask(h, w):
    m = np.ones((h, w), bool)
    gh, gw = h // GRID, w // GRID
    for (cy, cx) in HUD_CELLS:
        m[cy * gh:(cy + 1) * gh, cx * gw:(cx + 1) * gw] = False
    return m


def measure(path, start, end, scale):
    f = read_rgb(path, start, end, scale, FPS).astype(np.float32) / 255.0
    n, h, w, _ = f.shape
    m = hud_mask(h, w)
    px = f[:, m, :].reshape(-1, 3)                       # every world pixel of every frame
    mx, mn = px.max(axis=1), px.min(axis=1)
    sat = np.where(mx > 0, (mx - mn) / np.maximum(mx, 1e-6), 0.0)
    val = mx
    lit = val > 0.15                                      # ignore the night / unseen black
    saturated = lit & (sat > 0.25)
    r, g, b = px[:, 0], px[:, 1], px[:, 2]
    # hue in degrees for the saturated pixels
    d = np.maximum(mx - mn, 1e-6)
    hue = np.where(mx == r, (g - b) / d % 6, np.where(mx == g, (b - r) / d + 2, (r - g) / d + 4)) * 60.0
    hist, _ = np.histogram(hue[saturated], bins=BINS, range=(0.0, 360.0))
    hist = hist / max(1, saturated.sum())
    mid = lit & (val < 0.8) & (sat > 0.08) & (sat < 0.6)   # the skin / clothing mid-tones of characters and the ground
    return {
        "video": str(path), "window_s": [round(start, 1), round(end, 1)], "frames": int(n), "world_px_per_frame": int(m.sum()),
        "mean_rgb_0_255": [round(float(px[lit, c].mean() * 255), 1) for c in range(3)],
        "mean_saturation_lit": round(float(sat[lit].mean()), 4),
        "lit_share": round(float(lit.mean()), 4),
        "saturated_share_of_lit": round(float(saturated.sum() / max(1, lit.sum())), 4),
        "midtone_share_of_lit": round(float(mid.sum() / max(1, lit.sum())), 4),
        "hue_hist_30deg": [round(float(x), 4) for x in hist],
    }


def ratio(x, y):
    return round(x / y, 3) if y else None


def main(argv):
    specs, window, scale, out_json, seconds = [], None, 640, None, 20.0
    windows = [None, None]  # per-side override, like parity-judge.py's --window-a / --window-b
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--window":
            window = (float(argv[i + 1]), float(argv[i + 2])); i += 3
        elif a in ("--window-a", "--window-b"):
            windows[0 if a == "--window-a" else 1] = (float(argv[i + 1]), float(argv[i + 2])); i += 3
        elif a == "--scale":
            scale = int(argv[i + 1]); i += 2
        elif a == "--seconds":
            seconds = float(argv[i + 1]); i += 2
        elif a == "--json":
            out_json = argv[i + 1]; i += 2
        else:
            specs.append(a); i += 1
    if len(specs) != 2:
        print(__doc__)
        return 2
    m = []
    for side, spec in enumerate(specs):
        v = video_of(spec)
        s, e = windows[side] if windows[side] else (window if window else active_window(v, seconds))
        m.append(measure(v, s, e, scale))
        r = m[-1]
        print(f"{v}: {r['frames']} frames {s:.1f}-{e:.1f}s, mean rgb {r['mean_rgb_0_255']}, saturation {r['mean_saturation_lit']}, "
              f"lit {r['lit_share']}, saturated {r['saturated_share_of_lit']}, midtones {r['midtone_share_of_lit']}")
    a, b = m
    ha, hb = np.array(a["hue_hist_30deg"]), np.array(b["hue_hist_30deg"])
    cmp = {
        "mean_rgb_ratio_b_over_a": [ratio(b["mean_rgb_0_255"][c], a["mean_rgb_0_255"][c]) for c in range(3)],
        "saturation_ratio": ratio(b["mean_saturation_lit"], a["mean_saturation_lit"]),
        "saturated_share_ratio": ratio(b["saturated_share_of_lit"], a["saturated_share_of_lit"]),
        "midtone_share_ratio": ratio(b["midtone_share_of_lit"], a["midtone_share_of_lit"]),
        "hue_hist_l1": round(float(np.abs(ha - hb).sum()), 4),
    }
    ratios = [x for x in cmp["mean_rgb_ratio_b_over_a"] + [cmp["saturation_ratio"], cmp["saturated_share_ratio"], cmp["midtone_share_ratio"]] if x is not None]
    ok = cmp["hue_hist_l1"] < 0.2 and all(0.85 <= x <= 1.15 for x in ratios)
    print(f"colorshift={'none' if ok else 'SHIFT'} rgb_ratio={cmp['mean_rgb_ratio_b_over_a']} sat_ratio={cmp['saturation_ratio']} "
          f"saturated_ratio={cmp['saturated_share_ratio']} midtone_ratio={cmp['midtone_share_ratio']} hue_l1={cmp['hue_hist_l1']}")
    if out_json:
        Path(out_json).write_text(json.dumps({"a": a, "b": b, "b_over_a": cmp, "within_band": ok}, indent=1))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
