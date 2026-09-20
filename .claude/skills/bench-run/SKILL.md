---
name: bench-run
description: Launch a hands-off Project Zomboid measurement run (bench, drive, parity, verify) with harness/run.sh, respecting the shared-machine and run-etiquette rules. Use for any request to benchmark, measure, A/B, smoke-test or reproduce a frame-time or chunk-latency number.
---

# Launch a measurement run

## 1. Preflight (every time)

```bash
pgrep -fa '[P]rojectZomboid64'      # game already running?
pgrep -fa '[h]arness/run.sh'        # another session mid-run? (exclude your own)
loginctl show-session $XDG_SESSION_ID -p LockedHint   # locked screen stalls the game
scripts/pzopt.sh status             # which classes are installed (stock run needs uninstall)
```
If a peer session is busy, use ListAgents / SendMessage and wait; never launch behind their
game. If someone is editing `harness/run.sh`, copy it to `harness/.run-snapshot.sh` and launch
that. Keep the Steam performance monitor off (caps at ~160 fps).

## 2. Announce

Tell the user in the message before the launch: a run is starting, leave the game alone (no
clicking Continue, no launching, no screen lock). One run at a time, never a batch.

## 3. Launch

Bench (camera route, max zoom):
```bash
harness/run.sh --label <name> --mode bench --flag zoom=max --prop instrument=true --no-dashboard
```
Drive, 60 km/h A/B route (the comparable one):
```bash
harness/run.sh --label <name> --mode drive --flag route=E:1200 --route-seconds 90 \
  --prop instrument=true --no-dashboard --record
```
Add `--flag kmh=193` for the ~122 km/h route (`--route-seconds 60`, expect retries), `--jfr`
plus `--jfr-setting jdk.JavaMonitorWait#threshold=0ms --jfr-setting jdk.ThreadPark#threshold=0ms`
for wait analysis, `--renderer zink` for the Zink A/B, `--env JAVA_TOOL_OPTIONS=-Dzomboid.wayland=1`
for native Wayland, `--option uiRenderOffscreen=true` for the offscreen UI, `--gc g1` for a GC A/B.
Stock runs: `scripts/pzopt.sh uninstall` first, reinstall after. `--launcher auto` is fine.
Use the DEFAULT bench save for drive runs (no `--source-save`).

## 4. Validate before trusting

```bash
python3 harness/analyze.py harness/runs/<label>-*/
grep -E 'route complete|Desktop resolution|OpenGL version' harness/runs/<label>-*/console.txt
grep zoom= harness/runs/<label>-*/pzopt-bench.out
```
Valid = mangohud, sysmon and thread lines present, `route complete`, zoom 2.5 on bench,
resolution/renderer match the baseline. On drive runs look at `recording.mp4` frames (ffmpeg
tile sheet) before concluding anything. Then `python3 harness/dashboard.py`.

## Do not

- edit run.sh mid-run; pkill with a self-matching pattern; use verify mode for anything
  interactive; compare across resolution / renderer / zoom / launcher / dashboard state;
  use Proton-era baselines for frame time; add `no_display` or long lingers.
