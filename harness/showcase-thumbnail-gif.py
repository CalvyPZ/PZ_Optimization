#!/usr/bin/env python3
"""Animated Workshop thumbnail: the last beat of the showcase (the maintainer's own capture that
backs the results card), tone-mapped, from 25 s on: a square view of the fight, then a zoom and
pan into the performance overlay in the top-left corner, held there while its numbers tick.
"PZ Optimized" sits on a dark band at the bottom the whole time.

  harness/showcase-thumbnail-gif.py [out.gif]
  env: THUMB_SRC (video), GIF_T0 (default 25), GIF_SIZE (default 512), GIF_FPS (default 8),
       GIF_HOLD1 / GIF_ZOOM / GIF_HOLD2 seconds (default 1.6 / 1.4 / 2.4),
       GIF_START x:y:w (square crop at the start, default 1520:0:2160 = the pair, full height),
       GIF_END   x:y:w (square crop at the end, default 0:0:760 = the overlay, text ~17 px),
       GIF_COLORS (default 112), GIF_DITHER (default none), GIF_LABEL (default "PZ Optimized")

The Steam preview limit is 1 MB; the script prints the size and fails above it (512 px / 8 fps /
112 colours / no dither is ~730 KB; 480 px with 128 colours came out at 4 MB, so the palette
size matters more than the pixel count). Note the in-game uploader only takes preview.png: a GIF preview goes
up with steamcmd (docs/workshop.md).
"""
import os, subprocess, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(REPO, 'docs/workshop/images/00-showcase-thumbnail.gif')
src = os.environ.get('THUMB_SRC', os.path.expanduser('~/Videos/Project Zomboid/Video_2026-09-20_19-10-46.mp4'))
t0 = float(os.environ.get('GIF_T0', '25'))
S = int(os.environ.get('GIF_SIZE', '512'))
fps = int(os.environ.get('GIF_FPS', '8'))
hold1, zoom, hold2 = (float(os.environ.get(k, d)) for k, d in (('GIF_HOLD1', '1.6'), ('GIF_ZOOM', '1.4'), ('GIF_HOLD2', '2.4')))
x0, y0, w0 = (int(v) for v in os.environ.get('GIF_START', '1520:0:2160').split(':'))
x1, y1, w1 = (int(v) for v in os.environ.get('GIF_END', '0:0:760').split(':'))
colors = int(os.environ.get('GIF_COLORS', '112'))
dither = os.environ.get('GIF_DITHER', 'none')          # none: smallest and no crawling on the game noise
label = os.environ.get('GIF_LABEL', 'PZ Optimized')
BLACK = '/usr/share/fonts/noto/NotoSans-Black.ttf'

W, H = 5120, 2160
total = hold1 + zoom + hold2
n = int(round(total * fps))
# the whole clip decoded once, tone-mapped to SDR at source resolution, streamed frame by frame
cmd = ['ffmpeg', '-v', 'error', '-ss', f'{t0}', '-t', f'{total + 0.2}', '-i', src,
       '-vf', f'fps={fps},zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,'
              f'zscale=p=bt709:t=bt709:m=bt709,format=rgb24', '-f', 'rawvideo', '-']
proc = subprocess.Popen(cmd, stdout=subprocess.PIPE)

def ease(u):                       # smoothstep, so the zoom starts and ends gently
    return u * u * (3 - 2 * u)

band = Image.new('RGBA', (S, S), (0, 0, 0, 0))
ImageDraw.Draw(band).rectangle((0, S - int(S * 0.19), S, S), fill=(0, 0, 0, 170))
band = band.filter(ImageFilter.GaussianBlur(S // 40))
font = ImageFont.truetype(BLACK, int(S * 0.115))

frames = []
for i in range(n):
    raw = proc.stdout.read(W * H * 3)
    if len(raw) < W * H * 3:
        break
    t = i / fps
    u = 0.0 if t < hold1 else 1.0 if t >= hold1 + zoom else ease((t - hold1) / zoom)
    x, y, w = (round(a + (b - a) * u) for a, b in ((x0, x1), (y0, y1), (w0, w1)))
    x, y = max(0, min(W - w, x)), max(0, min(H - w, y))
    arr = np.frombuffer(raw, dtype=np.uint8).reshape(H, W, 3)[y:y + w, x:x + w]
    im = Image.fromarray(np.ascontiguousarray(arr)).resize((S, S), Image.LANCZOS).convert('RGBA')
    im = Image.alpha_composite(im, band)
    d = ImageDraw.Draw(im)
    d.text((S // 2 + 3, S - int(S * 0.095) + 3), label, font=font, fill=(0, 0, 0, 220), anchor='mm', stroke_width=S // 120, stroke_fill=(0, 0, 0, 220))
    d.text((S // 2, S - int(S * 0.095)), label, font=font, fill=(245, 245, 245), anchor='mm', stroke_width=S // 120, stroke_fill=(0, 0, 0, 255))
    frames.append(im.convert('RGB'))
proc.kill(); proc.wait()
assert len(frames) >= n - 1, f'only {len(frames)} frames decoded'

# one global palette, no dither by default (the game noise would otherwise crawl and triple the size), loop
work = '/tmp/pzopt-thumb-gif'
os.makedirs(work, exist_ok=True)
for i, f in enumerate(frames):
    f.save(f'{work}/f{i:03d}.png')
subprocess.run(['ffmpeg', '-v', 'error', '-y', '-framerate', str(fps), '-i', f'{work}/f%03d.png',
                '-filter_complex', f'split[a][b];[a]palettegen=max_colors={colors}:stats_mode=diff[p];[b][p]paletteuse=dither={dither}:diff_mode=rectangle',
                '-loop', '0', out], check=True)
size = os.path.getsize(out)
print(f'wrote {out}: {len(frames)} frames, {S}x{S}, {fps} fps, {size // 1024} KB')
if size > 1024000:
    sys.exit(f'{size} bytes is over the 1 MB Steam preview limit: lower GIF_SIZE / GIF_COLORS / GIF_FPS')
