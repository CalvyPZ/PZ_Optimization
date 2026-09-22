#!/usr/bin/env python3
"""Render docs/media/render-distance-card.png: the vanilla chunk grid (19x19 at this resolution) vs the 15x15 grid of the
Render distance option (chunkGridWidth, 2026-09-22), both with every optimization on, desktop 5120x2160 / RTX 4090,
uncapped, max zoom (runs prev-gridspin3-* and card-grid-drive-*). Same media style as harness/lowend-table.py; the
image carries every word of the Workshop's "New! Render distance" section.

    python3 harness/render-distance-card.py
    ffmpeg -y -i docs/media/render-distance-card.png -vf scale=1920:-1 -q:v 3 docs/workshop/images/18-render-distance.jpg
"""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch

OUT = "docs/media/render-distance-card.png"
DATE = "2026-09-22"
# docs/media-style.md, SDR column
BG, PANEL, RULE, INK, INK2, MUTED = "#0b0b0e", "#111114", "#26262c", "#f0f0f4", "#b4b4b8", "#8a8a90"
STOCK, OPT = "#c9592b", "#44de7c"
plt.rcParams["font.family"] = ["Noto Sans", "DejaVu Sans", "sans-serif"]
MONO = "Noto Sans Mono"

# route, (fps, mean ms, p99 ms) vanilla grid, (fps, mean ms, p99 ms) 15x15
ROWS = [
    ("Walking through Rosewood, facing spinning", (223.5, 1000 / 223.5, 13.5), (247.8, 1000 / 247.8, 11.9)),
    ("Driving at 120 km/h", (264.5, 1000 / 264.5, 10.6), (281.9, 1000 / 281.9, 11.1)),
]
FPS_MAX = 300.0

W, ROW_H, HEAD_H, TOP, BOT, PAD = 2100, 96, 84, 236, 150, 40
H = TOP + HEAD_H + ROW_H * len(ROWS) + BOT + PAD
fig = plt.figure(figsize=(W / 100, H / 100), dpi=100)
fig.patch.set_facecolor(BG)
ax = fig.add_axes([0, 0, 1, 1]); ax.set_xlim(0, W); ax.set_ylim(H, 0); ax.axis("off")
ax.add_patch(FancyBboxPatch((PAD, PAD), W - 2 * PAD, H - 2 * PAD, boxstyle="round,pad=0,rounding_size=10",
                            facecolor=PANEL, edgecolor="#505058", linewidth=0.8))

def text(x, y, s, size=15, color=INK, weight="normal", ha="left", va="center", family=None):
    ax.text(x, y, s, fontsize=size, color=color, fontweight=weight, ha=ha, va=va, family=family)

X0 = PAD + 48
text(X0, 86, "New! Render distance", 30, OPT, "bold")
text(W - X0, 86, DATE, 16, MUTED, ha="right", family=MONO)
text(X0, 134, "Options > Optimizations > Chunk streaming: how many chunks per side stay loaded, simulated, lit and drawn "
     "around you. Vanilla (default) or 7 to 15;", 14, INK2)
text(X0, 158, "vanilla is 19 at 1080p and above. For CPU-limited setups (heavy mod lists, NPC mods). Measured here on a "
     "desktop: RTX 4090, 5120x2160, max zoom, uncapped, Linux.", 14, INK2)
text(X0, 196, "Both columns with every optimization on; only the grid differs. fps mean, frame time mean and p99; one run per cell.", 13, MUTED)

X_ROUTE = X0
X_S, X_P = X0 + 760, X0 + 1360
BAR_W, VAL_X = 210, 226
y = TOP
text(X_ROUTE, y + 30, "ROUTE", 12.5, MUTED, "semibold")
for xr, name, col in ((X_S, "VANILLA 19x19 GRID", STOCK), (X_P, "15x15 GRID (120 TILES)", OPT)):
    text(xr, y + 18, name, 12.5, col, "semibold")
    text(xr, y + 44, "fps mean      mean ms  /  p99 ms", 11.5, MUTED)
y += HEAD_H
ax.plot([X0, W - X0], [y - 10, y - 10], color="#505058", lw=1)

for route, s, p in ROWS:
    cy = y + ROW_H / 2
    text(X_ROUTE, cy, route, 16, INK, "semibold")
    for xr, (fps, mean, p99), colour, weight in ((X_S, s, STOCK, "normal"), (X_P, p, OPT, "semibold")):
        w = BAR_W * min(fps, FPS_MAX) / FPS_MAX
        ax.add_patch(FancyBboxPatch((xr, cy - 11), w, 22, boxstyle="round,pad=0,rounding_size=4",
                                    facecolor=colour, edgecolor="none"))
        text(xr + VAL_X, cy, f"{fps:.0f}", 19, colour, weight, family=MONO)
        text(xr + VAL_X + 78, cy, f"{mean:.1f}  /  {p99:.1f}", 14.5, INK2, family=MONO)
    gain = p[0] / s[0]
    text(W - X0, cy, f"{(gain - 1) * 100:+.0f} %", 17, OPT, "semibold", ha="right", family=MONO)
    y += ROW_H
    ax.plot([X0, W - X0], [y, y], color=RULE, lw=0.8)

text(X0, H - PAD - 84, "Bars: fps mean, 0 to 300. This GPU is the limit on both routes (99 % busy), so the gain here is small; "
     "the fewer chunks save CPU work, which matters most where the CPU is the limit.", 12.5, INK2)
text(X0, H - PAD - 60, "The trade-off: at wide zoom the world ends before the screen edge, and zombies, cars and sounds "
     "beyond the grid are not simulated. Restart to apply.", 12.5, INK2)
text(X0, H - PAD - 30, "The runs behind these numbers: github.com/xD3I/PZ_Optimization, commit da3cdea (" + DATE + ").", 11.5, MUTED)
fig.savefig(OUT, dpi=100, facecolor=BG)
print(OUT, W, H)
