#!/usr/bin/env python3
"""Build the optimization-progress dashboard from the run directories.

  harness/dashboard.py [--out docs/benchmark-progress.html] [--json docs/benchmark-progress.json]

Every run under harness/runs/ that produced a frame log is summarised with
harness/analyze.py (the same numbers compare.py uses), tagged with the platform
it ran on (renderer + JVM + desktop, read from its console.txt) and the variant
it measured (pzopt.properties + run.opts + the scenario in pzopt-bench.out),
and written into one self-contained HTML page: status tiles against the
performance target, per-platform frame-tail charts, the chunk-latency result,
utilization, the plan's task progress and a full table. Nothing is typed in by
hand: regenerate after every run.
"""
import datetime
import html
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from analyze import summarize  # noqa: E402
from compare import latency  # noqa: E402

REPO = Path(__file__).resolve().parent.parent
RUNS = REPO / "harness" / "runs"
CHANGES = REPO / "openspec" / "changes"

# Performance target for the driving scenario (docs/plan-driving-frame-time.md, section 2).
TARGET = {
    "frame_p99_ms": 10.0,
    "frame_p99_9_ms": 16.7,
    "frames_over_33ms": 0,
    "jitter_ms": 1.0,
    "under_cap_share_pct": 25.0,
    "gpu_pct_when_unbound": 90.0,
}


def platform_of(env, opts):
    gl = env.get("opengl", "")
    if opts.get("layout") == "native" or "Azul" in env.get("jvm_vendor", ""):
        if "NVIDIA" in gl:
            return "native / NVIDIA GL"
        if "Mesa" in gl or "zink" in gl.lower():
            return "native / Mesa Zink"
        return "native / unknown GL"
    return "Proton / Windows build"


def variant_of(s):
    props = dict(kv.split("=", 1) for kv in s.get("props", "").split() if "=" in kv)
    opts = s.get("opts", {})
    sc = s.get("scenario", {})
    parallel = props.get("parallel", "true") == "true"
    wake = props.get("wake", "true") == "true"
    if not parallel and not wake:
        base = "stock behaviour"
    elif parallel and wake:
        base = f"wake + pool W={props.get('workers', '4')}"
    elif wake:
        base = "wake only"
    else:
        base = f"pool only W={props.get('workers', '4')}"
    tags = []
    if opts.get("no_dashboard") == "1":
        tags.append("no PZDashboard")
    if opts.get("gc") not in (None, "", "default"):
        tags.append(opts["gc"].upper())
    if opts.get("jfr") == "1":
        tags.append("JFR")
    if opts.get("game_profiler") == "1":
        tags.append("GameProfiler")
    if sc.get("mode") == "drive":
        tags.append("driving")
    if "translucentCache" in props and props["translucentCache"] == "true":
        tags.append("translucentCache")
    return base + (" [" + ", ".join(tags) + "]" if tags else "")


def run_date(name):
    m = re.search(r"(\d{8})-(\d{6})$", name)
    if not m:
        return None
    return datetime.datetime.strptime(m.group(1) + m.group(2), "%Y%m%d%H%M%S").isoformat(timespec="seconds")


def collect():
    rows = []
    for d in sorted(RUNS.iterdir()):
        if not d.is_dir() or not (d / "pzopt-frames.out").exists() and not (d / "mangohud.csv").exists():
            continue
        try:
            s = summarize(d)
        except Exception as e:  # a half-written run must not break the page
            print(f"skip {d.name}: {e}", file=sys.stderr)
            continue
        if "frames" not in s and "mangohud" not in s:
            continue
        try:
            s["latency"] = latency(d) if (d / "pzopt-chunks.out").exists() else {}
        except Exception:
            s["latency"] = {}
        env, opts = s.get("environment", {}), s.get("opts", {})
        sc = s.get("scenario", {})
        f = s.get("frames", {}).get("us", {})
        m = s.get("mangohud", {})
        sm = s.get("sysmon", {})
        th = s.get("threads", {})
        row = {
            "run": d.name,
            "date": run_date(d.name),
            "platform": platform_of(env, opts),
            "variant": variant_of(s),
            "mode": sc.get("mode", opts.get("mode", "bench")),
            "valid": s.get("valid", True) and sc.get("route_status", "complete") == "complete",
            "route_status": sc.get("route_status", "complete"),
            "zoom": sc.get("zoom"),
            "resolution": sc.get("resolution") or env.get("desktop", "").replace("Desktop resolution ", ""),
            "opengl": env.get("opengl", "").replace("OpenGL version: ", ""),
            "props": s.get("props", "").replace("\n", " "),
            "frame_mean_ms": f.get("mean", 0) / 1000 if f else None,
            "frame_p50_ms": f.get("p50", 0) / 1000 if f else None,
            "frame_p90_ms": f.get("p90", 0) / 1000 if f else None,
            "frame_p99_ms": f.get("p99", 0) / 1000 if f else None,
            "frame_p99_9_ms": f.get("p99_9", 0) / 1000 if f else None,
            "frame_max_ms": f.get("max", 0) / 1000 if f else None,
            "fps_mean": s.get("frames", {}).get("fps_mean"),
            "frames_over_33ms": s.get("frames", {}).get("over_33ms"),
            "mh_p99_ms": m.get("us", {}).get("p99", 0) / 1000 if m else None,
            "mh_p99_9_ms": m.get("us", {}).get("p99_9", 0) / 1000 if m else None,
            "mh_over_33ms": m.get("over_33ms") if m else None,
            "jitter_ms": m.get("jitter_us", 0) / 1000 if m else None,
            "stdev_ms": m.get("stdev_us", 0) / 1000 if m else None,
            "under_cap_share_pct": m.get("under_cap_share", 0) * 100 if m else None,
            "fps_1pct_low": m.get("fps_1pct_low") if m else None,
            "chunk_p50_ms": s["latency"].get("p50"),
            "chunk_p90_ms": s["latency"].get("p90"),
            "chunk_p99_ms": s["latency"].get("p99"),
            "chunks_per_s": s.get("chunks", {}).get("per_second"),
            "gpu_pct": sm.get("gpu_pct", {}).get("mean") if sm else None,
            "gpu_p90_pct": sm.get("gpu_pct", {}).get("p90") if sm else None,
            "cpu_pct": sm.get("cpu_pct", {}).get("mean") if sm else None,
            "busiest_core_pct": sm.get("cpu_busiest_core_pct", {}).get("mean") if sm else None,
            "process_cores": th.get("process_share") if th else None,
            "game_thread_pct": next((t["share"] * 100 for t in th.get("threads", []) if t["name"] == "MainThread"), None) if th else None,
            "gc_events": s.get("gc", {}).get("events_in_route") if s.get("gc") else None,
            "gc_collector": s.get("gc", {}).get("collector") if s.get("gc") else None,
        }
        rows.append(row)
    return rows


def task_progress():
    out = []
    for change in sorted(CHANGES.iterdir()):
        t = change / "tasks.md"
        if not t.exists():
            continue
        text = t.read_text()
        done = len(re.findall(r"^- \[x\]", text, re.M))
        partial = len(re.findall(r"^- \[~\]", text, re.M))
        todo = len(re.findall(r"^- \[ \]", text, re.M))
        out.append({"change": change.name, "done": done, "partial": partial, "todo": todo})
    return out


def build(rows, tasks):
    data = json.dumps({"generated": datetime.datetime.now().isoformat(timespec="seconds"), "target": TARGET, "runs": rows, "tasks": tasks})
    return TEMPLATE.replace("__DATA__", data.replace("</", "<\\/"))


TEMPLATE = r"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>PZ Optimization — driving frame-time progress</title>
<style>
.viz-root {
  color-scheme: light;
  --surface-1: #fcfcfb; --surface-2: #f0efec; --line: #d9d8d3;
  --text-primary: #0b0b0b; --text-secondary: #52514e; --text-muted: #7a7975;
  --series-1: #2a78d6; --series-2: #eb6834; --series-3: #1baf7a; --series-4: #eda100; --series-5: #e87ba4;
  --good: #0ca30c; --warning: #fab219; --serious: #ec835a; --critical: #d03b3b;
  --target: #52514e;
}
@media (prefers-color-scheme: dark) {
  :root:where(:not([data-theme="light"])) .viz-root {
    color-scheme: dark;
    --surface-1: #1a1a19; --surface-2: #262624; --line: #383835;
    --text-primary: #ffffff; --text-secondary: #c3c2b7; --text-muted: #8f8e86;
    --series-1: #3987e5; --series-2: #d95926; --series-3: #199e70; --series-4: #c98500; --series-5: #d55181;
    --target: #c3c2b7;
  }
}
:root[data-theme="dark"] .viz-root {
  color-scheme: dark;
  --surface-1: #1a1a19; --surface-2: #262624; --line: #383835;
  --text-primary: #ffffff; --text-secondary: #c3c2b7; --text-muted: #8f8e86;
  --series-1: #3987e5; --series-2: #d95926; --series-3: #199e70; --series-4: #c98500; --series-5: #d55181;
  --target: #c3c2b7;
}
* { box-sizing: border-box; }
body { margin: 0; font: 14px/1.45 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
.viz-root { background: var(--surface-1); color: var(--text-primary); min-height: 100vh; padding: 24px clamp(16px, 4vw, 48px) 48px; }
h1 { font-size: 22px; margin: 0 0 4px; font-weight: 650; }
h2 { font-size: 16px; margin: 32px 0 8px; font-weight: 600; }
.sub { color: var(--text-secondary); margin: 0 0 16px; max-width: 80ch; }
.row { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; margin: 8px 0 16px; }
.row label { color: var(--text-secondary); }
select, button { font: inherit; color: var(--text-primary); background: var(--surface-2); border: 1px solid var(--line); border-radius: 6px; padding: 4px 8px; }
.tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr)); gap: 12px; }
.tile { background: var(--surface-2); border: 1px solid var(--line); border-radius: 10px; padding: 12px 14px; }
.tile .k { color: var(--text-secondary); font-size: 12px; }
.tile .v { font-size: 26px; font-weight: 650; line-height: 1.2; margin: 2px 0; font-variant-numeric: tabular-nums; }
.tile .d { color: var(--text-secondary); font-size: 12px; }
.tile .s { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; margin-top: 4px; color: var(--text-secondary); }
.dot { width: 9px; height: 9px; border-radius: 50%; display: inline-block; }
.panel { background: var(--surface-1); border: 1px solid var(--line); border-radius: 10px; padding: 12px 14px 8px; margin-bottom: 14px; }
.panel h3 { margin: 0 0 2px; font-size: 14px; font-weight: 600; }
.panel .note { color: var(--text-muted); font-size: 12px; margin: 0 0 8px; }
.legend { display: flex; flex-wrap: wrap; gap: 14px; font-size: 12px; color: var(--text-secondary); margin: 6px 0 4px; }
.legend span { display: inline-flex; align-items: center; gap: 6px; }
.sw { width: 12px; height: 12px; border-radius: 3px; display: inline-block; }
svg text { fill: var(--text-secondary); font-size: 11px; }
svg .axis line, svg .axis path { stroke: var(--line); }
svg .grid line { stroke: var(--line); stroke-dasharray: 2 3; }
svg .tline { stroke: var(--target); stroke-width: 1.5; stroke-dasharray: 5 4; }
svg .tlabel { fill: var(--text-secondary); font-size: 11px; }
svg .lbl { fill: var(--text-primary); font-size: 11px; font-variant-numeric: tabular-nums; }
svg rect.bar { rx: 0; }
svg .hit { fill: transparent; }
.tip { position: fixed; pointer-events: none; background: var(--surface-2); color: var(--text-primary); border: 1px solid var(--line); border-radius: 6px; padding: 6px 9px; font-size: 12px; box-shadow: 0 4px 16px rgba(0,0,0,.18); display: none; max-width: 340px; z-index: 5; }
table { border-collapse: collapse; width: 100%; font-size: 12px; font-variant-numeric: tabular-nums; }
th, td { text-align: right; padding: 5px 8px; border-bottom: 1px solid var(--line); white-space: nowrap; }
th:first-child, td:first-child, th.l, td.l { text-align: left; }
th { color: var(--text-secondary); font-weight: 600; position: sticky; top: 0; background: var(--surface-1); cursor: pointer; }
.wrap { overflow: auto; max-height: 520px; border: 1px solid var(--line); border-radius: 10px; }
.bar-track { background: var(--surface-2); border-radius: 4px; height: 10px; overflow: hidden; display: flex; }
.bar-track i { display: block; height: 100%; }
.tasks { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 12px; }
.tasks .t { border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px; }
.tasks .t b { display: block; margin-bottom: 6px; font-weight: 600; }
.tasks .t small { color: var(--text-secondary); }
.muted { color: var(--text-muted); }
code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
.invalid td { color: var(--text-muted); }
footer { margin-top: 32px; color: var(--text-muted); font-size: 12px; }
</style>
</head>
<body>
<div class="viz-root" id="root">
<h1>Project Zomboid optimization — driving frame-time progress</h1>
<p class="sub">Objective: consistent frame time while driving, with the CPU and GPU used whenever the game is not pegged at the 240 fps cap. Every number on this page comes from a run directory under <code>harness/runs/</code> via <code>harness/analyze.py</code>; regenerate with <code>python3 harness/dashboard.py</code>. Generated <span id="gen"></span>.</p>

<div class="row">
  <label>Platform <select id="platform"></select></label>
  <label>Scenario <select id="mode"><option value="all">all</option><option value="bench">teleport route (bench)</option><option value="drive">driving route</option></select></label>
  <label><input type="checkbox" id="showInvalid"> include rejected / incomplete runs</label>
  <button id="theme" type="button">Toggle dark</button>
</div>

<h2>Where we are against the target</h2>
<p class="sub">Latest stock-behaviour run on the selected platform and scenario, next to the best variant measured there. Target values are the driving target from <code>docs/plan-driving-frame-time.md</code> (status: good = meets target, warning = within 25 %, critical = further away).</p>
<div class="tiles" id="tiles"></div>

<h2>Frame-time tail per run</h2>
<div class="panel">
  <h3>p99 and p99.9 frame time (in-game sampler), by run</h3>
  <p class="note">One bar pair per run in time order; the dashed line is the target. Runs on different platforms (renderer / JVM / desktop) are not comparable with each other, so change the platform filter rather than reading across it.</p>
  <div class="legend" id="leg-tail"></div>
  <div id="chart-tail"></div>
</div>
<div class="panel">
  <h3>Frames over 33 ms on the route (in-game sampler)</h3>
  <p class="note">Spike count on the 100 s route; the driving target is zero.</p>
  <div id="chart-spikes"></div>
</div>

<h2>Chunk streaming (the shipped change)</h2>
<div class="panel">
  <h3>Chunk latency, enqueue to publish: p50 / p90 / p99 per run</h3>
  <p class="note">The wake-on-enqueue + recalc-pool result. Log scale: stock is ~170 ms at the median, the shipped default under 10 ms.</p>
  <div class="legend" id="leg-chunk"></div>
  <div id="chart-chunk"></div>
</div>

<h2>Machine utilization on the route</h2>
<div class="panel">
  <h3>GPU busy % (nvidia-smi) and game-process cores busy</h3>
  <p class="note">From <code>sysmon.csv</code> and <code>pzopt-threads.out</code>. When the game is not at the cap the objective wants these high; a GPU at 100 % with idle cores means the frame is GPU-bound.</p>
  <div class="legend" id="leg-util"></div>
  <div id="chart-util"></div>
</div>

<h2>Plan progress</h2>
<div class="tasks" id="tasks"></div>

<h2>All runs</h2>
<p class="sub">Click a header to sort. Rejected or incomplete runs are hidden unless the checkbox above is set.</p>
<div class="wrap"><table id="table"><thead></thead><tbody></tbody></table></div>
<footer>Sources: <code>harness/runs/*/</code> (pzopt-frames.out, pzopt-chunks.out, mangohud.csv, sysmon.csv, pzopt-threads.out, console.txt), <code>openspec/changes/*/tasks.md</code>. Built by <code>harness/dashboard.py</code>.</footer>
</div>
<div class="tip" id="tip"></div>
<script id="data" type="application/json">__DATA__</script>
<script>
const DATA = JSON.parse(document.getElementById('data').textContent);
const T = DATA.target;
document.getElementById('gen').textContent = DATA.generated;
const root = document.documentElement;
document.getElementById('theme').onclick = () => {
  const dark = root.getAttribute('data-theme') === 'dark' || (!root.getAttribute('data-theme') && matchMedia('(prefers-color-scheme: dark)').matches);
  root.setAttribute('data-theme', dark ? 'light' : 'dark');
};
const tip = document.getElementById('tip');
function showTip(e, html) { tip.innerHTML = html; tip.style.display = 'block'; moveTip(e); }
function moveTip(e) { tip.style.left = (e.clientX + 14) + 'px'; tip.style.top = (e.clientY + 14) + 'px'; }
function hideTip() { tip.style.display = 'none'; }
const fmt = (v, d = 1) => v == null ? '–' : Number(v).toFixed(d);
const css = n => getComputedStyle(document.getElementById('root')).getPropertyValue(n).trim();

const platforms = [...new Set(DATA.runs.map(r => r.platform))];
const sel = document.getElementById('platform');
for (const p of platforms) { const o = document.createElement('option'); o.value = p; o.textContent = p; sel.appendChild(o); }
sel.value = platforms.includes('native / NVIDIA GL') ? 'native / NVIDIA GL' : platforms[platforms.length - 1];
for (const id of ['platform', 'mode', 'showInvalid']) document.getElementById(id).addEventListener('change', render);
matchMedia('(prefers-color-scheme: dark)').addEventListener('change', render);
new MutationObserver(render).observe(root, { attributes: true, attributeFilter: ['data-theme'] });

function filtered() {
  const p = sel.value, m = document.getElementById('mode').value, inv = document.getElementById('showInvalid').checked;
  return DATA.runs.filter(r => r.platform === p && (m === 'all' || r.mode === m) && (inv || r.valid));
}
function isStock(r) { return r.variant.startsWith('stock behaviour') && !/\[/.test(r.variant); }

function status(v, target, lowerBetter = true) {
  if (v == null) return ['muted', 'no data'];
  const ok = lowerBetter ? v <= target : v >= target;
  if (ok) return ['good', 'meets target'];
  const near = lowerBetter ? v <= target * 1.25 : v >= target * 0.75;
  return near ? ['warning', 'within 25 %'] : ['critical', 'off target'];
}

function tiles() {
  const rs = filtered();
  const stock = rs.filter(isStock).slice(-1)[0];
  const best = rs.filter(r => r.frame_p99_ms != null).sort((a, b) => a.frame_p99_ms - b.frame_p99_ms)[0];
  const el = document.getElementById('tiles'); el.innerHTML = '';
  const defs = [
    ['frame p99', 'frame_p99_ms', T.frame_p99_ms, 'ms', true],
    ['frame p99.9', 'frame_p99_9_ms', T.frame_p99_9_ms, 'ms', true],
    ['frames > 33 ms', 'frames_over_33ms', T.frames_over_33ms, '', true],
    ['frame-to-frame jitter', 'jitter_ms', T.jitter_ms, 'ms', true],
    ['frames below 240 fps cap', 'under_cap_share_pct', T.under_cap_share_pct, '%', true],
    ['GPU busy', 'gpu_pct', T.gpu_pct_when_unbound, '%', false],
  ];
  if (!stock && !best) { el.innerHTML = '<div class="tile"><div class="k">no runs match the filter</div></div>'; return; }
  for (const [label, key, target, unit, lower] of defs) {
    const r = stock || best;
    const v = r[key];
    const [st, txt] = status(v, target, lower);
    const bestV = best ? best[key] : null;
    const d = document.createElement('div'); d.className = 'tile';
    d.innerHTML = `<div class="k">${label} · latest stock</div><div class="v">${fmt(v, key === 'frames_over_33ms' ? 0 : 1)}${unit ? ' ' + unit : ''}</div>` +
      `<div class="d">target ${lower ? '≤' : '≥'} ${target}${unit}${best && best !== r ? ` · best variant ${fmt(bestV, key === 'frames_over_33ms' ? 0 : 1)}${unit} (${best.variant})` : ''}</div>` +
      `<div class="s"><span class="dot" style="background:var(--${st === 'muted' ? 'line' : st})"></span>${st === 'good' ? '✓' : st === 'warning' ? '△' : st === 'critical' ? '✕' : '·'} ${txt}</div>`;
    d.title = r.run; el.appendChild(d);
  }
}

// generic grouped bar chart: rows = runs, series = [{key,label,color}], opts {log, target, unit}
function barChart(id, legendId, rows, series, opts) {
  const host = document.getElementById(id); host.innerHTML = '';
  if (legendId) {
    const lg = document.getElementById(legendId); lg.innerHTML = '';
    for (const s of series) { const sp = document.createElement('span'); sp.innerHTML = `<i class="sw" style="background:${s.color}"></i>${s.label}`; lg.appendChild(sp); }
    if (opts.target != null) { const sp = document.createElement('span'); sp.innerHTML = `<i class="sw" style="background:transparent;border-top:2px dashed var(--target);height:0;border-radius:0"></i>target ${opts.target}${opts.unit || ''}`; lg.appendChild(sp); }
  }
  rows = rows.filter(r => series.some(s => r[s.key] != null));
  if (!rows.length) { host.innerHTML = '<p class="muted">no runs with this metric match the filter</p>'; return; }
  const W = Math.max(640, host.clientWidth || 900), H = 260, m = { t: 12, r: 16, b: 78, l: 46 };
  const iw = W - m.l - m.r, ih = H - m.t - m.b;
  const vals = rows.flatMap(r => series.map(s => r[s.key])).filter(v => v != null).concat(opts.target != null ? [opts.target] : []);
  let max = Math.max(...vals) * 1.08, min = 0;
  const log = opts.log;
  if (log) { min = Math.max(0.5, Math.min(...vals.filter(v => v > 0)) / 2); }
  const y = v => log ? m.t + ih - (Math.log10(Math.max(v, min)) - Math.log10(min)) / (Math.log10(max) - Math.log10(min)) * ih : m.t + ih - (v / max) * ih;
  const gw = Math.min(110, iw / rows.length), bw = Math.max(3, Math.min(22, (gw - 8) / series.length - 2));
  let svg = `<svg width="${W}" height="${H}" role="img" aria-label="${opts.title || ''}">`;
  const ticks = log ? [1, 3, 10, 30, 100, 300, 1000].filter(t => t >= min && t <= max) : [0, 0.25, 0.5, 0.75, 1].map(f => Math.round(max * f * 10) / 10);
  svg += '<g class="grid">' + ticks.map(t => `<line x1="${m.l}" x2="${W - m.r}" y1="${y(t)}" y2="${y(t)}"/>`).join('') + '</g>';
  svg += '<g class="axis">' + ticks.map(t => `<text x="${m.l - 6}" y="${y(t) + 4}" text-anchor="end">${t}</text>`).join('') + `<line x1="${m.l}" x2="${m.l}" y1="${m.t}" y2="${m.t + ih}"/></g>`;
  rows.forEach((r, i) => {
    const x0 = m.l + i * gw + (gw - series.length * (bw + 2)) / 2;
    series.forEach((s, j) => {
      const v = r[s.key]; if (v == null) return;
      const x = x0 + j * (bw + 2), yy = y(v), h = m.t + ih - yy;
      svg += `<rect class="bar" x="${x}" y="${yy}" width="${bw}" height="${Math.max(0, h)}" fill="${s.color}" rx="3"/>`;
      if (opts.label && series.length <= 2) svg += `<text class="lbl" x="${x + bw / 2}" y="${yy - 3}" text-anchor="middle">${fmt(v, opts.digits ?? 1)}</text>`;
    });
    const short = r.run.replace(/-\d{8}-\d{6}$/, '');
    svg += `<text x="${m.l + i * gw + gw / 2}" y="${m.t + ih + 12}" text-anchor="end" transform="rotate(-38 ${m.l + i * gw + gw / 2} ${m.t + ih + 12})">${short.length > 22 ? short.slice(0, 21) + '…' : short}</text>`;
    svg += `<rect class="hit" data-i="${i}" x="${m.l + i * gw}" y="${m.t}" width="${gw}" height="${ih}"/>`;
  });
  if (opts.target != null) svg += `<line class="tline" x1="${m.l}" x2="${W - m.r}" y1="${y(opts.target)}" y2="${y(opts.target)}"/><text class="tlabel" x="${W - m.r}" y="${y(opts.target) - 4}" text-anchor="end">target ${opts.target}${opts.unit || ''}</text>`;
  svg += '</svg>'; host.innerHTML = svg;
  host.querySelectorAll('.hit').forEach(h => {
    const r = rows[+h.dataset.i];
    const body = `<b>${r.run}</b><br>${r.variant}<br>${r.date || ''} · ${r.platform}<br>` + series.map(s => `${s.label}: <b>${fmt(r[s.key], opts.digits ?? 1)}${opts.unit || ''}</b>`).join(' · ');
    h.addEventListener('mouseenter', e => showTip(e, body)); h.addEventListener('mousemove', moveTip); h.addEventListener('mouseleave', hideTip);
  });
}

function tasks() {
  const el = document.getElementById('tasks'); el.innerHTML = '';
  for (const t of DATA.tasks) {
    const n = t.done + t.partial + t.todo || 1;
    const d = document.createElement('div'); d.className = 't';
    d.innerHTML = `<b>${t.change}</b><div class="bar-track"><i style="width:${t.done / n * 100}%;background:var(--good)"></i><i style="width:${t.partial / n * 100}%;background:var(--warning)"></i></div><small>${t.done} done · ${t.partial} partial · ${t.todo} open</small>`;
    el.appendChild(d);
  }
}

const COLS = [
  ['run', 'run', 'l'], ['date', 'date', 'l'], ['platform', 'platform', 'l'], ['variant', 'variant', 'l'], ['mode', 'mode', 'l'], ['status', 'route_status', 'l'],
  ['zoom', 'zoom'], ['fps mean', 'fps_mean', 0], ['p50', 'frame_p50_ms'], ['p90', 'frame_p90_ms'], ['p99', 'frame_p99_ms'], ['p99.9', 'frame_p99_9_ms'], ['max', 'frame_max_ms'], ['>33ms', 'frames_over_33ms', 0],
  ['MH p99', 'mh_p99_ms'], ['MH p99.9', 'mh_p99_9_ms'], ['jitter', 'jitter_ms', 2], ['<cap %', 'under_cap_share_pct'], ['1%-low fps', 'fps_1pct_low', 0],
  ['chunk p50', 'chunk_p50_ms'], ['chunk p90', 'chunk_p90_ms'], ['chunk p99', 'chunk_p99_ms'], ['chunks/s', 'chunks_per_s'],
  ['GPU %', 'gpu_pct', 0], ['GPU p90 %', 'gpu_p90_pct', 0], ['CPU %', 'cpu_pct', 0], ['cores', 'process_cores', 2], ['game thr %', 'game_thread_pct', 0], ['GC', 'gc_events', 0], ['props', 'props', 'l'],
];
let sortKey = 'date', sortAsc = true;
function table() {
  const rows = DATA.runs.filter(r => document.getElementById('showInvalid').checked || r.valid).slice()
    .sort((a, b) => { const x = a[sortKey], y = b[sortKey]; if (x == null) return 1; if (y == null) return -1; return (x < y ? -1 : x > y ? 1 : 0) * (sortAsc ? 1 : -1); });
  const th = document.querySelector('#table thead'); th.innerHTML = '<tr>' + COLS.map(c => `<th class="${c[2] === 'l' ? 'l' : ''}" data-k="${c[1]}">${c[0]}${sortKey === c[1] ? (sortAsc ? ' ▲' : ' ▼') : ''}</th>`).join('') + '</tr>';
  th.querySelectorAll('th').forEach(h => h.onclick = () => { if (sortKey === h.dataset.k) sortAsc = !sortAsc; else { sortKey = h.dataset.k; sortAsc = true; } table(); });
  const tb = document.querySelector('#table tbody');
  tb.innerHTML = rows.map(r => `<tr class="${r.valid ? '' : 'invalid'}">` + COLS.map(c => { const v = r[c[1]]; const s = c[2] === 'l' ? (v ?? '') : typeof v === 'number' ? fmt(v, typeof c[2] === 'number' ? c[2] : 1) : (v ?? '–'); return `<td class="${c[2] === 'l' ? 'l' : ''}">${s}</td>`; }).join('') + '</tr>').join('');
}

function render() {
  const rs = filtered();
  const c = k => css('--series-' + k);
  tiles();
  barChart('chart-tail', 'leg-tail', rs, [{ key: 'frame_p99_ms', label: 'p99 (ms)', color: c(1) }, { key: 'frame_p99_9_ms', label: 'p99.9 (ms)', color: c(2) }], { target: T.frame_p99_ms, unit: ' ms', label: true, title: 'frame-time tail per run' });
  barChart('chart-spikes', null, rs, [{ key: 'frames_over_33ms', label: 'frames > 33 ms', color: c(1) }], { target: T.frames_over_33ms, unit: '', label: true, digits: 0, title: 'spikes per run' });
  barChart('chart-chunk', 'leg-chunk', rs, [{ key: 'chunk_p50_ms', label: 'p50 (ms)', color: c(1) }, { key: 'chunk_p90_ms', label: 'p90 (ms)', color: c(2) }, { key: 'chunk_p99_ms', label: 'p99 (ms)', color: c(3) }], { log: true, unit: ' ms', title: 'chunk latency per run' });
  barChart('chart-util', 'leg-util', rs, [{ key: 'gpu_pct', label: 'GPU busy (%)', color: c(1) }, { key: 'busiest_core_pct', label: 'busiest core (%)', color: c(2) }], { target: T.gpu_pct_when_unbound, unit: ' %', label: true, digits: 0, title: 'utilization per run' });
  tasks(); table();
}
render();
addEventListener('resize', render);
</script>
</body>
</html>
"""


if __name__ == "__main__":
    args = sys.argv[1:]
    out = REPO / "docs" / "benchmark-progress.html"
    out_json = REPO / "docs" / "benchmark-progress.json"
    if "--out" in args:
        out = Path(args[args.index("--out") + 1])
    if "--json" in args:
        out_json = Path(args[args.index("--json") + 1])
    rows = collect()
    tasks = task_progress()
    out.write_text(build(rows, tasks))
    out_json.write_text(json.dumps({"target": TARGET, "runs": rows, "tasks": tasks}, indent=1))
    print(f"{len(rows)} runs -> {out} (+ {out_json.name})")
