#!/usr/bin/env python3
"""Flame graph of the game thread over a run's route window, from the overlay's stack log.

  harness/flamegraph.py <run-dir> [--out file.svg] [--folded file.txt] [--all] [--min-pct 0.05]
                        [--width 1800] [--title TEXT]

Reads pzopt-stacks.out (pzopt.GameThreadProfile: 'f <id> <Class.method>' frame ids, 't <epoch_ms>
<samples>' per second, '<id>;<id>;... <count>' folded stacks root->leaf, game frames only, from
GameWindow.frameStep up), keeps the seconds inside the route window (pzopt-bench.out
route_start/end_epoch_ms; --all keeps every second, e.g. boot and load), and writes a
self-contained SVG: root at the bottom, callees above, width = share of the samples, siblings
biggest first from the left; hover a box for its full name and share, click to zoom, the
search box highlights matching frames. Colours: update green, render blue, lighting amber,
pzopt frames magenta, the rest grey, shaded per name. --folded also writes the merged stacks in
the classic "a;b;c count" format for other flame graph tools.

The same stacks drawn live in the overlay (overlayFlame) cover the last 5 s; this is the whole route.
"""
import argparse
import html
import sys
from pathlib import Path


def read_stacks(run, window):
    p = Path(run) / "pzopt-stacks.out"
    if not p.exists():
        sys.exit(f"{p} not found (needs a run with the overlay log, i.e. any harness run with the overrides on)")
    frames = {}
    stacks = {}
    hz = None
    keep = window is None
    samples = seconds = 0
    with p.open(errors="replace") as f:
        for line in f:
            if line.startswith("#"):
                import re
                m = re.search(r"(\d+) Hz", line)
                hz = int(m.group(1)) if m else None
                continue
            if line.startswith("f "):
                _, fid, name = line.rstrip("\n").split(" ", 2)
                frames[fid] = name
            elif line.startswith("t "):
                _, epoch, n = line.split()
                epoch = int(epoch)
                keep = window is None or (window[0] <= epoch and epoch - 1000 <= window[1])
                if keep:
                    samples += int(n)
                    seconds += 1
            elif keep:
                ids, _, count = line.rstrip("\n").rpartition(" ")
                if not count.isdigit():
                    continue
                key = tuple(frames.get(i, f"#{i}") for i in ids.split(";"))
                stacks[key] = stacks.get(key, 0) + int(count)
    return frames, stacks, samples, seconds, hz


def route_window(run):
    bench = Path(run) / "pzopt-bench.out"
    if not bench.exists():
        return None
    kv = dict(l.split("=", 1) for l in bench.read_text().splitlines() if "=" in l)
    if "route_start_epoch_ms" in kv and "route_end_epoch_ms" in kv:
        return int(kv["route_start_epoch_ms"]), int(kv["route_end_epoch_ms"])
    return None


class Node:
    __slots__ = ("name", "count", "kids")

    def __init__(self, name):
        self.name, self.count, self.kids = name, 0, {}


def build_tree(stacks, total):
    root = Node("game thread")
    root.count = total
    for path, c in stacks.items():
        n = root
        for frame in path:
            k = n.kids.get(frame)
            if k is None:
                k = n.kids[frame] = Node(frame)
            k.count += c
            n = k
    return root


PHASE = {"GameWindow.logic": (110, 220, 130), "GameWindow.renderInternal": (120, 170, 255), "LightingThread.update": (255, 210, 100)}
GREY = (180, 180, 180)
PZOPT = (255, 140, 255)


def color(name, inherited):
    base = PHASE.get(name, PZOPT if name.startswith("pzopt.") else inherited)
    h = 0
    for ch in name:
        h = (h * 31 + ord(ch)) & 0xffffffff
    shade = 0.72 + 0.28 * ((h & 0xff) / 255)
    return base, tuple(int(v * shade) for v in base)


def layout(root, min_share):
    """Boxes (x0, x1 in 0..1, depth, name, count, rgb), root row 0."""
    boxes = []
    total = max(1, root.count)

    def place(n, x0, x1, depth, inherited):
        if (x1 - x0) < min_share:
            return
        base, rgb = color(n.name, inherited)
        boxes.append((x0, x1, depth, n.name, n.count, rgb))
        x = x0
        for k in sorted(n.kids.values(), key=lambda k: -k.count):
            w = (x1 - x0) * k.count / max(1, n.count)
            place(k, x, x + w, depth + 1, base)
            x += w
    place(root, 0.0, 1.0, 0, GREY)
    return boxes


def svg(boxes, total, width, title, hz, seconds):
    row_h = 16
    depth = max(b[2] for b in boxes) + 1
    top = 60
    height = top + depth * row_h + 30
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}" font-family="monospace" font-size="12">',
           '<style>.b:hover rect{stroke:#000;stroke-width:1} text{pointer-events:none} .hl rect{fill:#ff2020 !important}</style>',
           f'<rect width="100%" height="100%" fill="#1b1b1b"/>',
           f'<text x="{width/2}" y="24" text-anchor="middle" fill="#eee" font-size="16">{html.escape(title)}</text>',
           f'<text x="{width/2}" y="44" text-anchor="middle" fill="#bbb">{total} stack samples over {seconds} s at {hz or "?"} Hz; root at the bottom, callees above, width = share; hover for the share, click to zoom, type in the search box to highlight</text>',
           f'<foreignObject x="10" y="8" width="320" height="30"><input xmlns="http://www.w3.org/1999/xhtml" id="q" placeholder="search frames" style="width:300px;font:12px monospace;background:#333;color:#eee;border:1px solid #666"/></foreignObject>',
           '<g id="boxes">']
    for x0, x1, d, name, count, rgb in boxes:
        px = x0 * width
        pw = max(0.5, (x1 - x0) * width)
        py = top + (depth - 1 - d) * row_h
        share = 100 * count / max(1, total)
        label = html.escape(name)
        fill = "#%02x%02x%02x" % rgb
        out.append(f'<g class="b" data-x0="{x0:.6f}" data-x1="{x1:.6f}" data-name="{label}"><title>{label}: {count} samples, {share:.1f} %</title>'
                   f'<rect x="{px:.2f}" y="{py}" width="{pw:.2f}" height="{row_h - 1}" fill="{fill}" rx="1"/>')
        if pw > 7 * 3 + 6:
            chars = int((pw - 6) / 7)
            text = label if len(name) <= chars else html.escape(name[:max(1, chars - 1)]) + "…"
            out.append(f'<text x="{px + 3:.2f}" y="{py + 12}" fill="#111">{text}</text>')
        out.append('</g>')
    out.append('</g>')
    out.append('''<script><![CDATA[
var W=%d, boxes=document.querySelectorAll('#boxes g');
function zoom(x0,x1){var s=1/(x1-x0);boxes.forEach(function(g){var a=+g.dataset.x0,b=+g.dataset.x1,r=g.querySelector('rect'),t=g.querySelector('text');
 var nx=(Math.max(a,x0)-x0)*s*W,nw=(Math.min(b,x1)-Math.max(a,x0))*s*W;var vis=b>x0&&a<x1;g.style.display=vis?'':'none';
 if(vis){r.setAttribute('x',nx);r.setAttribute('width',Math.max(0.5,nw));if(t){t.setAttribute('x',nx+3);t.style.display=nw>27?'':'none';var n=g.dataset.name,c=Math.floor((nw-6)/7);t.textContent=n.length<=c?n:n.slice(0,Math.max(1,c-1))+'\\u2026';}}});}
boxes.forEach(function(g){g.addEventListener('click',function(e){zoom(+g.dataset.x0,+g.dataset.x1);e.stopPropagation();});});
document.documentElement.addEventListener('click',function(){zoom(0,1);});
document.getElementById('q').addEventListener('input',function(e){var q=e.target.value.toLowerCase();boxes.forEach(function(g){g.classList.toggle('hl',q!==''&&g.dataset.name.toLowerCase().indexOf(q)>=0);});});
]]></script>''' % width)
    out.append('</svg>')
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--out", default=None, help="SVG path (default <run>/flamegraph.svg)")
    ap.add_argument("--folded", default=None, help="also write the merged stacks as 'a;b;c count' lines")
    ap.add_argument("--all", action="store_true", help="every second of the run, not just the route window")
    ap.add_argument("--min-pct", type=float, default=0.05, help="drop boxes below this share of the samples")
    ap.add_argument("--width", type=int, default=1800)
    ap.add_argument("--title", default=None)
    a = ap.parse_args()
    run = Path(a.run)
    window = None if a.all else route_window(run)
    frames, stacks, total, seconds, hz = read_stacks(run, window)
    if total == 0:
        sys.exit("no samples in the window (run without a route window? try --all)")
    root = build_tree(stacks, total)
    boxes = layout(root, a.min_pct / 100)
    title = a.title or f"{run.name}: game thread{' over the route' if window else ''}"
    out = Path(a.out) if a.out else run / "flamegraph.svg"
    out.write_text(svg(boxes, total, a.width, title, hz, seconds))
    if a.folded:
        with open(a.folded, "w") as f:
            for path, c in sorted(stacks.items(), key=lambda kv: -kv[1]):
                f.write(";".join(path) + f" {c}\n")
    # a short text summary so the terminal shows what the picture will
    print(f"{out}: {total} samples over {seconds} s ({len(stacks)} distinct stacks, {len(boxes)} boxes, {len(frames)} frames)")
    for k in sorted(root.kids.values(), key=lambda k: -k.count)[:3]:
        print(f"  {100 * k.count / total:5.1f}%  {k.name}")
        for kk in sorted(k.kids.values(), key=lambda k: -k.count)[:4]:
            print(f"          {100 * kk.count / total:5.1f}%  {kk.name}")


if __name__ == "__main__":
    main()
