#!/usr/bin/env python3
"""Render docs/media/preview-card.png: the Workshop's "New! See what every setting does" section as one image
(the description embeds only the picture, see the release-windows skill). Same media style as
harness/profiler-card.py (docs/media-style.md): near-black surface, Noto Sans, the date in Noto Sans Mono,
green for what is new. The picture is the Optimizations tab with its preview panel, cropped from a live
capture (docs/media/options-preview-full.png: the 5120x2160 desktop, Options > Optimizations, the mouse on
"Trees: bake into chunk textures").

    python3 harness/preview-card.py
    ffmpeg -y -i docs/media/preview-card.png -vf scale=1920:-1 -q:v 3 docs/workshop/images/14-options-preview.jpg
"""
from PIL import Image, ImageDraw, ImageFont

OUT = "docs/media/preview-card.png"
SHOT = "docs/media/options-preview-full.png"
DATE = "2026-09-22"
BG, PANEL, RING, INK, INK2, MUTED, OPT = "#0b0b0e", "#111114", "#505058", "#f0f0f4", "#b4b4b8", "#8a8a90", "#44de7c"
FONTS = "/usr/share/fonts/noto/"


def font(name, size):
    return ImageFont.truetype(FONTS + name, size)

TITLE, BODY, NOTE, MONO = font("NotoSans-Bold.ttf", 60), font("NotoSans-Regular.ttf", 28), font("NotoSans-Regular.ttf", 26), font("NotoSansMono-Regular.ttf", 32)

shot = Image.open(SHOT).convert("RGB")
W = 2100
PAD, X0 = 40, 88
inner = W - 2 * X0
scale = inner / shot.width
shot = shot.resize((inner, round(shot.height * scale)), Image.LANCZOS)

LINES = [
    (BODY, INK2, "Point at any setting in Options > Optimizations and the panel beside the list plays the stock game and the"),
    (BODY, INK2, "optimized build side by side on the same route (24 fps clips of the harness recordings, the live frame rate"),
    (BODY, INK2, "burned in), repeats what the setting does, and rates its load on each part of your machine."),
    (NOTE, MUTED, "Bars on a lowest / low / mid / high / ultra / max axis, mid = the stock game: CPU game thread, CPU render thread, other"),
    (NOTE, MUTED, "cores, GPU, VRAM, RAM, disk, boot and load time, chunk arrival. The overlay settings show the overlay off and on."),
]
TOP = 170
y_text = TOP
for f, _, _ in LINES:
    y_text += f.size + 10
SHOT_Y = y_text + 28
FOOT = [
    "Nothing moves as the mouse crosses the list: every part of the panel has a fixed place. The clips are decoded in the game and",
    "freed when the options screen closes. 5120x2160 desktop; the same layout fills whatever is right of the controls on any screen.",
]
H = SHOT_Y + shot.height + 18 + len(FOOT) * (NOTE.size + 8) + 24 + PAD

im = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(im)
d.rounded_rectangle((PAD, PAD, W - PAD, H - PAD), radius=10, fill=PANEL, outline=RING, width=1)
d.text((X0, 66), "New! See what every setting does", font=TITLE, fill=OPT)
d.text((W - X0, 84), DATE, font=MONO, fill=MUTED, anchor="ra")
y = TOP
for f, colour, text in LINES:
    d.text((X0, y), text, font=f, fill=colour)
    y += f.size + 10
d.rounded_rectangle((X0 - 1, SHOT_Y - 1, X0 + shot.width, SHOT_Y + shot.height), radius=4, outline=RING, width=1)
im.paste(shot, (X0, SHOT_Y))
fy = SHOT_Y + shot.height + 18
for line in FOOT:
    d.text((X0, fy), line, font=NOTE, fill=MUTED)
    fy += NOTE.size + 8
im.save(OUT)
print(OUT, im.size)
