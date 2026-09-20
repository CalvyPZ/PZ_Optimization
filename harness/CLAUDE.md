# harness/

Hands-off game runs and their analysis. Everything here is driven by `run.sh`; the Java side
is `src/pzopt/pzopt/Harness.java` (route driving, zoom, schedule) and `Stats.java` (frame and
chunk logs). Runs land in `harness/runs/<label>-<timestamp>/` (gitignored); reference numbers
in `harness/baseline/` (committed).

## run.sh

```
harness/run.sh --label <name> [--mode verify|bench|drive|parity] [--flag k=v]... [--prop k=v]...
               [--route-seconds N] [--source-save Mode/Name] [--renderer nvidia|zink]
               [--launcher auto|steam|direct] [--env K=V]... [--option key=value]...
               [--mod ID]... [--vmarg ARG]...
               [--mangohud secs] [--mangohud-config path] [--record] [--no-dashboard] [--no-mangohud]
               [--jfr] [--jfr-period ms] [--jfr-setting event#setting=value] [--game-profiler]
               [--gc g1|zgc] [--lead secs] [--quit-after secs] [--retries N] [--refresh-template]
```

What it does: installs the pzopt-harness Lua mod, creates/points at the bench save
`Saves/Sandbox/pzopt-bench`, writes the flag file the mod reads, launches the game, starts
`sysmon.sh` and the MangoHud log, waits for exit, collects console.txt and every `pzopt-*.out`
into the run dir, restores `latestSave.ini`. `run.opts` records launcher, renderer, flags.

Modes:
- `verify`: nobody presses "Click to Start"; the game sits at the loading screen. Only for
  install smoke checks. Do not use it for HUD, Wayland or input tests.
- `bench`: fixed camera route on the bench save, ~100 s. `--flag turn=90` spins the player facing
  (degrees per second) so the vision cone, lighting cone and cutaways keep changing; the game-thread
  route since 2026-09-20 is `--flag route=S:450 --flag turn=90 --route-seconds 25` (south through
  Rosewood from the bench save, 55 chunks/s), reference runs `gt-q-*`. **Always** `--flag zoom=max` (the
  save's zoom drifts to 1.0; zoom 1.0 is CPU-bound near the 240 cap, zoom 2.5 is the real
  test). Check `zoom=2.5` in `pzopt-bench.out` before comparing.
- `drive`: spawns a car and follows the highway. Default route `--flag route=E:1200`,
  `--route-seconds 90`, cruise 60 km/h. `--flag kmh=193` gives the ~122 km/h cap
  (Base.RaceCar12 maxSpeed 120). At 120 km/h the steering oscillates and leaves the road at
  ~300-400 tiles about every other attempt; check `route complete` in console.txt and retry.
  A/Bs use the 60 km/h route. `--flag vehicle=none` requires a fixture instead of spawning.
- `parity`: captures recalc output per chunk; `parity-gate.sh` compares with
  `baseline/parity-stock.out`.

Flags that must be on every measured run: `--prop instrument=true` (else no
`pzopt-chunks.out` / `pzopt-frames.out` and compare.py crashes), `--flag zoom=max` on bench,
`--no-dashboard` when measuring (the PZDashboard mod fires four collectors every 2.000 s, one
20-28 ms frame). Use the DEFAULT bench save for drive runs; `--source-save
Apocalypse/2026-09-18_12-18-03` starts off the highway and the car crashes.

## Launchers

- `steam` needs the Steam launch options `<repo>/harness/steam-launch.sh %command%`; env vars
  cannot reach a game started by the running client, so run.sh writes
  `~/Zomboid/pzopt-launch.env` per run and the wrapper sources it.
- `direct` runs `projectzomboid.sh` with `-Dzomboid.steam=0`; `auto` (default) picks direct
  when Steam is not running or logged out (a logged-out client silently ignores -applaunch).
  Direct numbers match Steam numbers. run.sh sets `LC_NUMERIC=C` (de_DE locale breaks
  MangoHud's fps_metrics) and has a 120 s start-up watchdog.
- Steam's newer **performance monitor** caps optimized runs at ~160 fps by pinning the GL
  thread. Keep it off in Steam settings; the classic overlay is harmless.
- run.sh refuses when a game process exists or the session is locked (locked screen stalls
  the game at "Creating display").

## Timing and MangoHud log

No default lead since 2026-09-19: the Java harness fixes route start = world ready + settle
(run.sh passes settle=5) and publishes it in `~/Zomboid/pzopt-schedule.out`; run.sh starts the
MangoHud log 3 s earlier over the control socket (abstract unix socket `mangoapp`,
`control=mangoapp` in the conf) with its own python `mh_control` that waits for the greeting.
Do NOT use `mangohudctl` (it hangs up before the accept, log never starts, exit 0). xdotool
Shift_L+F2 is the fallback; Shift_R+F9 resets fps metrics at route start (lost on native
Wayland). `--lead N` restores the fixed clock. `pzopt-logdone` ends the Java linger. Never
poke `@mangoapp` while a run is going. MangoHud's own gpu_load reads 0 for this game; GPU
utilization comes from `sysmon.sh` (nvidia-smi). See `config/CLAUDE.md` for hook details.

## Comparability

Compare runs only with the same: desktop resolution (console.txt "Desktop resolution"),
renderer ("OpenGL version" line: Mesa = Zink, NVIDIA = GL), `zoom=`, `launcher=`, route and
speed, dashboard on/off, display server. `compare.py` warns on opengl/desktop mismatch.
Baselines: `baseline/native/` (native build, use these), `baseline/5120x2160/` and
`baseline/bench-stock-*.json` are Proton + NVIDIA GL from Sep 15 and are dead for frame time
(still fine for chunk latency). Noise floor = spread between the two stock runs; a change is
real above twice that.

## Analysis scripts

| Script | Reads | Gives |
|---|---|---|
| `analyze.py <run>` | pzopt-frames/chunks.out, mangohud CSV, pzopt-overlay.out (in-game overlay log, same shape), sysmon | route-window frame stats, chunk latency, per-thread CPU. First check a run is valid (mangohud + sysmon + threads lines present) |
| `compare.py --baseline <dir> <runs>` | analyze output | deltas vs stock with noise verdict |
| `dashboard.py` | all runs | `docs/benchmark-progress.html`; regenerate after every run |
| `waits.py <run>` | JFR wait events (`--jfr --jfr-setting jdk.JavaMonitorWait#threshold=0ms` etc.) | per-thread blocking sites in the route window |
| `attribute.py` / `sections.py` | JFR samples / GameProfiler recording (`--game-profiler`) | where slow-frame time goes |
| `loadtime.py <runs>` | pzopt-loadtrace.out | load-after-Continue phases side by side |
| `loadsheet.sh <run>` | recording.mp4 + loadtrace | contact sheet around the load |
| `parity.py a b` | pzopt-parity.out | square-by-square recalc diff |
| `readme-chart.py` | named runs | `docs/media/drive-results.svg` |
| `stitch-quad.sh` | four drive recordings | 2:1 quad video (header comment has the launch recipe) |
| `stitch-triple.sh` | three bench recordings (stock settings, optimized 2026-09-19, optimized + game-thread pass) | 2:1 quad video with a results panel; clip starts derived from run.opts launch_epoch and pzopt-schedule.out (recorder starts ~1 s after launch_epoch) |
| `stitch-sbs.sh` | stock + optimized 120 km/h recordings | side-by-side video with live boot/load counters and a hardware panel; header explains the HUD-clock sync. The first ~2.3 s of every capture show the desktop: never start a pane before the game window appears |
| `stitch-sbs-gif.sh` | the stitch-sbs.sh mp4 | two README GIFs under GitHub's 10 MB limit: `-load.gif` (boot + load, real time) and `-drive.gif` (10 s of the route + the result lines) |
| `proton-preflight.sh` | Steam manifests | read-only Proton readiness report |
| `simulate.py` | | streamer queue simulation |

Use `--record` on drive runs and read frames from `recording.mp4` with ffmpeg (tile contact
sheet) before concluding anything about visuals; it is a monitor capture, so the game window
must stay focused and uncovered.

## Windows

`run-win.ps1` (PowerShell) does the run.sh steps a bench needs on a Windows depot: harness mod,
bench save rebuilt from `bench-save/pzopt-bench-template.tar.zst` (Windows tar reads zstd), flag
file, pzopt.properties, Steam launch, a `Get-Counter` + `nvidia-smi` sampler with sysmon.sh's
columns, collection into `harness/runs/`. No MangoHud, JFR or recording. `-Prop`/`-Flag` accept
comma-joined values (`powershell -File` hands the script one string). The Java harness quits at
the route end by itself when no MangoHud is loaded. `analyze.py` runs on the embeddable Python
(no installer needed); `analyze-win.ps1` is a PowerShell fallback. The game pauses on focus loss
(`focusloss=true`), which stops `IsoChunk.update` and with it the frame sampler: keep the window
focused. Reference numbers in `baseline/windows/`, findings in `docs/windows-test.md`.

## Pitfalls that already cost runs

- EXIT trap under `set -e`: a failing last command of an `&&` list aborts the restore.
  `restore()` does `set +e`. The maintainer's original MangoHud.conf was lost that way once.
- Another session's run.sh EXIT trap can fire after you launched behind their game and strip
  your flags (game sits at the main menu). Check for run.sh processes, not just the game.
- Do not add `no_display` or long lingers: The maintainer wants the overlay visible and the game to
  close by itself after a run.

`native-threads.sh <out> [start-after] [window]`: run beside `run.sh`; snapshots every native thread's CPU (driver workers, MangoHud, JIT) and the GL-related environment of the live game process. `pzopt-threads.out` only sees Java threads.
