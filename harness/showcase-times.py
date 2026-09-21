#!/usr/bin/env python3
"""Timing and result numbers for the showcase stitch (harness/stitch-showcase.sh).

For every run given (label prefix, latest run dir), prints one JSON object with
  video_t0     video seconds at which the game window appears (desktop before it)
  onset        video seconds of the route start (car / camera motion onset, found by frame
               differencing around the scheduled route_start_epoch_ms)
  offset       video_t = (epoch_ms - route_start_epoch_ms)/1000 + onset  ->  offset = onset - route_start
  boot         seconds from the first log line to the Continue press (launch -> main menu)
  load         seconds from Continue to "world ready"
  t_log, t_cont, t_ready   the same three points in video seconds
  route_s      route length from console.txt
  fps_mean, p99_ms, p999_ms, low1_fps   over the route window (pzopt-overlay.out)
  fps          [[video_t, fps], ...] presented-frame rate in 0.25 s bins over the whole log
The gpu-screen-recorder capture starts ~1 s after run.opts launch_epoch and drifts ~6 ms/s
against the wall clock, so the offset is taken at the route start, where it matters.

  harness/showcase-times.py show-drive120-stock-1 show-drive120-opt-1 ... > /tmp/showcase-times.json
"""
import glob, json, os, re, subprocess, sys
import numpy as np

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

def run_dir(label):
    return sorted(glob.glob(os.path.join(REPO, 'harness/runs', label + '-*')))[-1]

def kv(path):
    out = {}
    for l in open(path):
        if '=' in l:
            k, v = l.rstrip('\n').split('=', 1); out[k] = v
    return out

def frames(video, start, dur, fps=20, w=192, h=81):
    cmd = ['ffmpeg', '-v', 'error', '-ss', f'{start:.3f}', '-t', f'{dur:.3f}', '-i', video,
           '-vf', f'fps={fps},scale={w}:{h}:flags=area,format=gray', '-f', 'rawvideo', '-']
    raw = subprocess.run(cmd, capture_output=True, check=True).stdout
    n = len(raw) // (w * h)
    return np.frombuffer(raw[:n * w * h], dtype=np.uint8).reshape(n, h, w).astype(np.float32)

def onset(video, guess, span=2.5, fps=20):
    """Route start: the first frame that differs from its predecessor after a still second, on
    48x20 thumbnails (rain and fog average out). Drive runs show one isolated spike there before
    the car has any speed; the teleport routes start moving right away."""
    f = frames(video, max(0.0, guess - span), 2 * span, fps, 48, 20)
    d = np.abs(np.diff(f, axis=0)).mean(axis=(1, 2))
    quiet = 0
    for i, x in enumerate(d):
        if x > 1.0 and quiet >= fps // 2:
            return max(0.0, guess - span) + (i + 1) / fps, float(x), quiet / fps
        quiet = quiet + 1 if x < 0.6 else 0
    return None, float(d.max()), 0.0

def window_appears(video, fps=10):
    f = frames(video, 0.0, 8.0, fps)
    d = np.abs(np.diff(f, axis=0)).mean(axis=(1, 2))
    i = int(np.argmax(d))
    return (i + 1) / fps

def main():
    out = {}
    for label in sys.argv[1:]:
        d = run_dir(label)
        opts = kv(os.path.join(d, 'run.opts'))
        sched = kv(os.path.join(d, 'pzopt-schedule.out'))
        launch = int(opts['launch_epoch'])
        rs = int(sched['route_start_epoch_ms'])
        ready = int(sched['world_ready_epoch_ms'])
        video = os.path.join(d, 'recording.mp4')
        t_log = t_cont = None
        for l in open(os.path.join(d, 'pzopt-loadtrace.out')):
            ep = int(l.split('\t', 1)[0])
            if t_log is None: t_log = ep
            if 'continuing latest save' in l and t_cont is None: t_cont = ep
        con = open(os.path.join(d, 'console.txt'), errors='replace').read()
        m = re.search(r'route (?:complete|done) in ([0-9.]+)s', con)
        route_s = float(m.group(1)) if m else None
        guess = rs / 1000 - launch - 1.0
        on, peak, base = onset(video, guess)
        off = (on - rs / 1000) if on is not None else None
        # overlay log: fps per 0.25 s bin over the whole log, and route-window stats
        ov = np.genfromtxt(os.path.join(d, 'pzopt-overlay.out'), delimiter=',', names=True)
        ep = ov['epoch_ms'] / 1000.0; ft = ov['frametime']
        bins = {}
        for e, t in zip(ep, ft):
            b = int(e * 4)
            bins.setdefault(b, []).append(t)
        series = []
        for b in sorted(bins):
            v = bins[b]
            series.append([round(b / 4 + off, 3) if off is not None else None, round(len(v) / 0.25, 1)])
        end = rs / 1000 + (route_s or 0)
        sel = (ep >= rs / 1000) & (ep <= end)
        w = ft[sel]
        res = {}
        if len(w):
            s = np.sort(w)
            res = dict(fps_mean=round(len(w) / (route_s or 1), 1), p99_ms=round(float(np.percentile(w, 99)), 1),
                       p999_ms=round(float(np.percentile(w, 99.9)), 1), max_ms=round(float(w.max()), 1),
                       low1_fps=round(1000.0 / float(s[-max(1, len(s) // 100):].mean()), 1),
                       over33=int((w > 33.3).sum()))
        out[label] = dict(dir=d, video=video, video_t0=window_appears(video), onset=on, onset_peak=peak, onset_base=base,
                          offset=off, launch_epoch=launch, route_start=rs / 1000, route_s=route_s,
                          boot=round((t_cont - t_log) / 1000, 2), load=round((ready - t_cont) / 1000, 2),
                          t_log=round(t_log / 1000 + off, 2) if off is not None else None,
                          t_cont=round(t_cont / 1000 + off, 2) if off is not None else None,
                          t_ready=round(ready / 1000 + off, 2) if off is not None else None,
                          fps=series, **res)
    json.dump(out, sys.stdout, indent=1)

if __name__ == '__main__':
    main()
