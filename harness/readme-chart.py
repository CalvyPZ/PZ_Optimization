#!/usr/bin/env python3
"""Render docs/media/drive-results.svg: stock vs optimized on the two driving routes.

Numbers are the route-window statistics printed by harness/analyze.py for the runs
named in RUNS (in-game frame sampler; GPU busy from sysmon; chunk latency is the
streamer queue wait). Re-run after new showcase runs and paste the new figures here.
"""

# speed label -> (stock run, optimized run)
RUNS = {
    "60 km/h": ("show-stock-1-20260919-000959", "show-opt-1-20260919-001316"),
    "120 km/h": ("show120-stock-2-20260919-005559", "show120-opt-1-20260919-005848"),
}

# metric -> speed -> (stock, optimized)
DATA = {
    "frame mean": {"60 km/h": (8.2, 4.2), "120 km/h": (10.6, 6.4)},
    "frame p90": {"60 km/h": (14.4, 4.2), "120 km/h": (15.1, 8.4)},
    "frame p99": {"60 km/h": (18.3, 5.4), "120 km/h": (20.1, 11.1)},
    "frame p99.9": {"60 km/h": (23.1, 18.4), "120 km/h": (25.3, 19.1)},
    "gpu busy": {"60 km/h": (92, 54), "120 km/h": (82, 47)},
    "chunk p50": {"60 km/h": (153.1, 4.1), "120 km/h": (136.4, 4.2)},
    "chunk p99": {"60 km/h": (295.9, 21.1), "120 km/h": (319.2, 18.6)},
}

PANELS = [
    ("Frame time, ms (lower is better)", ["frame mean", "frame p90", "frame p99", "frame p99.9"], 28, "ms"),
    ("GPU busy, % of the route", ["gpu busy"], 100, "%"),
    ("Chunk latency, ms from request to ready", ["chunk p50", "chunk p99"], 340, "ms"),
]

SURFACE, INK, INK2, MUTED, GRID = "#fcfcfb", "#0b0b0b", "#52514e", "#8a8983", "#e6e5e1"
SERIES = [("stock", "#2a78d6"), ("optimized", "#eb6834")]
FONT = "font-family='-apple-system,Segoe UI,Helvetica,Arial,sans-serif'"

W = 1200
LEFT, RIGHT = 200, 40
BAR, GAP, GROUP_GAP, SPEED_GAP = 14, 2, 14, 10
ROW = BAR * 2 + GAP + GROUP_GAP


def fmt(v, unit):
    if unit == "%" or float(v).is_integer() or v >= 100:
        return f"{v:.0f}"
    return f"{v:.1f}"


def main():
    out = []
    y = 70
    out.append(f"<text x='{LEFT}' y='34' {FONT} font-size='20' font-weight='600' fill='{INK}'>Project Zomboid B42, 1,200 tiles east of Rosewood at max zoom</text>")
    out.append(f"<text x='{LEFT}' y='54' {FONT} font-size='13' fill='{INK2}'>Route-window statistics from harness/analyze.py, 5120x2160, native Linux, NVIDIA GL, RTX 4090 / 9800X3D, 2026-09-19</text>")
    lx = W - RIGHT - 190
    for i, (name, color) in enumerate(SERIES):
        out.append(f"<rect x='{lx + i * 100}' y='24' width='12' height='12' rx='2' fill='{color}'/>")
        out.append(f"<text x='{lx + i * 100 + 18}' y='35' {FONT} font-size='13' fill='{INK2}'>{name}</text>")
    plot_w = W - LEFT - RIGHT
    for title, metrics, vmax, unit in PANELS:
        y += 24
        out.append(f"<text x='{LEFT}' y='{y}' {FONT} font-size='14' font-weight='600' fill='{INK}'>{title}</text>")
        y += 10
        top = y
        for m in metrics:
            for speed, (stock, opt) in DATA[m].items():
                out.append(f"<text x='{LEFT - 12}' y='{y + BAR + GAP / 2 + 4}' {FONT} font-size='12' text-anchor='end' fill='{INK}'>{m}</text>")
                out.append(f"<text x='{LEFT - 12}' y='{y + BAR + GAP / 2 + 18}' {FONT} font-size='11' text-anchor='end' fill='{MUTED}'>{speed}</text>")
                for i, v in enumerate((stock, opt)):
                    by = y + i * (BAR + GAP)
                    w = max(2, plot_w * v / vmax)
                    out.append(f"<rect x='{LEFT}' y='{by}' width='{w:.1f}' height='{BAR}' fill='{SERIES[i][1]}'/>")
                    out.append(f"<text x='{LEFT + w + 6:.1f}' y='{by + BAR - 3}' {FONT} font-size='12' fill='{INK}'>{fmt(v, unit)}</text>")
                ratio = stock / opt
                if ratio >= 1.5:
                    out.append(f"<text x='{LEFT + plot_w * opt / vmax + 46:.1f}' y='{y + BAR + BAR - 3}' {FONT} font-size='11' fill='{INK2}'>{ratio:.1f}× lower</text>")
                y += ROW + SPEED_GAP
        # axis
        for k in range(0, 5):
            gx = LEFT + plot_w * k / 4
            out.insert(len(out) - 0, f"<line x1='{gx:.1f}' y1='{top - 4}' x2='{gx:.1f}' y2='{y - GROUP_GAP - SPEED_GAP + 4}' stroke='{GRID}' stroke-width='1'/>")
            out.append(f"<text x='{gx:.1f}' y='{y - GROUP_GAP - SPEED_GAP + 18}' {FONT} font-size='11' text-anchor='middle' fill='{MUTED}'>{fmt(vmax * k / 4, unit)}</text>")
        y += 14
    y += 20
    for s, (a, b) in RUNS.items():
        out.append(f"<text x='{LEFT}' y='{y}' {FONT} font-size='11' fill='{MUTED}'>{s}: {a} (stock) vs {b} (optimized)</text>")
        y += 16
    out.append(f"<text x='{LEFT}' y='{y}' {FONT} font-size='11' fill='{MUTED}'>Optimized = Config.java defaults; stock = every override switched off. Frame times from the in-game sampler, GPU from sysmon, chunk latency = streamer queue wait.</text>")
    h = y + 20
    svg = [f"<svg xmlns='http://www.w3.org/2000/svg' width='{W}' height='{h}' viewBox='0 0 {W} {h}' role='img' aria-label='Stock versus optimized frame time, GPU load and chunk latency at 60 and 120 km/h'>",
           f"<rect width='{W}' height='{h}' fill='{SURFACE}'/>"]
    # grid lines go under the bars
    grid = [l for l in out if l.startswith("<line")]
    rest = [l for l in out if not l.startswith("<line")]
    svg += grid + rest + ["</svg>"]
    path = __import__("os").path.join(__import__("os").path.dirname(__file__), "..", "docs", "media", "drive-results.svg")
    with open(path, "w") as f:
        f.write("\n".join(svg) + "\n")
    print(path, h)


if __name__ == "__main__":
    main()
