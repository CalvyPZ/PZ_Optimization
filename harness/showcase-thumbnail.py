#!/usr/bin/env python3
"""YouTube thumbnail for the showcase: a frame of the maintainer's own capture (the results-card
background), tone-mapped, cropped 16:9 around the player and the zombie, "PZ optimized before
GTA VI" on top and the stock-vs-optimized fps of the 120 km/h drive big at the bottom, both drawn
over the frame on dark bands.

  harness/showcase-thumbnail.py [out.jpg]
  env: THUMB_SRC (video), THUMB_T (seconds, default 25.833), THUMB_CROP x:y:w:h in source pixels
       (default 1150:200:2880:1620 = the pair centred, 25 s of Video_2026-09-20_19-10-46.mp4),
       FPS_STOCK / FPS_OPT (default 174 / 632, the 1 s-average readouts on the poster frame)
       THUMB_HEADER (default "PZ optimized before GTA VI"), THUMB_ONLY_OPT=1 for "632 fps" alone
       (the Workshop variant: THUMB_HEADER="PZ Optimized" THUMB_ONLY_OPT=1 ... docs/workshop/images/00-showcase-thumbnail.jpg)
"""
import os, subprocess, sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(REPO, 'docs/media/showcase-stock-vs-all-optimizations-thumbnail.jpg')
src = os.environ.get('THUMB_SRC', os.path.expanduser('~/Videos/Project Zomboid/Video_2026-09-20_19-10-46.mp4'))
t = float(os.environ.get('THUMB_T', '25.833'))   # 25 s + 50 frames: the shot zombie flying, a second one reaching in from the left
cx, cy, cw, ch = (int(v) for v in os.environ.get('THUMB_CROP', '1150:200:2880:1620').split(':'))
fps_stock, fps_opt = os.environ.get('FPS_STOCK', '174'), os.environ.get('FPS_OPT', '632')
header = os.environ.get('THUMB_HEADER', 'PZ optimized before GTA VI')
only_opt = os.environ.get('THUMB_ONLY_OPT') == '1'
W, H = 2560, 1440
BLACK, BOLD = '/usr/share/fonts/noto/NotoSans-Black.ttf', '/usr/share/fonts/noto/NotoSans-Bold.ttf'

frame = '/tmp/pzopt-showcase-thumb-src.png'
subprocess.run(['ffmpeg', '-v', 'error', '-y', '-ss', f'{t}', '-i', src, '-frames:v', '1', '-vf',
                'zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,'
                'zscale=p=bt709:t=bt709:m=bt709,format=rgb24', frame], check=True)
im = Image.open(frame).convert('RGB').crop((cx, cy, cx + cw, cy + ch)).resize((W, H), Image.LANCZOS)

# dark bands top and bottom so the text reads on any background
band = Image.new('RGBA', (W, H), (0, 0, 0, 0))
bd = ImageDraw.Draw(band)
bd.rectangle((0, 0, W, 300), fill=(0, 0, 0, 140))
bd.rectangle((0, H - 420, W, H), fill=(0, 0, 0, 165))
band = band.filter(ImageFilter.GaussianBlur(40))
im = Image.alpha_composite(im.convert('RGBA'), band)
d = ImageDraw.Draw(im)

def text(x, y, s, font, fill, anchor='la', stroke=10):
    d.text((x + 8, y + 8), s, font=font, fill=(0, 0, 0, 200), anchor=anchor, stroke_width=stroke, stroke_fill=(0, 0, 0, 200))
    d.text((x, y), s, font=font, fill=fill, anchor=anchor, stroke_width=stroke, stroke_fill=(0, 0, 0, 255))

WHITE, AMBER, GREEN = (245, 245, 245), (255, 176, 64), (96, 230, 120)
# top: the header line
text(W // 2, 150, header, ImageFont.truetype(BLACK, 150), WHITE, anchor='mm')
# bottom: 174 vs 632 fps (or 632 fps alone), measured so the whole line is centred
big, mid, small = ImageFont.truetype(BLACK, 330), ImageFont.truetype(BOLD, 140), ImageFont.truetype(BOLD, 160)
parts = [(fps_opt, big, GREEN), ('  fps', small, WHITE)]
if not only_opt:
    parts = [(fps_stock, big, AMBER), ('  vs  ', mid, WHITE)] + parts
widths = [d.textlength(s, font=f) for s, f, _ in parts]
x = (W - sum(widths)) // 2
y = H - 210
for (s, f, c), w in zip(parts, widths):
    text(x, y, s, f, c, anchor='ls' if f is not big else 'ls')
    x += w
im.convert('RGB').save(out, quality=92, optimize=True)
print(f'wrote {out} ({os.path.getsize(out) // 1024} KB)')
