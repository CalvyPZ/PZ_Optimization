#!/usr/bin/env python3
"""Before / after GIF for GitHub issue #4 (windows drawn through closed curtains) from two
harness screenshot runs at the same camera position (the Rosewood living room at 8147,11507,
curtains closed by the harness `close_curtains=true` flag, `--shot-at 3`, zoom 1):

  harness/issue4-gif.py [before-run-dir] [after-run-dir] [out.gif]

Defaults: the newest `i4-repro-*` (curtainDepthNudgePct=0, no curtain layer rule: the glass on
top of the curtain) and `i4-fix2-*` (defaults: curtain in front) under harness/runs, written to
docs/media/issue4-curtains-before-after.gif. Two frames of 2 s each with a label band, the wall
with the three north windows and the next room's two, 2x nearest-neighbour (pixel art).
env: GIF_CROP x:y:w:h in source pixels relative to the screen centre (the player), GIF_SCALE.
"""
import glob, os, sys
from PIL import Image, ImageDraw, ImageFont

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RUNS = os.path.join(REPO, 'harness/runs')


def newest(prefix):
    dirs = sorted(glob.glob(os.path.join(RUNS, prefix + '-*')))
    if not dirs:
        sys.exit(f'no {prefix}-* run under harness/runs')
    return dirs[-1]


before = sys.argv[1] if len(sys.argv) > 1 else newest('i4-repro')
after = sys.argv[2] if len(sys.argv) > 2 else newest('i4-fix2')
out = sys.argv[3] if len(sys.argv) > 3 else os.path.join(REPO, 'docs/media/issue4-curtains-before-after.gif')
cx0, cy0, cw, ch = (int(v) for v in os.environ.get('GIF_CROP', '-340:-300:780:420').split(':'))
scale = float(os.environ.get('GIF_SCALE', '1'))
FONT = '/usr/share/fonts/noto/NotoSans-Black.ttf'
BAND = 44

frames = []
for run, label, colour in ((before, 'Issue #4: window drawn through the closed curtain', (255, 96, 96)),
                           (after, 'Fixed: curtain in front (default settings)', (120, 230, 120))):
    im = Image.open(os.path.join(run, 'shot-game.png')).convert('RGB')
    w, h = im.size
    cx, cy = w // 2, h // 2
    crop = im.crop((cx + cx0, cy + cy0, cx + cx0 + cw, cy + cy0 + ch))
    if scale != 1:
        crop = crop.resize((int(cw * scale), int(ch * scale)), Image.NEAREST)
    frame = Image.new('RGB', (crop.width, crop.height + BAND), (16, 16, 16))
    frame.paste(crop, (0, BAND))
    d = ImageDraw.Draw(frame)
    d.text((12, 8), label, fill=colour, font=ImageFont.truetype(FONT, 24))
    frames.append(frame.quantize(colors=128, method=Image.MEDIANCUT, dither=Image.NONE))

os.makedirs(os.path.dirname(out), exist_ok=True)
frames[0].save(out, save_all=True, append_images=frames[1:], duration=[2200, 2200], loop=0, optimize=True)
print(f'{out}: {os.path.getsize(out):,} bytes, {frames[0].width}x{frames[0].height}, {len(frames)} frames from\n  {before}\n  {after}')
