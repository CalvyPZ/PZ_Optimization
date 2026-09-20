#!/usr/bin/env python3
"""Animated Workshop thumbnail: the last beat of the showcase (the maintainer's own capture that
backs the results card), tone-mapped, from 25 s on. Default: a square crop centred on the
character (the game camera follows them, so the crop is fixed and the overlay in the top-left
corner stays outside it), "PZ Optimized" on a dark band at the top. GIF_END= turns on the older
zoom-and-pan variant (hold, ease from GIF_START to GIF_END, hold).

  harness/showcase-thumbnail-gif.py [out.gif]
  env: THUMB_SRC (video), GIF_T0 (default 25), GIF_LEN seconds (default 4.6), GIF_SIZE (default 448),
       GIF_FPS (default 6), GIF_START x:y:w (square crop, default 1750:230:1620 = the character centred),
       GIF_END x:y:w (zoom target; unset = static), GIF_HOLD1 / GIF_ZOOM seconds for the zoom variant
       (default 1.6 / 1.4, the rest of GIF_LEN is the end hold), GIF_COLORS (default 96),
       GIF_DITHER (default none), GIF_MEDIAN (median filter size, 0 = off, default 3),
       GIF_LOSSY (gifsicle --lossy level, 0 = off, default 80; binary from GIFSICLE or PATH),
       GIF_LABEL (default "PZ Optimized"), GIF_LABEL_POS top|bottom (default top)

The Steam preview limit is 1 MB; the script prints the size and fails above it. The asphalt
noise is what costs: plain LZW at 512 px is ~145 KB a frame whatever the palette, so a moving
clip needs the median filter (kills the grain, keeps edges), gifsicle's lossy LZW and a modest
size / frame count. ImageMagick's fuzz transparency was tried and ghosts badly on a panning
camera; dither triples the size. Both stay off. The defaults (448 px, 6 fps, 4.6 s = 28 frames,
96 colours, median 3, lossy 80) land at ~985 KB. The in-game uploader only takes preview.png: a GIF
preview goes up with steamcmd (docs/workshop.md).
"""
import os, subprocess, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(REPO, 'docs/workshop/images/00-showcase-thumbnail.gif')
src = os.environ.get('THUMB_SRC', os.path.expanduser('~/Videos/Project Zomboid/Video_2026-09-20_19-10-46.mp4'))
t0 = float(os.environ.get('GIF_T0', '25'))
total = float(os.environ.get('GIF_LEN', '4.6'))
S = int(os.environ.get('GIF_SIZE', '448'))
fps = int(os.environ.get('GIF_FPS', '6'))
hold1, zoom = float(os.environ.get('GIF_HOLD1', '1.6')), float(os.environ.get('GIF_ZOOM', '1.4'))
x0, y0, w0 = (int(v) for v in os.environ.get('GIF_START', '1750:230:1620').split(':'))
end = os.environ.get('GIF_END')
x1, y1, w1 = (int(v) for v in end.split(':')) if end else (x0, y0, w0)
colors = int(os.environ.get('GIF_COLORS', '96'))
median = int(os.environ.get('GIF_MEDIAN', '3'))
lossy = int(os.environ.get('GIF_LOSSY', '80'))
gifsicle = os.environ.get('GIFSICLE', 'gifsicle')
dither = os.environ.get('GIF_DITHER', 'none')          # none: smallest and no crawling on the game noise
label = os.environ.get('GIF_LABEL', 'PZ Optimized')
label_top = os.environ.get('GIF_LABEL_POS', 'top') != 'bottom'
BLACK = '/usr/share/fonts/noto/NotoSans-Black.ttf'

W, H = 5120, 2160
n = int(round(total * fps))
# the whole clip decoded once, tone-mapped to SDR at source resolution, streamed frame by frame
cmd = ['ffmpeg', '-v', 'error', '-ss', f'{t0}', '-t', f'{total + 0.2}', '-i', src,
       '-vf', f'fps={fps},zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,'
              f'zscale=p=bt709:t=bt709:m=bt709,format=rgb24', '-f', 'rawvideo', '-']
proc = subprocess.Popen(cmd, stdout=subprocess.PIPE)

def ease(u):                       # smoothstep, so the zoom starts and ends gently
    return u * u * (3 - 2 * u)

bh = int(S * 0.19)
band = Image.new('RGBA', (S, S), (0, 0, 0, 0))
ImageDraw.Draw(band).rectangle((0, 0, S, bh) if label_top else (0, S - bh, S, S), fill=(0, 0, 0, 170))
band = band.filter(ImageFilter.GaussianBlur(S // 40))
font = ImageFont.truetype(BLACK, int(S * 0.115))
ly = int(S * 0.095) if label_top else S - int(S * 0.095)

frames = []
for i in range(n):
    raw = proc.stdout.read(W * H * 3)
    if len(raw) < W * H * 3:
        break
    t = i / fps
    u = 0.0 if (not end or t < hold1) else 1.0 if t >= hold1 + zoom else ease((t - hold1) / zoom)
    x, y, w = (round(a + (b - a) * u) for a, b in ((x0, x1), (y0, y1), (w0, w1)))
    x, y = max(0, min(W - w, x)), max(0, min(H - w, y))
    arr = np.frombuffer(raw, dtype=np.uint8).reshape(H, W, 3)[y:y + w, x:x + w]
    im = Image.fromarray(np.ascontiguousarray(arr)).resize((S, S), Image.LANCZOS)
    if median:
        im = im.filter(ImageFilter.MedianFilter(median))
    im = im.convert('RGBA')
    im = Image.alpha_composite(im, band)
    d = ImageDraw.Draw(im)
    d.text((S // 2 + 3, ly + 3), label, font=font, fill=(0, 0, 0, 220), anchor='mm', stroke_width=S // 120, stroke_fill=(0, 0, 0, 220))
    d.text((S // 2, ly), label, font=font, fill=(245, 245, 245), anchor='mm', stroke_width=S // 120, stroke_fill=(0, 0, 0, 255))
    frames.append(im.convert('RGB'))
proc.kill(); proc.wait()
assert len(frames) >= n - 1, f'only {len(frames)} frames decoded'

# one global palette, no dither by default (the game noise would otherwise crawl and triple the size), loop
work = '/tmp/pzopt-thumb-gif'
os.makedirs(work, exist_ok=True)
for f in os.listdir(work):
    os.remove(os.path.join(work, f))
for i, f in enumerate(frames):
    f.save(f'{work}/f{i:03d}.png')
subprocess.run(['ffmpeg', '-v', 'error', '-y', '-framerate', str(fps), '-i', f'{work}/f%03d.png',
                '-filter_complex', f'split[a][b];[a]palettegen=max_colors={colors}:stats_mode=diff[p];[b][p]paletteuse=dither={dither}:diff_mode=rectangle',
                '-loop', '0', out], check=True)
raw_size = os.path.getsize(out)
if lossy:
    subprocess.run([gifsicle, '-O3', f'--lossy={lossy}', out, '-o', out], check=True)
size = os.path.getsize(out)
print(f'lzw {raw_size // 1024} KB -> lossy {lossy} {size // 1024} KB')
print(f'wrote {out}: {len(frames)} frames, {S}x{S}, {fps} fps, {size // 1024} KB')
if size > 1024000:
    sys.exit(f'{size} bytes is over the 1 MB Steam preview limit: lower GIF_SIZE / GIF_COLORS / GIF_FPS')
