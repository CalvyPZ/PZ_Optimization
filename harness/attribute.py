#!/usr/bin/env python3
"""Attribute the game thread's slow frames to code, from a JFR recording
joined with the per-frame timings of a harness run.

  harness/attribute.py <run-dir>... [--threshold-ms 20] [--top 20] [--json out.json] [--thread MainThread|main]
                       [--after-mark PREFIX:N]   "slow" = the frame holding every "# PREFIX..." mark and the N-1 after it, instead of a threshold
                                                (e.g. zoom-:2 for the frames of a harness zoom step, flag zoom_cycle)
                       [--check-anchors] [--java-period-ms 10] [--native-period-ms 20]
                       [--drill pkg.Class.method[,...]]   what a method was doing (callees) in slow vs ordinary frames

Inputs per run directory (harness/run.sh --jfr --prop instrument=true):
  pzopt-frames.out  per-frame durations with "# anchor <epochUs> <offsetUs>" lines (pzopt.Stats)
  pzopt.jfr         the flight recording; converted once to samples.tsv by tools/JfrSamples.java
  pzopt-bench.out   route window stamps (used by --check-anchors)

Every MainThread sample is assigned to the frame whose [start, end) contains
its timestamp. Samples closer than 0.5 ms to a frame boundary are dropped:
the two clocks are paired to about that precision. Frames on the route with
a duration >= threshold are "slow"; the rest "ordinary". For each group the
samples are ranked by top frame, by first zombie.* frame and by a set of
inclusive markers (the stack contains the method). With several runs, the
tallies are pooled, which is what the rare >=33 ms and >=50 ms spikes need.
"""
import bisect
import json
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
GAME_THREAD = "MainThread"
BOUNDARY_US = 500

# inclusive markers: share of samples whose stack contains the method (any depth)
MARKERS = [
    ("chunk hand-off (IsoChunkMap.updateInternal)", "zombie.iso.IsoChunkMap.updateInternal"),
    ("  doLoadGridsquare", "zombie.iso.IsoChunk.doLoadGridsquare"),
    ("  addChunkToWorld (pathfind)", "zombie.pathfind.PolygonalMap2.addChunkToWorld"),
    ("chunk unload/save (IsoChunkMap)", "zombie.iso.IsoChunkMap.Unload"),
    ("IsoChunkMap.update (all)", "zombie.iso.IsoChunkMap.update"),
    ("lighting (LightingThread.update)", "zombie.iso.LightingThread.update"),
    ("logic (GameWindow.logic)", "zombie.GameWindow.logic"),
    ("  IsoWorld.update", "zombie.iso.IsoWorld.update"),
    ("  IsoCell.update", "zombie.iso.IsoCell.update"),
    ("  zombies (ZombiePopulationManager)", "zombie.popman.ZombiePopulationManager"),
    ("  Lua events (LuaEventManager)", "zombie.Lua.LuaEventManager"),
    ("  Lua VM (kahlua)", "se.krka.kahlua"),
    ("render (GameWindow.renderInternal)", "zombie.GameWindow.renderInternal"),
    ("  IsoCell.render", "zombie.iso.IsoCell.render"),
    ("  RenderThread / GL sync", "zombie.core.opengl.RenderThread"),
    ("  texture / model loading", "zombie.core.textures"),
    ("  streaming file I/O", "java.io.RandomAccessFile"),
    ("world save (IsoWorld.save)", "zombie.iso.IsoWorld.save"),
    ("region flood fill (isoregion)", "zombie.iso.areas.isoregion"),
    ("vehicles", "zombie.vehicles"),
    ("sound (fmod)", "fmod"),
    ("thread wait/park", "java.util.concurrent.locks.LockSupport.park"),
    ("Object.wait", "java.lang.Object.wait"),
    # render thread ("main" on the native build, "Render Thread" on Windows): --thread main
    ("RT: wait for a ready state (idle)", "zombie.core.SpriteRenderer.waitForReadyState"),
    ("RT: postRender (draw buffer build + GL calls)", "zombie.core.SpriteRenderer.postRender"),
    ("RT:   world draw buffer", "zombie.core.SpriteRenderer.buildStateDrawBuffer"),
    ("RT:   UI draw buffer", "zombie.core.SpriteRenderer.buildStateUIDrawBuffer"),
    ("RT:   chunk texture bake (FBORenderChunk)", "zombie.iso.fboRenderChunk.FBORenderChunk"),
    ("RT:   texture upload/create (TextureID)", "zombie.core.textures.TextureID"),
    ("RT:   shader compile", "zombie.core.opengl.Shader"),
    ("RT: Display.update (swap / GPU wait)", "org.lwjgl.opengl.Display.update"),
    ("RT: invoke queue (game-thread GL requests)", "zombie.core.opengl.RenderThread.flushInvokeQueue"),
]


def short(frame):
    """zombie.iso.IsoChunkMap.updateInternal -> IsoChunkMap.updateInternal"""
    parts = frame.split(".")
    return ".".join(parts[-2:]) if len(parts) > 2 else frame


def load_frames(run):
    """Frames with absolute start/end (epoch microseconds) plus the route window.

    Returns (frames, window, anchors, markers): frames = list of (start_us, end_us, dur_us, frame_id),
    window = (route_start_us, route_end_us) or None, anchors = list of (epoch_us, offset_us),
    markers = {label: offset_us}. frame_id is the game's frame counter (None if the log predates it).
    """
    frames, anchors, markers = [], [], {}
    marklist = []  # every mark in order, (label, offset_us); markers keeps the last per label
    anchor = None
    cursor = None  # epoch µs at which the next frame starts
    fid = None     # game frame counter of the next frame
    for line in (Path(run) / "pzopt-frames.out").read_text().splitlines():
        if not line.strip():
            continue
        if line.startswith("#"):
            parts = line.split()
            if parts[1] == "anchor":
                anchor = (int(parts[2]), int(parts[3]))
                anchors.append(anchor)
                cursor = anchor[0]
                fid = int(parts[4]) if len(parts) > 4 else None
            else:
                markers[parts[1]] = int(parts[2])
                marklist.append((parts[1], int(parts[2])))
            continue
        if cursor is None:  # frames before the first anchor (older log format): unusable for the join
            continue
        d = int(line)
        frames.append((cursor, cursor + d, d, fid))
        cursor += d
        if fid is not None:
            fid += 1
    window = None
    if anchors and "route-start" in markers and "route-end" in markers:
        window = (offset_to_epoch(anchors, markers["route-start"]), offset_to_epoch(anchors, markers["route-end"]))
    markers["__list__"] = marklist
    return frames, window, anchors, markers


def offset_to_epoch(anchors, offset_us):
    """Map a monotonic offset (µs since Stats init) to epoch µs using the nearest anchor."""
    a = min(anchors, key=lambda x: abs(x[1] - offset_us))
    return a[0] + (offset_us - a[1])


def check_anchors(run):
    """Compare anchor-derived route stamps with the wall-clock stamps in pzopt-bench.out."""
    frames, window, anchors, markers = load_frames(run)
    bench = Path(run) / "pzopt-bench.out"
    kv = dict(l.split("=", 1) for l in bench.read_text().splitlines() if "=" in l) if bench.exists() else {}
    base = [a[0] - a[1] for a in anchors]
    print(f"{Path(run).name}: {len(anchors)} anchors, {len(frames)} frames; epoch-offset spread across anchors "
          f"{(max(base) - min(base)) / 1000:.2f} ms")
    ok = True
    for label, key in (("route-start", "route_start_epoch_ms"), ("route-end", "route_end_epoch_ms")):
        if label in markers and key in kv:
            d = (offset_to_epoch(anchors, markers[label]) - int(kv[key]) * 1000) / 1000
            print(f"  {label}: anchor-derived minus pzopt-bench.out = {d:+.2f} ms")
            ok = ok and abs(d) < 5
    if window:
        n = sum(1 for f in frames if window[0] <= f[0] < window[1])
        print(f"  frames inside the route window: {n}, window {((window[1] - window[0]) / 1e6):.2f} s")
    return ok


def load_samples(run, thread=GAME_THREAD):
    """MainThread samples [(epoch_us, kind, frames)] and GC-side events from the recording.

    events = {"gc": [(epoch_us, name, sum_of_pauses_us)], "pause": [(epoch_us, phase, us)],
              "stall": [(epoch_us, thread, us)]}  (ZGC allocation stalls: a thread blocked waiting for the collector)
    """
    run = Path(run)
    tsv = run / "samples.tsv"
    jfr = run / "pzopt.jfr"
    if not tsv.exists() or tsv.stat().st_mtime < jfr.stat().st_mtime:
        with tsv.open("w") as out:
            subprocess.run(["java", str(REPO / "tools" / "JfrSamples.java"), str(jfr)], stdout=out, check=True)
    samples, events = [], {"gc": [], "pause": [], "stall": []}
    with tsv.open() as f:
        f.readline()
        for line in f:
            kind, th, t, rest = line.rstrip("\n").split("\t", 3)
            if kind == "wait":  # blocking events (harness/waits.py), not samples
                continue
            if kind in events:
                events[kind].append((int(t) // 1000, th, int(rest)))
            elif th == thread:
                samples.append((int(t) // 1000, kind, rest.split(";")))
    return samples, events


def first_game_frame(frames):
    for fr in frames:
        if fr.startswith("zombie.") or fr.startswith("se.krka.") or fr.startswith("fmod."):
            return fr
    return frames[0] if frames else "?"


def section(frames):
    """The direct child of GameWindow.frameStep on the stack: the game's own coarse sections."""
    for i, fr in enumerate(frames):
        if fr == "zombie.GameWindow.frameStep" or fr == "zombie.core.opengl.RenderThread.lockStepRenderStep":
            return frames[i - 1] if i > 0 else fr
    return "(outside frameStep)"


class Drill:
    """For samples whose stack contains a method: what that method was doing (its callee chain) and the top frames."""
    def __init__(self, method):
        self.method = method
        self.n = 0
        self.callee1 = Counter()
        self.callee2 = Counter()
        self.caller2 = Counter()
        self.top = Counter()

    def add(self, frames):
        for i, fr in enumerate(frames):
            if fr == self.method or fr.startswith(self.method + "."):
                self.n += 1
                self.callee1[frames[i - 1] if i >= 1 else "(self)"] += 1
                self.callee2[" > ".join(short(x) for x in frames[max(0, i - 2):i][::-1]) if i >= 1 else "(self)"] += 1
                self.caller2[" < ".join(short(x) for x in frames[i + 1:i + 4]) if i + 1 < len(frames) else "(root)"] += 1
                self.top[frames[0]] += 1
                return


class Tally:
    def __init__(self):
        self.n = 0
        self.java = 0
        self.native = 0
        self.top = Counter()
        self.game = Counter()
        self.section = Counter()
        self.marker = Counter()
        self.marker_us = Counter()
        self.section_us = Counter()
        self.frames = 0
        self.frame_us = 0
        self.sample_us = 0

    def add(self, kind, frames, period_us):
        self.n += 1
        self.sample_us += period_us
        if kind == "java":
            self.java += 1
        else:
            self.native += 1
        self.top[frames[0] if frames else "?"] += 1
        self.game[first_game_frame(frames)] += 1
        sec = section(frames)
        self.section[sec] += 1
        self.section_us[sec] += period_us
        joined = ";" + ";".join(frames) + ";"
        for label, m in MARKERS:
            if ";" + m in joined or "." + m in joined:
                self.marker[label] += 1
                self.marker_us[label] += period_us


def attribute(runs, threshold_ms=20.0, java_period_ms=10.0, native_period_ms=20.0, drill=(), after_mark=None):
    groups = {"slow": Tally(), "ordinary": Tally(), "spike33": Tally(), "spike50": Tally()}
    drills = {g: [Drill(m) for m in drill] for g in ("slow", "ordinary")}
    info = {"runs": [], "dropped_boundary": 0, "outside_window": 0, "gc_in_window": [], "pauses_in_window": [], "stalls_in_window": []}
    for run in runs:
        frames, window, anchors, markers = load_frames(run)
        samples, events = load_samples(run, GAME_THREAD)
        opts = {}
        if (Path(run) / "run.opts").exists():
            opts = dict(l.split("=", 1) for l in (Path(run) / "run.opts").read_text().splitlines() if "=" in l)
        jp = float(opts["jfr_period"]) if opts.get("jfr_period") else java_period_ms
        if window:
            frames = [f for f in frames if window[0] <= f[0] < window[1]]
        starts = [f[0] for f in frames]
        thr = threshold_ms * 1000
        slow_idx = None  # --after-mark: the frame indices that count as slow
        if after_mark:
            prefix, nf = after_mark
            slow_idx = set()
            for label, off in markers.get("__list__", []):
                if label.startswith(prefix):
                    i0 = max(0, bisect.bisect_right(starts, offset_to_epoch(anchors, off)) - 1)  # the frame the mark fell in
                    slow_idx.update(range(i0, min(i0 + nf, len(frames))))
        def is_slow(idx, d):
            return idx in slow_idx if slow_idx is not None else d >= thr
        for idx, (start, end, d, _fid) in enumerate(frames):
            for g, lim in (("spike33", 33333), ("spike50", 50000)):
                if d >= lim:
                    groups[g].frames += 1
                    groups[g].frame_us += d
            if is_slow(idx, d):
                groups["slow"].frames += 1
                groups["slow"].frame_us += d
            else:
                groups["ordinary"].frames += 1
                groups["ordinary"].frame_us += d
        assigned = 0
        for t, kind, st in samples:
            if window and not (window[0] <= t < window[1]):
                info["outside_window"] += 1
                continue
            i = bisect.bisect_right(starts, t) - 1
            if i < 0:
                continue
            start, end, d, _fid = frames[i]
            if t >= end:
                continue
            if t - start < BOUNDARY_US or end - t < BOUNDARY_US:
                info["dropped_boundary"] += 1
                continue
            assigned += 1
            period = (jp if kind == "java" else native_period_ms) * 1000
            if is_slow(i, d):
                groups["slow"].add(kind, st, period)
            else:
                groups["ordinary"].add(kind, st, period)
            for dr in drills["slow" if is_slow(i, d) else "ordinary"]:
                dr.add(st)
            if d >= 33333:
                groups["spike33"].add(kind, st, period)
            if d >= 50000:
                groups["spike50"].add(kind, st, period)
        inw = (lambda ev: [e for e in ev if window[0] <= e[0] < window[1]]) if window else (lambda ev: ev)
        gc_in, pauses, stalls = inw(events["gc"]), inw(events["pause"]), inw(events["stall"])
        info["gc_in_window"].extend(gc_in)
        info["pauses_in_window"].extend(pauses)
        info["stalls_in_window"].extend(stalls)
        # which frames the stop-the-world pauses and the game thread's allocation stalls landed in
        hit = Counter()
        for t, th, us in pauses + [x for x in stalls if x[1] == GAME_THREAD]:
            i = bisect.bisect_right(starts, t) - 1
            if 0 <= i < len(frames) and t < frames[i][1]:
                hit[frames[i][2] >= thr] += 1
        main_stalls = [x for x in stalls if x[1] == GAME_THREAD]
        info["runs"].append({
            "run": Path(run).name, "frames_on_route": len(frames), "samples": len(samples), "assigned": assigned,
            "route_s": (window[1] - window[0]) / 1e6 if window else None, "java_period_ms": jp,
            "gc_events_in_window": len(gc_in), "gc_pause_ms_in_window": sum(g[2] for g in gc_in) / 1000,
            "stw_pauses_in_window": len(pauses), "stw_pause_ms_in_window": sum(p[2] for p in pauses) / 1000,
            "stw_pause_max_ms": max([p[2] for p in pauses], default=0) / 1000,
            "game_thread_stalls_in_window": len(main_stalls), "game_thread_stall_ms": sum(x[2] for x in main_stalls) / 1000,
            "game_thread_stall_max_ms": max([x[2] for x in main_stalls], default=0) / 1000,
            "gc_events_landing_in_slow_frames": hit[True], "gc_events_landing_in_ordinary_frames": hit[False],
        })
    info["drills"] = drills
    return groups, info


def print_drills(info, top):
    for g in ("slow", "ordinary"):
        for dr in info["drills"][g]:
            if not dr.n:
                continue
            print(f"--- DRILL {dr.method} in {g.upper()} frames: {dr.n} samples")
            print("  callee chain below it (2 levels):")
            for k, v in dr.callee2.most_common(top):
                print(f"    {v/dr.n*100:5.1f}%  {k}")
            print("  callers above it (3 levels):")
            for k, v in dr.caller2.most_common(top):
                print(f"    {v/dr.n*100:5.1f}%  {k}")
            print("  top frame:")
            for k, v in dr.top.most_common(top):
                print(f"    {v/dr.n*100:5.1f}%  {k}")
            print()


def print_report(groups, info, threshold_ms, top):
    for r in info["runs"]:
        print(f"== {r['run']}: {r['frames_on_route']} frames on the {r['route_s']:.0f} s route, "
              f"{r['samples']} {GAME_THREAD} samples, {r['assigned']} assigned to a frame "
              f"(java period {r['java_period_ms']:.0f} ms)")
        print(f"   GC in the window: {r['gc_events_in_window']} collections, {r['stw_pauses_in_window']} stop-the-world pauses "
              f"totalling {r['stw_pause_ms_in_window']:.1f} ms (max {r['stw_pause_max_ms']:.2f} ms); "
              f"{r['game_thread_stalls_in_window']} allocation stalls on {GAME_THREAD} totalling {r['game_thread_stall_ms']:.1f} ms "
              f"(max {r['game_thread_stall_max_ms']:.1f} ms); pauses+stalls landing in slow frames: "
              f"{r['gc_events_landing_in_slow_frames']}, in ordinary frames: {r['gc_events_landing_in_ordinary_frames']}")
    print(f"dropped: {info['dropped_boundary']} samples within {BOUNDARY_US/1000:.1f} ms of a frame boundary, "
          f"{info['outside_window']} outside the route window\n")
    for name, title in (("slow", f"SLOW frames (>= {threshold_ms:g} ms)"), ("ordinary", f"ORDINARY frames (< {threshold_ms:g} ms)"),
                        ("spike33", "SPIKES >= 33 ms"), ("spike50", "SPIKES >= 50 ms")):
        g = groups[name]
        if g.frames == 0:
            print(f"--- {title}: no frames\n")
            continue
        cov = g.sample_us / g.frame_us if g.frame_us else 0
        print(f"--- {title}: {g.frames} frames, {g.frame_us/1000:.0f} ms total; {g.n} samples "
              f"({g.java} java, {g.native} native) ~ {g.sample_us/1000:.0f} ms sampled = {cov*100:.0f}% of frame time")
        if g.n == 0:
            print()
            continue
        print("  by section (child of GameWindow.frameStep):")
        for k, v in g.section.most_common(8):
            print(f"    {v/g.n*100:5.1f}%  {short(k)}")
        print("  inclusive markers (stack contains):")
        for label, _ in MARKERS:
            v = g.marker.get(label, 0)
            if v:
                print(f"    {v/g.n*100:5.1f}%  {label}")
        print(f"  by first game frame (top {top}):")
        for k, v in g.game.most_common(top):
            print(f"    {v/g.n*100:5.1f}%  {k}")
        print(f"  by top frame (top {top}):")
        for k, v in g.top.most_common(top):
            print(f"    {v/g.n*100:5.1f}%  {k}")
        print()
    # where the extra time of a slow frame goes: sampled ms per frame, slow minus ordinary, per section and marker.
    # Sampled ms per frame is scaled by the group's coverage so the totals match the measured frame time.
    sl, od = groups["slow"], groups["ordinary"]
    if sl.frames and od.frames and sl.n and od.n:
        extra = sl.frame_us / sl.frames - od.frame_us / od.frames
        scale_s = sl.frame_us / sl.sample_us if sl.sample_us else 0
        scale_o = od.frame_us / od.sample_us if od.sample_us else 0
        print(f"--- EXTRA TIME of a slow frame: {extra/1000:.1f} ms over an ordinary one "
              f"({sl.frame_us/sl.frames/1000:.1f} vs {od.frame_us/od.frames/1000:.1f} ms), by section then by inclusive marker "
              f"(ms/frame slow minus ordinary, share of the extra):")
        rows = []
        for k in set(sl.section) | set(od.section):
            d = (sl.section_us[k] * scale_s / sl.frames - od.section_us[k] * scale_o / od.frames)
            rows.append((d, short(k), sl.section_us[k] * scale_s / sl.frames, od.section_us[k] * scale_o / od.frames))
        for d, k, a, b in sorted(rows, reverse=True)[:8]:
            print(f"    {d/1000:+6.2f} ms {d/extra*100:5.1f}%  {k}   (slow {a/1000:.2f} / ordinary {b/1000:.2f})")
        print("  markers:")
        rows = []
        for label, _ in MARKERS:
            if sl.marker[label] or od.marker[label]:
                a = sl.marker_us[label] * scale_s / sl.frames
                b = od.marker_us[label] * scale_o / od.frames
                rows.append((a - b, label, a, b))
        for d, k, a, b in sorted(rows, reverse=True):
            print(f"    {d/1000:+6.2f} ms {d/extra*100:5.1f}%  {k.strip()}   (slow {a/1000:.2f} / ordinary {b/1000:.2f})")
        print()


def to_json(groups, info, threshold_ms):
    out = {"threshold_ms": threshold_ms, "info": info, "groups": {}}
    for name, g in groups.items():
        out["groups"][name] = {
            "frames": g.frames, "frame_ms": g.frame_us / 1000, "samples": g.n, "java": g.java, "native": g.native,
            "sampled_ms": g.sample_us / 1000,
            "section": g.section.most_common(20), "markers": g.marker.most_common(),
            "section_ms_per_frame": {short(k): v / 1000 / g.frames * (g.frame_us / g.sample_us if g.sample_us else 0) for k, v in g.section_us.items()} if g.frames else {},
            "marker_ms_per_frame": {k.strip(): v / 1000 / g.frames * (g.frame_us / g.sample_us if g.sample_us else 0) for k, v in g.marker_us.items()} if g.frames else {},
            "first_game_frame": g.game.most_common(40), "top_frame": g.top.most_common(40),
        }
    return out


if __name__ == "__main__":
    args = sys.argv[1:]
    threshold, top, out_json, check = 20.0, 20, None, False
    after_mark = None
    jp, np_ = 10.0, 20.0
    runs, drill = [], []
    i = 0
    while i < len(args):
        a = args[i]
        if a == "--threshold-ms":
            threshold = float(args[i + 1]); i += 2
        elif a == "--after-mark":
            pfx, nf = args[i + 1].rsplit(":", 1)
            after_mark = (pfx, int(nf)); i += 2
        elif a == "--top":
            top = int(args[i + 1]); i += 2
        elif a == "--json":
            out_json = args[i + 1]; i += 2
        elif a == "--java-period-ms":
            jp = float(args[i + 1]); i += 2
        elif a == "--native-period-ms":
            np_ = float(args[i + 1]); i += 2
        elif a == "--check-anchors":
            check = True; i += 1
        elif a == "--drill":
            drill.extend(args[i + 1].split(",")); i += 2
        elif a == "--thread":
            GAME_THREAD = args[i + 1]; i += 2   # e.g. main (render thread on the native build); frames are still the game thread's
        else:
            runs.append(a); i += 1
    if check:
        ok = all([check_anchors(r) for r in runs])
        sys.exit(0 if ok else 1)
    groups, info = attribute(runs, threshold, jp, np_, drill, after_mark)
    print_report(groups, info, threshold, top)
    print_drills(info, top)
    info.pop("drills")
    if out_json:
        Path(out_json).write_text(json.dumps(to_json(groups, info, threshold), indent=1))
