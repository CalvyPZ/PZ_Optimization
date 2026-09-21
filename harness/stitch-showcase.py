#!/usr/bin/env python3
"""Showcase video: stock (left) vs all optimizations (right), one file, under two minutes.

Segments (each rendered to its own AV1 HDR intermediate, then joined with 0.5 s cross-fades):
  2 boot + load of the 120 km/h runs (live BOOT / LOAD counters, the optimized side holds at its
    route start until the stock game has loaded), flowing into the 120 km/h highway drive
  3 the Options > Optimizations tab (menu recording, cropped to the panel; the scroll is sped up)
  4 spinning Rosewood route   5 120 km/h drive in heavy fog   6 120 km/h drive in a thunderstorm
  7 results card

Every pane is the centre 3840x2160 of the 5120x2160 capture scaled to 1920x1080, with the
in-game overlay region (top-left 1380x395 of the capture) pasted over its corner at 0.75x so the
numbers stay readable. Under each pane a big fps number (1 s average of the presented frames from
that run's pzopt-overlay.out, aligned to the video at the route start) and the whole-route results.
Output 3840x1800 (2.13:1, between 16:9 and 21:9 for YouTube), 60 fps, AV1 10-bit PQ / BT.2020
(NVENC), text colours are PQ code values. Audio: the game's menu theme (see stitch-sbs.sh for the
vgmstream extraction) throughout, replaced by the game's own rain loop during the thunderstorm.

Recordings: harness/showcase-record.sh <segment> <stock|opt>; timing: harness/showcase-times.py.
  harness/stitch-showcase.py [out.mp4]          (env RUNS_* to pick other run labels, WORK for the
                                                 intermediates dir, FAST=1 for a quick 1280-wide preview)
"""
import json, os, subprocess, sys, glob

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(REPO)
OUT = sys.argv[1] if len(sys.argv) > 1 else 'docs/media/showcase-stock-vs-all-optimizations.mp4'
WORK = os.environ.get('WORK', '/tmp/pzopt-showcase')
FAST = os.environ.get('FAST') == '1'
MUSIC = os.environ.get('MUSIC', os.path.expanduser('~/Zomboid/pzopt/menu-138.wav'))
# rain: ZomboidSound.bank stream 15042 "world_ext_rain_general_very_strong" (4 ch, 1:23), extracted like the theme:
#   vgmstream-cli -s 15042 -i -o ~/Zomboid/pzopt/rain-very-strong.wav <game>/media/sound/banks/Desktop/ZomboidSound.bank
# (stream indices from the FSB5 name table inside the .bank; the strings.bank has event names only)
RAIN = os.environ.get('RAIN', os.path.expanduser('~/Zomboid/pzopt/rain-very-strong.wav'))
os.makedirs(WORK, exist_ok=True)

RUNS = dict(drive=('show-drive120-stock-1', 'show-drive120-opt-1'), spin=('show-spin-stock-1', 'show-spin-opt-1'),
            fog=('show-fog120-stock-1', 'show-fog120-opt-1'), storm=('show-storm120-stock-1', 'show-storm120-opt-1'))
MENU_RUN = os.environ.get('RUNS_MENU', 'show-menu-opt-1')
for k in RUNS:
    e = os.environ.get('RUNS_' + k.upper())
    if e: RUNS[k] = tuple(e.split(','))

W, H = 3840, 1800
PW, PH, PY = 1920, 1080, 120          # panes
HUD_W, HUD_H, HUD_S = 1380, 395, 0.75  # in-game overlay region of the capture and its paste scale
FPS = 60
XF = 0.5                               # cross-fade between segments
DRIVE_SHOW, SPIN_SHOW, WX_SHOW = 22.0, 16.0, 16.0
MENU_A, MENU_CLICK, MENU_B, MENU_C, MENU_SPEED = 78.0, 80.0, 84.5, 120.5, 5.0   # menu recording: real-time part (tab click), sped-up scroll
FONT = 'Noto Sans'; MONO = 'Noto Sans Mono'
# PQ code values (~60 % = comfortable white), ASS wants &HAABBGGRR
C_WHITE, C_GREY, C_DIM, C_STOCK, C_OPT, C_BLUE = '&H00B8B4B4', '&H00908A8A', '&H00706A6A', '&H00408AB8', '&H0078B85C', '&H00C89A5C'
BG = '0x060608'

def sh(cmd):
    print('+', ' '.join(cmd if isinstance(cmd, list) else [cmd])[:400], file=sys.stderr)
    subprocess.run(cmd, check=True)

# ---------------------------------------------------------------- timing numbers
times_json = os.path.join(WORK, 'times.json')
labels = [l for pair in RUNS.values() for l in pair]
if not os.path.exists(times_json):
    with open(times_json, 'w') as f:
        subprocess.run(['python3', 'harness/showcase-times.py', *labels], stdout=f, check=True)
T = json.load(open(times_json))
for l in labels:
    assert T[l]['onset'] is not None, f'{l}: no route-start onset found'

# ---------------------------------------------------------------- ASS helpers
def ts(s):
    s = max(0.0, s); h = int(s // 3600); m = int(s % 3600 // 60); sec = s % 60
    return f'{h}:{m:02d}:{sec:05.2f}'

class Ass:
    def __init__(self):
        self.ev = []
    def add(self, t0, t1, style, text, layer=0):
        self.ev.append(f'Dialogue: {layer},{ts(t0)},{ts(t1)},{style},,0,0,0,,{text}')
    def write(self, path):
        head = f"""[Script Info]
ScriptType: v4.00+
PlayResX: {W}
PlayResY: {H}
WrapStyle: 2
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Title,{FONT},64,{C_WHITE},{C_WHITE},&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,0,0,5,0,0,0,1
Style: Big,{FONT},72,{C_WHITE},{C_WHITE},&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,0,0,5,0,0,0,1
Style: Label,{FONT},42,{C_WHITE},{C_WHITE},&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,0,0,5,0,0,0,1
Style: Num,{MONO},260,{C_WHITE},{C_WHITE},&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,0,0,5,0,0,0,1
Style: Counter,{MONO},72,{C_WHITE},{C_WHITE},&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,0,0,5,0,0,0,1
Style: Text,{FONT},44,{C_WHITE},{C_WHITE},&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,0,0,5,0,0,0,1
Style: Small,{FONT},34,{C_GREY},{C_GREY},&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,0,0,5,0,0,0,1
Style: Foot,{FONT},30,{C_GREY},{C_GREY},&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,5,0,0,0,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""
        open(path, 'w').write(head + '\n'.join(self.ev) + '\n')

def col(c): return f'{{\\c{c}}}'
FOOT = ('Project Zomboid 42.20  ·  native Linux, NVIDIA OpenGL  ·  5120x2160 at max zoom  ·  Ryzen 7 9800X3D, RTX 4090  ·  '
        'same save, same route, same machine  ·  big number = 1 s average from the overlay log, the in-game overlay shows the live frame time')

def header(ass, t0, t1, title, labels=True):
    ass.add(t0, t1, 'Title', f'{{\\an5\\pos({W//2},44)}}{title}')
    if labels:
        ass.add(t0, t1, 'Label', f'{{\\an5\\pos({PW//2},96)}}{col(C_STOCK)}STOCK{col(C_WHITE)}   every optimization off, frame cap removed')
        ass.add(t0, t1, 'Label', f'{{\\an5\\pos({PW + PW//2},96)}}{col(C_OPT)}OPTIMIZED{col(C_WHITE)}   all optimizations on, uncapped')
    ass.add(t0, t1, 'Foot', f'{{\\an5\\pos({W//2},{H-26})}}{FOOT}')

def fps_events(ass, run, seg_t, t0, t1, x0, color):
    """Big 1 s-average fps number under a pane; seg_t maps video seconds to segment seconds."""
    series = [(v, f) for v, f in T[run]['fps'] if v is not None]
    vals = {round(v * 4): f for v, f in series}
    last = None
    for v, f in series:
        b = round(v * 4)
        win = [vals[k] for k in range(b - 3, b + 1) if k in vals]
        if len(win) < 2: continue
        avg = int(round(sum(win) / len(win)))
        s = seg_t(v)
        if s is None or s < t0 or s >= t1: continue
        e = min(t1, s + 0.25)
        ass.add(s, e, 'Num', f'{{\\an9\\pos({x0 + 1120},1250)}}{col(color)}{avg}')
        last = avg
    ass.add(t0, t1, 'Big', f'{{\\an7\\pos({x0 + 1150},1355)}}{col(C_GREY)}fps')
    ass.add(t0, t1, 'Small', f'{{\\an7\\pos({x0 + 1150},1450)}}1 s average')

def results(ass, run, t0, t1, x0, color, what):
    r = T[run]
    ass.add(t0, t1, 'Small', f'{{\\an7\\pos({x0 + 1400},1265)}}{what}')
    ass.add(t0, t1, 'Text', f'{{\\an7\\pos({x0 + 1400},1318)}}{col(color)}{r["fps_mean"]:.0f} fps{col(C_WHITE)} mean')
    ass.add(t0, t1, 'Text', f'{{\\an7\\pos({x0 + 1400},1382)}}{col(color)}{r["p99_ms"]:.1f} ms{col(C_WHITE)} p99 frame time')
    ass.add(t0, t1, 'Text', f'{{\\an7\\pos({x0 + 1400},1446)}}{col(color)}{r["low1_fps"]:.0f} fps{col(C_WHITE)} 1 % low')
    ass.add(t0, t1, 'Text', f'{{\\an7\\pos({x0 + 1400},1510)}}{col(color)}{r["over33"]}{col(C_WHITE)} frames over 33 ms')

def counter(ass, t_start, total, x, y, color, t_end):
    """Live s.ss counter from t_start, frozen at total (coloured) afterwards."""
    step = 0.05
    n = int(total / step)
    for i in range(n):
        v = i * step
        ass.add(t_start + v, t_start + v + step, 'Counter', f'{{\\an9\\pos({x},{y})}}{v:5.2f} s')
    ass.add(t_start + total, t_end, 'Counter', f'{{\\an9\\pos({x},{y})}}{col(color)}{total:5.2f} s')

# ---------------------------------------------------------------- video building blocks
def pane(idx, extra=''):
    """Centre crop + HUD paste of input idx -> [pane{idx}]."""
    return (f'[{idx}:v]fps={FPS},format=yuv420p10le{extra},split[c{idx}a][c{idx}b];'
            f'[c{idx}a]crop=3840:2160:640:0,scale={PW}:{PH}:flags=lanczos,setsar=1[p{idx}];'
            f'[c{idx}b]crop={HUD_W}:{HUD_H}:0:0,scale={int(HUD_W*HUD_S)}:{int(HUD_H*HUD_S)}:flags=lanczos,setsar=1[h{idx}];'
            f'[p{idx}][h{idx}]overlay=0:0[pane{idx}]')

ENC_V = ['-c:v', 'av1_nvenc', '-preset', 'p7', '-tune', 'hq', '-rc', 'vbr', '-cq', '20', '-b:v', '0', '-maxrate', '120M', '-bufsize', '240M',
         '-pix_fmt', 'p010le', '-color_primaries', 'bt2020', '-color_trc', 'smpte2084', '-colorspace', 'bt2020nc', '-color_range', 'tv',
         '-movflags', '+faststart+write_colr', '-r', str(FPS)]
TAGS = 'setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv'
AUD = ['-c:a', 'aac', '-b:a', '192k', '-ar', '48000', '-ac', '2']

def compose(bg_len, ass_path, panes_filter, pane_labels):
    """bg + two panes + ASS -> [v] (the intermediates carry no sound; audio is built in the final join)."""
    f = f'color=c={BG}:s={W}x{H}:r={FPS}:d={bg_len:.3f},format=yuv420p10le[bg];{panes_filter};'
    if pane_labels:
        l, r = pane_labels
        f += f'[bg][{l}]overlay=0:{PY}:shortest=1[b1];[b1][{r}]overlay={PW}:{PY},drawbox=x={PW-2}:y={PY}:w=4:h={PH}:color=0x202026:t=fill[b2];'
    else:
        f += f'[bg][{panes_filter and "full"}]overlay=0:0:shortest=1[b2];'
    f += f'[b2]ass={ass_path}'
    if FAST: f += ',scale=1280:-2'
    f += f',{TAGS}[v]'
    return f

def encode(inputs, filt, length, out, has_audio=False):
    cmd = ['ffmpeg', '-hide_banner', '-v', 'error', '-y', *inputs, '-filter_complex', filt, '-map', '[v]']
    if has_audio: cmd += ['-map', '[a]', *AUD]
    cmd += [*ENC_V, '-t', f'{length:.3f}', out]
    sh(cmd)

def seg_path(n): return os.path.join(WORK, f'seg{n}.mp4')

# ---------------------------------------------------------------- 2 boot + load + 120 km/h drive
def seg_drive():
    s, o = RUNS['drive']; S, O = T[s], T[o]
    Ts = S['onset'] - S['t_log']; To = O['onset'] - O['t_log']; hold = Ts - To
    assert hold > 0
    L = Ts + DRIVE_SHOW
    ass = Ass()
    header(ass, 0, Ts, 'Launch  →  main menu  →  Continue  →  world ready        (both games launched together)')
    header(ass, Ts, L, '120 km/h highway drive   ·   1200 m   ·   max zoom')
    # live counters
    for run, x0, color, t_log in ((s, 0, C_STOCK, S['t_log']), (o, PW, C_OPT, O['t_log'])):
        R = T[run]
        ass.add(0, Ts, 'Text', f'{{\\an7\\pos({x0+120},1290)}}BOOT     launch  →  main menu')
        ass.add(0, Ts, 'Text', f'{{\\an7\\pos({x0+120},1400)}}LOAD     Continue  →  world ready')
        counter(ass, 0.0, R['boot'], x0 + PW - 140, 1280, color, Ts)
        counter(ass, R['t_cont'] - t_log, R['load'], x0 + PW - 140, 1390, color, Ts)
    ass.add(S['t_cont'] - S['t_log'], S['t_ready'] - S['t_log'], 'Small', f'{{\\an7\\pos(120,1510)}}loading...')
    ass.add(O['t_ready'] - O['t_log'], To, 'Small', f'{{\\an7\\pos({PW+120},1510)}}world ready  ·  settling')
    ass.add(To, Ts, 'Small', f'{{\\an7\\pos({PW+120},1510)}}{col(C_OPT)}world ready  ·  route ready  ·  waiting for the stock game to load...')
    ass.add(S['t_ready'] - S['t_log'], Ts, 'Small', f'{{\\an7\\pos(120,1510)}}world ready  ·  settling')
    # drive: big fps + results
    fps_events(ass, s, lambda v: v - S['t_log'], Ts, L, 0, C_STOCK)
    fps_events(ass, o, lambda v: (v - O['t_log'] + hold) if v >= O['onset'] else None, Ts, L, PW, C_OPT)
    results(ass, s, Ts, L, 0, C_STOCK, 'whole 1200 m route'); results(ass, o, Ts, L, PW, C_OPT, 'whole 1200 m route')
    p = os.path.join(WORK, 'seg2.ass'); ass.write(p)
    mask = 1.2  # the desktop shows before the game window appears: black until then
    panes = (pane(0, f",drawbox=x=0:y=0:w=iw:h=ih:color=black:t=fill:enable='lt(t,{mask})'") + ';' +
             f'[1:v]fps={FPS},format=yuv420p10le,drawbox=x=0:y=0:w=iw:h=ih:color=black:t=fill:enable=\'lt(t,{mask})\',split[o1][o2];'
             f'[o1]trim=0:{To:.3f},setpts=PTS-STARTPTS,tpad=stop_mode=clone:stop_duration={hold:.3f}[oA];'
             f'[o2]trim=start={To:.3f},setpts=PTS-STARTPTS[oB];[oA][oB]concat=n=2:v=1:a=0,split[c1a][c1b];'
             f'[c1a]crop=3840:2160:640:0,scale={PW}:{PH}:flags=lanczos,setsar=1[p1];'
             f'[c1b]crop={HUD_W}:{HUD_H}:0:0,scale={int(HUD_W*HUD_S)}:{int(HUD_H*HUD_S)}:flags=lanczos,setsar=1[h1];[p1][h1]overlay=0:0[pane1]')
    f = compose(L, p, panes, ('pane0', 'pane1'))
    encode(['-ss', f'{S["t_log"]:.3f}', '-t', f'{L:.3f}', '-i', S['video'],
            '-ss', f'{O["t_log"]:.3f}', '-t', f'{L:.3f}', '-i', O['video']], f, L, seg_path(2))
    return L, Ts

# ---------------------------------------------------------------- 3 the Optimizations tab
def seg_menu():
    v = sorted(glob.glob(f'harness/runs/{MENU_RUN}-*'))[-1] + '/recording.mp4'
    real = MENU_B - MENU_A; fast = (MENU_C - MENU_B) / MENU_SPEED; L = real + fast
    ass = Ass()
    header(ass, 0, L, 'Options  ▸  Optimizations   ·   every optimization is a tick box or a combo, saved to Zomboid/pzopt/options.ini, applied on the next launch', labels=False)
    ass.add(0, MENU_CLICK - MENU_A, 'Label', f'{{\\an5\\pos({W//2},96)}}Display tab: the frame cap has an "Uncapped" entry and the menus get their own cap   →   the Optimizations tab')
    ass.add(MENU_CLICK - MENU_A, L, 'Label', f'{{\\an5\\pos({W//2},96)}}master switch, chunk texture bakes and budgets, cutaways / lighting / weather, sprite buffers, the overlay, chunk streaming, boot and load     ({MENU_SPEED:.0f}x scroll)')
    p = os.path.join(WORK, 'seg3.ass'); ass.write(p)
    crop = 'crop=3690:1730:715:140,scale=3840:1800:flags=lanczos,setsar=1'
    f = (f'[0:v]fps={FPS},format=yuv420p10le,{crop}[m0];[1:v]fps={FPS},format=yuv420p10le,setpts=PTS/{MENU_SPEED},{crop}[m1];'
         f'[m0][m1]concat=n=2:v=1:a=0,pad={W}:{H}:0:0:color={BG}[full];'
         f'color=c={BG}:s={W}x{H}:r={FPS}:d={L:.3f},format=yuv420p10le[bg];[bg][full]overlay=0:0:shortest=1,'
         f'drawbox=x=0:y=0:w={W}:h={PY}:color={BG}:t=fill,drawbox=x=0:y={H-60}:w={W}:h=60:color={BG}:t=fill,ass={p}'
         + (',scale=1280:-2' if FAST else '') + f',{TAGS}[v]')
    encode(['-ss', f'{MENU_A}', '-t', f'{real}', '-i', v, '-ss', f'{MENU_B}', '-t', f'{MENU_C-MENU_B}', '-i', v], f, L, seg_path(3))
    return L

# ---------------------------------------------------------------- 4-6 route segments
def seg_route(n, key, show, title, what):
    s, o = RUNS[key]; S, O = T[s], T[o]
    pre = 1.0; L = pre + show
    ass = Ass(); header(ass, 0, L, title)
    fps_events(ass, s, lambda v: v - (S['onset'] - pre), 0, L, 0, C_STOCK)
    fps_events(ass, o, lambda v: v - (O['onset'] - pre), 0, L, PW, C_OPT)
    results(ass, s, 0, L, 0, C_STOCK, what); results(ass, o, 0, L, PW, C_OPT, what)
    p = os.path.join(WORK, f'seg{n}.ass'); ass.write(p)
    f = compose(L, p, pane(0) + ';' + pane(1), ('pane0', 'pane1'))
    encode(['-ss', f'{S["onset"]-pre:.3f}', '-t', f'{L:.3f}', '-i', S['video'],
            '-ss', f'{O["onset"]-pre:.3f}', '-t', f'{L:.3f}', '-i', O['video']], f, L, seg_path(n))
    return L

# ---------------------------------------------------------------- 7 results card
# background video for the results card: the maintainer's own capture (2026-09-20 19:10, AV1 HDR), from 39 s on;
# any mp4 works (SDR is mapped to PQ), RESULTS_BG= (empty) gives a plain black card
RESULTS_BG = os.environ.get('RESULTS_BG', os.path.expanduser('~/Videos/Project Zomboid/Video_2026-09-20_19-10-46.mp4'))
RESULTS_BG_SS = float(os.environ.get('RESULTS_BG_SS', '39'))   # seconds into that video to start from

def seg_results():
    """Results table, centred and compact, on a black frosted-glass panel over RESULTS_BG (or plain black)."""
    L = 13.0; ass = Ass()
    GX, GY, GW, GH = 720, 255, 2400, 1290            # the glass panel, centred
    def mean(k, side): return sum(T[RUNS[r][side]][k] for r in RUNS) / len(RUNS)
    def pct(a, b, lower_is_better):
        d = (b / a - 1) * 100
        good = d < 0 if lower_is_better else d > 0
        return f'{d:+.0f} %', (C_OPT if good else C_STOCK)
    rows = [('Boot   launch → main menu', f'{mean("boot",0):.1f} s', f'{mean("boot",1):.1f} s', pct(mean("boot",0), mean("boot",1), True)),
            ('Load   Continue → world ready', f'{mean("load",0):.1f} s', f'{mean("load",1):.1f} s', pct(mean("load",0), mean("load",1), True))]
    for key, name in (('drive', '120 km/h highway drive'), ('spin', 'Rosewood, spinning view'), ('fog', '120 km/h drive, heavy fog'), ('storm', '120 km/h drive, thunderstorm')):
        S, O = T[RUNS[key][0]], T[RUNS[key][1]]
        rows.append((f'{name}   ·   fps', f'{S["fps_mean"]:.0f} fps', f'{O["fps_mean"]:.0f} fps', pct(S['fps_mean'], O['fps_mean'], False)))
        rows.append((f'{name}   ·   p99 frame time', f'{S["p99_ms"]:.1f} ms', f'{O["p99_ms"]:.1f} ms', pct(S['p99_ms'], O['p99_ms'], True)))
    ass.add(0, L, 'Title', f'{{\\an5\\pos({GX + GW//2},{GY + 70})\\fs60}}Results   ·   same machine, same save, same routes, no frame cap')
    y, step = GY + 235, 96
    X0, X1, X2, X3 = GX + 90, GX + 1230, GX + 1640, GX + 2080
    ass.add(0, L, 'Label', f'{{\\an1\\pos({X1},{y-70})}}{col(C_STOCK)}STOCK')
    ass.add(0, L, 'Label', f'{{\\an1\\pos({X2},{y-70})}}{col(C_OPT)}OPTIMIZED')
    ass.add(0, L, 'Label', f'{{\\an1\\pos({X3},{y-70})}}CHANGE')
    for name, a, b, (c, cc) in rows:
        ass.add(0, L, 'Big', f'{{\\an1\\pos({X0},{y})\\fs42}}{name}')
        ass.add(0, L, 'Big', f'{{\\an1\\pos({X1},{y})\\fs46}}{col(C_STOCK)}{a}')
        ass.add(0, L, 'Big', f'{{\\an1\\pos({X2},{y})\\fs46}}{col(C_OPT)}{b}')
        ass.add(0, L, 'Big', f'{{\\an1\\pos({X3},{y})\\fs46}}{col(cc)}{c}')
        y += step
    ass.add(0, L, 'Small', f'{{\\an5\\pos({GX + GW//2},{GY + GH - 50})\\fs28}}boot and load: mean of the four launches   ·   fps: presented frames per second over the whole route   ·   p99: the frame time 99 % of frames stay under')
    p = os.path.join(WORK, 'seg7.ass'); ass.write(p)
    if RESULTS_BG:
        trc = subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=color_transfer', '-of', 'csv=p=0', RESULTS_BG],
                             capture_output=True, text=True).stdout.strip()
        tone = ('format=yuv420p10le' if trc == 'smpte2084' else
                'format=yuv420p,setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=tv,zscale=t=linear:npl=203,format=gbrpf32le,'
                'zscale=pin=bt709:tin=linear:p=bt2020:t=smpte2084:m=bt2020nc:r=tv:npl=203,format=yuv420p10le')
        bg = (f'[0:v]fps={FPS},scale={W}:{H}:force_original_aspect_ratio=increase,crop={W}:{H},setsar=1,{tone},split[bgA][bgB];'
              f'[bgB]crop={GW}:{GH}:{GX}:{GY},gblur=sigma=40:steps=3,drawbox=x=0:y=0:w=iw:h=ih:color=black@0.62:t=fill,'
              f'drawbox=x=0:y=0:w=iw:h=ih:color=0x505058@0.35:t=2[glass];[bgA][glass]overlay={GX}:{GY}')
        inputs = ['-stream_loop', '-1', '-ss', f'{RESULTS_BG_SS}', '-t', f'{L + 1}', '-i', RESULTS_BG]
    else:
        bg = (f'color=c={BG}:s={W}x{H}:r={FPS}:d={L},format=yuv420p10le,'
              f'drawbox=x={GX}:y={GY}:w={GW}:h={GH}:color=0x101014:t=fill,drawbox=x={GX}:y={GY}:w={GW}:h={GH}:color=0x303038:t=2')
        inputs = []
    f = bg + f',ass={p}' + (',scale=1280:-2' if FAST else '') + f',{TAGS}[v]'
    encode(inputs, f, L, seg_path(7)); return L

# ---------------------------------------------------------------- build
lens_json = os.path.join(WORK, 'lens.json')
SEGS = [2, 3, 4, 5, 6, 7]   # no title card: the video opens on the two launches
ONLY = [int(x) for x in os.environ.get('ONLY', '').split(',') if x]
if (os.environ.get('SKIP_SEGS') == '1' or ONLY) and os.path.exists(lens_json):   # reuse the intermediates (ONLY=3,7 redoes those)
    j = json.load(open(lens_json)); lens = {int(k): v for k, v in j['lens'].items()}; t_route = j['t_route']
    if 2 in ONLY: lens[2], t_route = seg_drive()
    if 3 in ONLY: lens[3] = seg_menu()
    if 7 in ONLY: lens[7] = seg_results()
    json.dump(dict(lens=lens, t_route=t_route), open(lens_json, 'w'))
else:
    lens = {}
    lens[2], t_route = seg_drive()
    lens[3] = seg_menu()
    lens[4] = seg_route(4, 'spin', SPIN_SHOW, 'Rosewood   ·   teleport route south, player facing spinning 90°/s   ·   max zoom   ·   the game-thread route', 'whole 25 s route')
    lens[5] = seg_route(5, 'fog', WX_SHOW, '120 km/h highway drive in heavy fog   ·   1200 m   ·   max zoom', 'whole 1200 m route')
    lens[6] = seg_route(6, 'storm', WX_SHOW, '120 km/h highway drive in a thunderstorm   ·   lightning every 6 s   ·   max zoom', 'whole 1200 m route')
    lens[7] = seg_results()
    json.dump(dict(lens=lens, t_route=t_route), open(lens_json, 'w'))

# cross-fade chain (video); audio = the menu theme everywhere except the thunderstorm, which gets the
# game's own rain loop (world_ext_rain_general_very_strong, 4 ch downmixed) instead
inputs = []
for i in SEGS: inputs += ['-i', seg_path(i)]
IM, IR = len(SEGS), len(SEGS) + 1
inputs += ['-stream_loop', '-1', '-i', MUSIC, '-stream_loop', '-1', '-i', RAIN]
starts = {SEGS[0]: 0.0}
for k in range(1, len(SEGS)): starts[SEGS[k]] = starts[SEGS[k-1]] + lens[SEGS[k-1]] - XF
total = starts[SEGS[-1]] + lens[SEGS[-1]]
vf = ''
prev_v = '[0:v]'
for k in range(1, len(SEGS)):
    vo = f'[v{k}]' if k < len(SEGS) - 1 else '[vx]'
    vf += f'{prev_v}[{k}:v]xfade=transition=fade:duration={XF}:offset={starts[SEGS[k]]:.3f}{vo};'
    prev_v = vo
r0, r1 = starts[6] - XF, starts[7] + XF          # rain window = the storm segment incl. both cross-fades
env = (f"if(lt(t,{r0:.2f}),1, if(lt(t,{r0+1:.2f}),{r0+1:.2f}-t, if(lt(t,{r1-1:.2f}),0, if(lt(t,{r1:.2f}),t-{r1-1:.2f},1))))")
mf = (f'[{IM}:a]atrim=0:{total:.3f},asetpts=PTS-STARTPTS,loudnorm=I=-16:TP=-1.5:LRA=11,'
      f"volume='{env}':eval=frame,afade=t=in:d=1.0,afade=t=out:st={total-4:.3f}:d=4[mus];"
      f'[{IR}:a]pan=stereo|c0=0.5*c0+0.5*c2|c1=0.5*c1+0.5*c3,atrim=0:{r1-r0:.3f},asetpts=PTS-STARTPTS,loudnorm=I=-16:TP=-1.5:LRA=9,'
      f'afade=t=in:d=1.0,afade=t=out:st={r1-r0-1.0:.3f}:d=1.0,adelay={int(r0*1000)}|{int(r0*1000)}[rain];'
      f'[mus][rain]amix=inputs=2:duration=longest:normalize=0,atrim=0:{total:.3f}[a];')
filt = vf + mf + f'[vx]format=yuv420p10le,{TAGS}[v]'
os.makedirs(os.path.dirname(OUT), exist_ok=True)
sh(['ffmpeg', '-hide_banner', '-v', 'error', '-y', *inputs, '-filter_complex', filt, '-map', '[v]', '-map', '[a]', *AUD, *ENC_V, '-t', f'{total:.3f}', OUT])
# loudness: measure the mix, then apply linear loudnorm to -16 LUFS with the video stream copied
tmp = OUT[:-4] + '.joined.mp4'; os.replace(OUT, tmp)
m = subprocess.run(['ffmpeg', '-hide_banner', '-i', tmp, '-vn', '-af', 'loudnorm=I=-16:TP=-1.5:LRA=11:print_format=json', '-f', 'null', '-'],
                   capture_output=True, text=True).stderr
j = json.loads(m[m.rindex('{'):m.rindex('}') + 1])
ln = (f"loudnorm=I=-16:TP=-1.5:LRA=11:measured_I={j['input_i']}:measured_TP={j['input_tp']}:measured_LRA={j['input_lra']}"
      f":measured_thresh={j['input_thresh']}:offset={j['target_offset']}:linear=true")
sh(['ffmpeg', '-hide_banner', '-v', 'error', '-y', '-i', tmp, '-map', '0:v', '-map', '0:a', '-c:v', 'copy', '-af', ln, *AUD,
    '-movflags', '+faststart', OUT]); os.remove(tmp)
poster_t = starts[2] + t_route + 12
sh(['ffmpeg', '-hide_banner', '-v', 'error', '-y', '-ss', f'{poster_t:.2f}', '-i', OUT, '-frames:v', '1',
    '-vf', 'zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p',
    '-q:v', '2', OUT[:-4] + '.jpg'])
# the YouTube thumbnail is harness/showcase-thumbnail.py (a frame of the results-card capture, not the poster)
print(f'segments: {lens}\nstarts: {starts}\ntotal {total:.1f} s -> {OUT}')
