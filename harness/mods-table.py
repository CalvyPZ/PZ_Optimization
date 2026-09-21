#!/usr/bin/env python3
"""Render docs/media/workshop-mods-comparison.png: the Workshop performance mods vs this build on the
three uncapped routes (docs/results.md, 2026-09-21). Table form with an inline fps bar per route;
emphasis colouring (this build in the accent, every other row in the de-emphasis gray); numbers in ink."""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch

OUT = "docs/media/workshop-mods-comparison.png"
SURFACE, INK, INK2, MUTED, GRID, ACCENT, DEEMPH, BAD = "#fcfcfb", "#0b0b0b", "#52514e", "#898781", "#e1e0d9", "#2a78d6", "#c3c2b7", "#d03b3b"
plt.rcParams["font.family"] = ["Noto Sans", "DejaVu Sans", "sans-serif"]

# name, subscribers, nature, (fps, p99) x drive / storm / spin, note
ROWS = [
    ("Stock game", "", "the shipped game", (166.7, 15.5), (74.6, 58.7), (135.4, 27.2), ""),
    ("Project Zomboid Optimiser", "23 k", "Lua toggles, F10 control centre", (159.0, 15.6), (71.7, 66.2), (127.8, 29.5), ""),
    ("   + PZO-Launcher engine jar", "", "agent jar, native lib, JVM flags", (165.3, 15.5), (72.2, 65.7), (128.6, 28.5), ""),
    ("Tempo", "42 k", "Lua sampler, menu memo", (160.0, 15.3), (70.8, 61.0), (130.0, 27.9), ""),
    ("   + its class shadows", "", "chunk-finalize budget, 3D-zombie cap", (162.1, 15.3), (75.7, 56.9), (130.5, 30.3), ""),
    ("Multi-Cpu Enhance", "28 k", "launcher JSON: ParallelGC, 8 GB heap", (169.4, 15.2), (73.8, 61.9), (135.9, 28.7), "300-350 ms GC stall in every route"),
    ("Every Texture Optimized", "616 k", "6,142 re-encoded textures", (163.1, 15.2), (72.3, 63.3), (134.6, 28.8), ""),
    ("Lugli - Optimizations", "3 k", "ZombieBuddy: wind gate, z-extents", (162.0, 15.3), (72.8, 60.0), (135.2, 28.0), ""),
    ("Zed's Better FPS (42.20 fix)", "47 k", "ZombieBuddy: GL state, sprite batching", (161.4, 15.2), (74.5, 59.0), (134.3, 27.7), ""),
    ("PZ_Optimization", "", "class overrides (this repo)", (481.2, 8.8), (245.6, 13.8), (456.2, 8.5), ""),
]
ROUTES = ["120 km/h drive", "120 km/h drive, thunderstorm", "Rosewood spin"]
FPS_MAX = 500.0

W, ROW_H, HEAD_H, TOP, BOT = 2000, 58, 96, 116, 70
H = TOP + HEAD_H + ROW_H * len(ROWS) + BOT
fig = plt.figure(figsize=(W / 100, H / 100), dpi=100)
fig.patch.set_facecolor(SURFACE)
ax = fig.add_axes([0, 0, 1, 1]); ax.set_xlim(0, W); ax.set_ylim(H, 0); ax.axis("off")

def text(x, y, s, size=15, color=INK, weight="normal", ha="left", va="center", family=None):
    ax.text(x, y, s, fontsize=size, color=color, fontweight=weight, ha=ha, va=va, family=family)

# title / subtitle
text(48, 40, "Project Zomboid Build 42 performance mods, measured on the same routes", 22, INK, "semibold")
text(48, 78, "Uncapped, 5120x2160, one mod at a time on the stock game, in-game overlay log over the route window; "
     "fps mean and p99 frame time. One run per cell, 2026-09-21.", 13.5, INK2)

# columns
X_MOD, X_SUB, X_WHAT = 48, 470, 560
X_R = [930, 1290, 1650]; BAR_W = 170; VAL_X = 185
y = TOP
text(X_MOD, y + 30, "Mod", 13, MUTED, "semibold")
text(X_SUB, y + 30, "Subs", 13, MUTED, "semibold")
text(X_WHAT, y + 30, "What it is", 13, MUTED, "semibold")
for xr, name in zip(X_R, ROUTES):
    text(xr, y + 18, name, 13, MUTED, "semibold")
    text(xr, y + 44, "fps mean   /   p99 ms", 11.5, MUTED)
y += HEAD_H
ax.plot([48, W - 48], [y - 10, y - 10], color="#c3c2b7", lw=1)

for name, subs, what, *cells, note in ROWS:
    ours = name == "PZ_Optimization"
    cy = y + ROW_H / 2
    if ours:
        ax.add_patch(FancyBboxPatch((40, y + 4), W - 80, ROW_H - 8, boxstyle="round,pad=0,rounding_size=6",
                                    facecolor="#eaf2fc", edgecolor="none"))
    text(X_MOD, cy, name.strip() if not name.startswith("   ") else name, 15, INK, "semibold" if ours else "normal")
    text(X_SUB, cy, subs, 14, INK2)
    text(X_WHAT, cy, what, 13.5, INK2)
    for xr, (fps, p99) in zip(X_R, cells):
        w = BAR_W * fps / FPS_MAX
        ax.add_patch(FancyBboxPatch((xr, cy - 9), w, 18, boxstyle="round,pad=0,rounding_size=4",
                                    facecolor=ACCENT if ours else DEEMPH, edgecolor="none"))
        text(xr + VAL_X, cy, f"{fps:.0f}", 15, INK, "semibold" if ours else "normal", ha="left", family="DejaVu Sans")
        text(xr + VAL_X + 52, cy, f"/  {p99:.1f}", 13.5, INK2, ha="left", family="DejaVu Sans")
    if note:
        text(X_WHAT, cy + 20, note, 11, BAD)
    y += ROW_H
    ax.plot([48, W - 48], [y, y], color=GRID, lw=0.8)

text(48, H - 40, "Bars: fps mean, same scale on every route (0 to 500). Every mod is within run-to-run noise of stock; "
     "Multi-Cpu Enhance's ParallelGC adds a 300-350 ms stop-the-world pause per route (stock G1 max 21 ms).", 12, INK2)
text(48, H - 18, "Per-mod details and every number: docs/results.md, 2026-09-21 sections.", 11.5, MUTED)
fig.savefig(OUT, dpi=100, facecolor=SURFACE)
print(OUT, W, H)
