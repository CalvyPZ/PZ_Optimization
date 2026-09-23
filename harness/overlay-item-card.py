#!/usr/bin/env python3
"""Render docs/media/overlay-item-card.png: the Workshop's "New! Performance overlay in the menus" section as one image
(the description embeds only the picture, see the release-windows skill). Same media style as harness/updater-card.py
(docs/media-style.md): near-black surface, Noto Sans, the date in Noto Sans Mono, green for what is new. The picture is
two captures of the desktop build (5120x2160, 2026-09-23, harness/pad-overlay-check.sh), the left 1700 px column at
half size: the main menu with the virtual pad's focus on the item and the overlay shown, and the pause menu
(docs/media/overlay-item-menu.png, overlay-item-pause.png).

    python3 harness/overlay-item-card.py
    ffmpeg -y -i docs/media/overlay-item-card.png -vf scale=1920:-1 -q:v 3 docs/workshop/images/19-overlay-item.jpg
"""
from PIL import Image, ImageDraw, ImageFont

OUT = "docs/media/overlay-item-card.png"
SHOTS = ["docs/media/overlay-item-menu.png", "docs/media/overlay-item-pause.png"]
CAPS = ["main menu (controller focus on the item)", "pause menu"]
DATE = "2026-09-23"
BG, PANEL, RING, INK, INK2, MUTED, OPT = "#0b0b0e", "#111114", "#505058", "#f0f0f4", "#b4b4b8", "#8a8a90", "#44de7c"
FONTS = "/usr/share/fonts/noto/"


def font(name, size):
    return ImageFont.truetype(FONTS + name, size)

TITLE, BODY, NOTE, MONO, CAP = (font("NotoSans-Bold.ttf", 60), font("NotoSans-Regular.ttf", 28), font("NotoSans-Regular.ttf", 26),
                                font("NotoSansMono-Regular.ttf", 32), font("NotoSans-Bold.ttf", 26))

W = 2100
PAD, X0 = 40, 88
inner = W - 2 * X0
GAP = 24

LINES = [
    (BODY, INK2, "SHOW / HIDE PERFORMANCE OVERLAY sits right below OPTIONS in the main menu and in the pause menu: the same toggle"),
    (BODY, INK2, "as the F9 key, without a keyboard. With a controller, the D-pad reaches it like any other item and A toggles it."),
    (NOTE, MUTED, "The overlay needs \"Sample frame times and utilization\" (Options > Optimizations > Performance overlay); without it the item"),
    (NOTE, MUTED, "shows the same restart notice as the key. Windows, Linux and macOS."),
]

shots = [Image.open(p).convert("RGB") for p in SHOTS]
s = (inner - GAP) / sum(im.width for im in shots)
shots = [im.resize((round(im.width * s), round(im.height * s)), Image.LANCZOS) for im in shots]

TOP = 170
y_text = TOP
for f, _, _ in LINES:
    y_text += f.size + 10
SHOT_Y = y_text + 28 + CAP.size + 12
pic_h = max(im.height for im in shots)
H = SHOT_Y + pic_h + 24 + PAD

im = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(im)
d.rounded_rectangle((PAD, PAD, W - PAD, H - PAD), radius=10, fill=PANEL, outline=RING, width=1)
d.text((X0, 66), "New! Performance overlay in the menus", font=TITLE, fill=OPT)
d.text((W - X0, 84), DATE, font=MONO, fill=MUTED, anchor="ra")
y = TOP
for f, colour, text in LINES:
    d.text((X0, y), text, font=f, fill=colour)
    y += f.size + 10

x = X0
cap_y = SHOT_Y - CAP.size - 12
for i, m in enumerate(shots):
    d.text((x, cap_y), CAPS[i], font=CAP, fill=OPT)
    d.rounded_rectangle((x - 1, SHOT_Y - 1, x + m.width, SHOT_Y + m.height), radius=4, outline=RING, width=1)
    im.paste(m, (x, SHOT_Y))
    x += m.width + GAP
im.save(OUT)
print(OUT, im.size)
