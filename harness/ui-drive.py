#!/usr/bin/env python3
"""Drive the game's own UI with OCR + TypeSafe (Jev) instead of an LLM looking at screenshots.

  harness/ui-drive.py workshop --notes "Release <commit> (game revision <rev>). ..." [--dry-run]
  harness/ui-drive.py step "<expected screen>" "<control to click>" [--screen shot.png] [--dry-run]
  harness/ui-drive.py read [--screen shot.png]          # OCR lines with screen coordinates

Every step is the same loop, ~1.5 s instead of an LLM round trip: screenshot (spectacle) -> the
active window must be the game (xdotool, deterministic; the 2026-09-21 retry typed into the
desktop after a focus loss) -> tesseract lines with boxes -> one Jev request: is the expected
screen up (noul), which line is the control (choice over the OCR lines + none), is an error /
failure text showing (noul) -> press-and-release at the line's centre (the game only hovers on a
plain click), pointer coordinates = screen px / --pointer-scale (1.25 on this KDE/XWayland
desktop). A step with a `fallback` uses the release-windows skill's known coordinates when Jev
finds no line (native dialogs, unreadable buttons) but only when the expected screen is confirmed.
Jev never sees pixels; it sees the OCR text and positions. A step that cannot be confirmed
stops the sequence (exit 2) with the screenshot path printed; nothing is retried blindly.

Workshop sequence (release-windows skill, "Steam Workshop deploy"): main menu -> WORKSHOP ->
Create and update items -> the PZ_Optimization row -> NEXT -> NEXT -> Edit Change Notes -> type
the notes -> ACCEPT -> Upload -> native confirm Ok -> CLOSE -> QUIT -> Yes. Preflight (Steam
session really logged on, no game / run.sh running, workshop.txt staged) stays in the skill; the
upload itself is verified from ~/.local/share/Steam/logs/workshop_log.txt, never from the game.
"""
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from typesafe_client import ask, choice, noul  # noqa: E402

SHOT = Path("/tmp/ui-drive.png")
TESSDATA = Path.home() / ".local/share/tessdata"
WINDOW_TITLE = "Project Zomboid"
WORKSHOP_ID = "3805285544"


def screenshot(path=SHOT):
    subprocess.run(["spectacle", "-b", "-n", "-f", "-o", str(path)], check=True, capture_output=True, timeout=20)
    return path


def active_window():
    try:
        return subprocess.run(["xdotool", "getactivewindow", "getwindowname"], capture_output=True, text=True, timeout=5).stdout.strip()
    except subprocess.SubprocessError:
        return ""


OCR_UP = 2  # the game's UI text is ~20 px tall at 5120x2160: inverted (dark on light) and doubled it reads cleanly


def ocr_lines(png, min_conf=40):
    """OCR'd text lines with their centre in screen pixels: [{id, text, x, y, w, h}]."""
    from PIL import Image, ImageOps
    im = ImageOps.invert(Image.open(png).convert("L"))
    im = im.resize((im.width * OCR_UP, im.height * OCR_UP), Image.LANCZOS)
    prep = Path(str(png) + ".ocr.png")
    im.save(prep)
    env = dict(os.environ, TESSDATA_PREFIX=str(TESSDATA)) if (TESSDATA / "eng.traineddata").exists() else os.environ
    tsv = subprocess.run(["tesseract", str(prep), "-", "-l", "eng", "--psm", "11", "tsv"], env=env,
                         capture_output=True, text=True, check=True).stdout.splitlines()
    hdr = tsv[0].split("\t")
    groups = {}
    for row in tsv[1:]:
        d = dict(zip(hdr, row.split("\t")))
        if d.get("level") != "5" or not d.get("text", "").strip() or float(d["conf"]) < min_conf:
            continue
        key = (d["block_num"], d["par_num"], d["line_num"])
        g = groups.setdefault(key, {"words": [], "x0": 1e9, "y0": 1e9, "x1": 0, "y1": 0})
        l, t, w, h = (int(d[k]) // OCR_UP for k in ("left", "top", "width", "height"))
        g["words"].append(d["text"])
        g["x0"], g["y0"], g["x1"], g["y1"] = min(g["x0"], l), min(g["y0"], t), max(g["x1"], l + w), max(g["y1"], t + h)
    lines = []
    for g in groups.values():
        text = " ".join(g["words"]).strip()
        if len(re.sub(r"[^A-Za-z]", "", text)) < 2:  # overlay numbers, stray glyphs
            continue
        lines.append({"text": text, "x": (g["x0"] + g["x1"]) // 2, "y": (g["y0"] + g["y1"]) // 2,
                      "w": g["x1"] - g["x0"], "h": g["y1"] - g["y0"]})
    lines.sort(key=lambda L: (L["y"], L["x"]))
    for i, L in enumerate(lines):
        L["id"] = f"L{i}"
    return lines


def judge_screen(lines, expected, target, extra=None):
    """One Jev request over the OCR lines: expected screen up?, which line is the control, error showing?"""
    cands = {L["id"]: f"{L['text']!r} at x={L['x']} y={L['y']} ({'left' if L['x'] < 1700 else 'centre' if L['x'] < 3400 else 'right'} "
                     f"{'top' if L['y'] < 720 else 'middle' if L['y'] < 1440 else 'bottom'})" for L in lines[:80]}
    cands["none"] = "no OCR line is that control (it may be unreadable, an icon, or not on this screen)"
    state = {"screen_lines": [f"{L['id']}: {L['text']}  (x={L['x']}, y={L['y']})" for L in lines[:80]],
             "screen_size": "5120x2160, y grows downwards; the top-left corner holds a performance overlay whose lines are not controls",
             "expected_screen": expected, "control_to_click": target}
    if extra:
        state.update(extra)
    q = {"on_screen": noul("Judging only from `screen_lines` (OCR of the current screen, may contain small misreads), is `expected_screen` what is showing?"),
         "target": choice({"question": "Which line is the control `control_to_click`? OCR may drop or swap a letter; a button label matches when the "
                                       "words are recognisably the same. Pick the line that IS the control, not a heading or a sentence that mentions it."},
                          cands),
         "error": noul("Does `screen_lines` contain an error or failure message (failed, error, invalid, no connection, could not) about the current action?")}
    return ask(state, q)


def press(x, y, scale, hold=0.15):
    px, py = int(x / scale), int(y / scale)
    subprocess.run(["xdotool", "mousemove", str(px), str(py)], check=True)
    time.sleep(0.2)
    subprocess.run(["xdotool", "mousedown", "1"], check=True)
    time.sleep(hold)
    subprocess.run(["xdotool", "mouseup", "1"], check=True)


def run_step(step, opts, screen=None):
    """Screenshot -> focus -> OCR -> Jev -> click. Returns (ok, detail)."""
    name, expected, target = step["name"], step["expect"], step.get("click")
    t0 = time.time()
    png = Path(screen) if screen else screenshot()
    if not screen and not opts["no_focus"]:
        title = active_window()
        if WINDOW_TITLE.lower() not in title.lower():
            return False, f"active window is {title!r}, not the game; no click sent"
    lines = ocr_lines(png)
    ans = judge_screen(lines, expected, target or "(nothing: this step only checks the screen)", step.get("extra"))
    on, err = ans["on_screen"]["noul"], ans["error"]["noul"]
    tgt = ans["target"]
    pick = next((L for L in lines if L["id"] == tgt["choice"]), None)
    detail = (f"[{name}] screen {on:.2f} error {err:.2f} target {tgt['choice']} {tgt['confidence']:.2f}"
              + (f" -> {pick['text']!r} ({pick['x']},{pick['y']})" if pick else "") + f"  ({time.time() - t0:.1f}s, {len(lines)} lines)")
    if err >= 0.6:
        return False, detail + "\n  an error text is showing: " + "; ".join(L["text"] for L in lines if re.search(r"fail|error|invalid|connect", L["text"], re.I))[:300]
    strong_target = pick is not None and tgt["confidence"] >= 0.9
    if on < opts["screen_threshold"] and not (on >= 0.35 and strong_target):
        # a 0.9+ match of the very control we expect corroborates a hesitant screen judgment; a
        # screen judged clearly wrong (the negatives score ~0.02) never clicks
        return False, detail + f"\n  expected screen not confirmed (need >= {opts['screen_threshold']}); screenshot {png}"
    if not target:
        if step.get("type_at") and not opts["dry_run"]:
            press(*step["type_at"], opts["scale"])
        return True, detail
    if pick and tgt["confidence"] >= opts["target_threshold"]:
        x, y = pick["x"], pick["y"]
    elif step.get("fallback"):
        x, y = step["fallback"]
        detail += f"\n  no readable control, using the skill's coordinates ({x},{y})"
    else:
        return False, detail + "\n  control not found on this screen; stopping"
    if opts["dry_run"]:
        return True, detail + f"\n  dry run: would press at screen ({x},{y}) = pointer ({int(x / opts['scale'])},{int(y / opts['scale'])})"
    press(x, y, opts["scale"])
    return True, detail


def type_text(text, opts):
    if opts["dry_run"]:
        print(f"  dry run: would type {len(text)} chars")
        return
    subprocess.run(["xdotool", "type", "--delay", "12", text], check=True)


def workshop_steps(notes):
    """Expectations are written the way the OCR sees the screen (the title line at the top centre,
    the button labels); fallbacks are the release-windows table's 1280-wide coordinates x 4."""
    return [
        {"name": "main menu", "expect": "the main menu: a column of CONTINUE, LOAD, SOLO, MULTIPLAYER, HOST, OPTIONS, MODS, WORKSHOP, CREDITS, QUIT on the left", "click": "WORKSHOP", "fallback": (628, 1868)},
        {"name": "workshop menu", "expect": "title 'Steam Workshop' with the buttons 'Open Steam Overlay to Spiffo's Workshop', 'Open Steam Overlay to items I created', 'Create and update items' and BACK", "click": "Create and update items", "fallback": (2560, 892)},
        {"name": "item row", "expect": "title 'Choose item directory' with BACK and NEXT at the bottom (the item list sits top-left under the performance overlay, so its rows are usually missing from the OCR)", "click": "the PZ_Optimization row of the item list", "fallback": (432, 296)},
        {"name": "choose directory", "expect": "title 'Choose item directory' with BACK and NEXT at the bottom", "click": "NEXT", "fallback": (4796, 2028)},
        {"name": "item details", "expect": "title 'Edit item details' with Title:, Preview image:, Description:, Tags: fields and NEXT at the bottom", "click": "NEXT", "fallback": (4796, 2028)},
        {"name": "prepare to publish", "expect": "title 'Prepare to publish item' with Title:, 'Workshop ID:', the buttons 'Edit Change Notes' and 'Upload to Steam Workshop now!' and the workshop terms line", "click": "Edit Change Notes", "fallback": (2560, 1200)},
        {"name": "change notes box", "expect": "title 'Edit Change Notes' with CANCEL and ACCEPT at the bottom (the empty text box itself has no OCR text)", "click": None, "then_type": notes, "type_at": (2000, 750)},
        {"name": "accept notes", "expect": "title 'Edit Change Notes' with the typed change-notes text and CANCEL / ACCEPT at the bottom", "click": "ACCEPT", "fallback": (2624, 2012)},
        {"name": "upload", "expect": "title 'Prepare to publish item' with 'Edit Change Notes' and 'Upload to Steam Workshop now!'", "click": "Upload to Steam Workshop now!", "fallback": (2560, 1272)},
        {"name": "confirm", "expect": "a native dialog with 'Steam Workshop upload requested' / a WARNING line about a popup box, and Ok / Cancel buttons, over the 'Prepare to publish item' screen", "click": "Ok", "fallback": (2984, 1204), "wait_before": 1.0},
        {"name": "publishing log", "expect": "title 'Publishing item to Steam Workshop' with a CLOSE button at the bottom (the button appears only when the upload has finished, the log lines above it OCR as noise)", "click": "CLOSE", "wait_before": 6.0, "poll": 30},
        {"name": "quit", "expect": "the main menu: a column of CONTINUE, LOAD, SOLO, MULTIPLAYER, HOST, OPTIONS, MODS, WORKSHOP, CREDITS, QUIT on the left", "click": "QUIT", "fallback": (584, 1996)},
        {"name": "quit confirm", "expect": "a 'Quit to desktop?' dialog with Yes and No", "click": "Yes", "fallback": (2504, 1112), "wait_before": 1.0},
    ]


def verify_upload():
    log = Path.home() / ".local/share/Steam/logs/workshop_log.txt"
    if not log.exists():
        return False, "no workshop_log.txt"
    tail = [l for l in log.read_text(errors="replace").splitlines() if WORKSHOP_ID in l][-3:]
    ok = any("Upload finished" in l and ": OK" in l for l in tail)
    return ok, "\n".join(tail)


def main(argv):
    if not argv:
        sys.exit(__doc__)
    opts = {"dry_run": "--dry-run" in argv, "no_focus": "--no-focus-check" in argv, "scale": 1.25,
            "screen_threshold": 0.6, "target_threshold": 0.5}
    screen = None
    notes = None
    args = []
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--screen":
            screen = argv[i + 1]; i += 2
        elif a == "--notes":
            notes = argv[i + 1]; i += 2
        elif a == "--pointer-scale":
            opts["scale"] = float(argv[i + 1]); i += 2
        elif a.startswith("--"):
            i += 1
        else:
            args.append(a); i += 1
    cmd = args[0]
    if cmd == "read":
        for L in ocr_lines(Path(screen) if screen else screenshot()):
            print(f"{L['id']:5s} ({L['x']:5d},{L['y']:5d}) {L['text']}")
        return 0
    if cmd == "step":
        ok, detail = run_step({"name": "step", "expect": args[1], "click": args[2] if len(args) > 2 else None}, opts, screen)
        print(detail)
        return 0 if ok else 2
    if cmd == "workshop":
        if not notes:
            sys.exit("workshop needs --notes")
        for step in workshop_steps(notes):
            if step.get("wait_before") and not screen:
                time.sleep(step["wait_before"])
            deadline = time.time() + step.get("poll", 0)
            while True:
                ok, detail = run_step(step, opts, screen)
                print(detail)
                if ok or time.time() >= deadline or screen:
                    break
                time.sleep(2)
            if not ok:
                return 2
            if step.get("then_type"):
                time.sleep(0.3)
                type_text(step["then_type"], opts)
            time.sleep(0.8 if not screen else 0)
        if opts["dry_run"]:
            return 0
        time.sleep(2)
        ok, tail = verify_upload()
        print(("upload OK:\n" if ok else "upload NOT confirmed in workshop_log.txt:\n") + tail)
        return 0 if ok else 3
    sys.exit(__doc__)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
