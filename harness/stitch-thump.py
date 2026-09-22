#!/usr/bin/env python3
"""Off-screen thump-burst video (2026-09-22 night): the ThumpRig scene (harness flag thump=N) with the bug restored
(--prop devActionEvalUnitMultiplier=true, left) beside the fix (right).

Three zombies thump a locked door next to the player; the player teleports 35 tiles away (the zombies are scene-culled
and drop to the SIXTEENTH simulation level) and comes back 20 s later. Each panel is a crop of the 5120x2160 capture
around the player, cut from 2 s before the spawn; the per-second rig lines of each console (strikes in the last second,
door health, the zombies' simulation level) are burned in under it, the phase caption above. Left audio = before,
right = after. AV1 10-bit PQ / BT.2020 like every docs/media video.

Runs: harness/run.sh --mode bench --flag start=7983,11247 --flag time_of_day=12 --flag see_all=true --flag route=S:1
      --flag speed=1 --flag hold=40 --flag zoom=0.5 --flag zombies=off --flag thump=3 --flag thump_at=3
      --flag thump_leave=8 --flag thump_dist=35 --flag thump_reveal=28 --route-seconds 1 --record --no-dashboard
      --prop instrument=true --option frameRate=240 --option uncappedFPS=false --prop uncappedFps=false
      [--prop devActionEvalUnitMultiplier=true]

Usage: harness/stitch-thump.py <before-label> <after-label> <out.mp4>
Env:   PRE (2) seconds before the spawn, LEN (36), OFF_BEFORE / OFF_AFTER extra seconds added to a clip start
"""
import glob
import os
import re
import subprocess
import sys

os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
before, after, out = sys.argv[1:4]
PRE = float(os.environ.get("PRE", "2"))
LEN = float(os.environ.get("LEN", "36"))
FONT = "/usr/share/fonts/noto/NotoSans-Bold.ttf"
if not os.path.exists(FONT):
    FONT = subprocess.check_output(["fc-match", "-f", "%{file}", "DejaVu Sans:bold"], text=True)

LINE = re.compile(r"harness: thump t=([\d.]+) epoch_ms=(\d+) strikes=(\d+) last_s=(\d+) door_hp=(-?\d+) destroyed=(\w+) sim=(\w+)")


def run_dir(label):
    return sorted(glob.glob(f"harness/runs/{label}-*"))[-1]


def rig(d):
    rows = []
    broken = None
    leave = reveal = None
    with open(f"{d}/console.txt", errors="replace") as f:
        for line in f:
            m = LINE.search(line)
            if m:
                rows.append((float(m[1]), int(m[2]), int(m[3]), int(m[4]), int(m[5]), m[6] == "true", m[7]))
            elif "harness: thump: leave" in line and rows:
                leave = rows[-1][0]
            elif "harness: thump: reveal" in line and rows:
                reveal = rows[-1][0]
            elif "harness: thump: door broken" in line:
                broken = int(re.search(r"broken (\d+) ms", line)[1]) / 1000.0
    spawn_epoch = rows[0][1] / 1000.0 - rows[0][0]  # t is seconds since the spawn
    return rows, spawn_epoch, leave, reveal, broken


def clip_start(d, spawn_epoch, extra):
    birth = int(subprocess.check_output(["stat", "-c", "%W", f"{d}/recording.mp4"], text=True))
    return round(spawn_epoch - birth - PRE + extra, 2)


B, F = run_dir(before), run_dir(after)
rb, sb, leave_b, reveal_b, broken_b = rig(B)
rf, sf, leave_f, reveal_f, broken_f = rig(F)
cb = clip_start(B, sb, float(os.environ.get("OFF_BEFORE", "0")))
cf = clip_start(F, sf, float(os.environ.get("OFF_AFTER", "0")))
leave = leave_b if leave_b is not None else 8.0
reveal = reveal_b if reveal_b is not None else 28.0
print(f"before {B} from {cb}s (door broken {broken_b}) | after {F} from {cf}s (door broken {broken_f})")

# layout: crop around the player (screen centre) and the door north of them
CX, CY, CWS, CHS = 1600, 300, 2240, 1100
W, PW, PH, TITLE_H, LABEL_H, TXT_H = 3840, 1920, 942, 170, 62, 270
Y1 = TITLE_H
YT = Y1 + PH
H = YT + TXT_H
TXT, DIM, RED, GRN, AMB = "0xb4b4b8", "0x8a8a90", "0xb85c5c", "0x5cb878", "0xb8a05c"


def esc(s):
    return s.replace("\\", "\\\\").replace(":", "\\:").replace("'", "’").replace("%", "\\%")


def dt(text, size, color, x, y, enable=None):
    e = f":enable='{enable}'" if enable else ""
    return f"drawtext=fontfile={FONT}:text='{esc(text)}':fontsize={size}:fontcolor={color}:x={x}:y={y}{e}"


def counters(rows, x0):
    """One drawtext set per rig line, shown from its video time to the next one's."""
    out = []
    for i, (t, _e, strikes, last_s, hp, destroyed, sim) in enumerate(rows):
        a = PRE + t
        b = PRE + rows[i + 1][0] if i + 1 < len(rows) else LEN
        if a >= LEN:
            break
        en = f"gte(t,{a:.3f})*lt(t,{b:.3f})"
        colour = RED if last_s >= 8 else TXT
        door = "BROKEN" if destroyed else f"{hp} / 500"
        out.append(dt(f"strikes in the last second: {last_s}", 40, colour, f"{x0}+({PW}-tw)/2", f"{YT}+28", en))
        out.append(dt(f"door health: {door}    total strikes: {strikes}    zombie sim level: {sim}", 30, DIM, f"{x0}+({PW}-tw)/2", f"{YT}+90", en))
    return out


phase = [
    (0, PRE, "3 zombies spawn at a locked door next to the player"),
    (PRE, PRE + leave, "on screen: full simulation, both builds thump at the same pace"),
    (PRE + leave, PRE + reveal, "the player walks 35 tiles away: the zombies are off screen, one update every 16 frames"),
    (PRE + reveal, LEN, "the player comes back"),
]
draws = [
    dt("Project Zomboid B42  |  zombies thumping a door while off screen  |  240 fps", 54, TXT, "(w-tw)/2", 24),
]
for a, b, text in phase:
    draws.append(dt(text, 38, AMB, "(w-tw)/2", 100, f"gte(t,{a:.3f})*lt(t,{b:.3f})"))
draws += [
    dt("BEFORE  -  deferred postupdate at perObjectMultiplier 1", 40, RED, f"({PW}-tw)/2", f"{Y1}+10"),
    dt("AFTER  -  multiplier = the zombie's simulation frame mod", 40, GRN, f"{PW}+({PW}-tw)/2", f"{Y1}+10"),
]
draws += counters(rb, 0) + counters(rf, PW)
draws.append(dt("ActionEval.flush ran each off-screen zombie's animation 1 frame per update while ThumpState counted strikes over 16 frames: "
                "every strike counted up to 16 times.  Left audio = before, right = after.", 26, DIM, "(w-tw)/2", f"{YT}+190"))

cell = "fps=60,crop={}:{}:{}:{},format=yuv420p10le,scale={}:{}:flags=lanczos,setsar=1".format(CWS, CHS, CX, CY, PW, PH)
filt = f"""
color=c=0x060608:s={W}x{H}:r=60:d={LEN},format=yuv420p10le[bg];
[0:v]{cell}[l];
[1:v]{cell}[r];
[bg][l]overlay=0:{Y1}:shortest=1[b1];
[b1][r]overlay={PW}:{Y1},drawbox=x=0:y={Y1}:w={W}:h={LABEL_H}:color=0x060608@0.85:t=fill,drawbox=x={PW}-2:y={Y1}:w=4:h={PH}:color=0x202026:t=fill[b2];
[b2]{','.join(draws)},setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v];
[0:a]atrim=0:{LEN},pan=mono|c0=0.5*c0+0.5*c1[al];
[1:a]atrim=0:{LEN},pan=mono|c0=0.5*c0+0.5*c1[ar];
[al][ar]join=inputs=2:channel_layout=stereo,loudnorm=I=-16:TP=-1.5:LRA=11,afade=t=out:st={max(0, LEN - 2)}:d=2[a]
"""
os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
subprocess.check_call([
    "ffmpeg", "-hide_banner", "-v", "error", "-y",
    "-ss", str(cb), "-t", str(LEN), "-i", f"{B}/recording.mp4",
    "-ss", str(cf), "-t", str(LEN), "-i", f"{F}/recording.mp4",
    "-filter_complex", filt, "-map", "[v]", "-map", "[a]", "-c:a", "aac", "-b:a", "192k",
    "-c:v", "av1_nvenc", "-preset", "p7", "-tune", "hq", "-rc", "vbr", "-cq", "22", "-b:v", "0", "-maxrate", "80M", "-bufsize", "160M",
    "-pix_fmt", "p010le", "-color_primaries", "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc", "-color_range", "tv",
    "-movflags", "+faststart+write_colr", "-r", "60", out])
poster = out.rsplit(".", 1)[0] + ".jpg"
subprocess.call(["ffmpeg", "-hide_banner", "-v", "error", "-y", "-ss", str(PRE + reveal + 3), "-i", out, "-frames:v", "1", "-vf",
                 "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p",
                 "-q:v", "2", poster])
print(out, poster)
