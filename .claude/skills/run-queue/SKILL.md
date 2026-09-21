---
name: run-queue
description: Schedule any game run (bench, drive, preset, verify, multiplayer, Workshop upload, showcase recording) or media encode / stitch through harness/queue.sh on the desktop or one of the laptops (flip, dell, mac) and read its result file. Use whenever a run has to happen - instead of launching run.sh, mp/run.sh, ui-drive.py or an ssh wrapper by hand - and for every ffmpeg / encode-av1-hdr.sh / stitch-*.sh job (they must never overlap a run); also when asked what the queue, a machine or a session is doing.
---

# The run queue

One queue for every session and every computer. You submit; a worker runs your job when the
machine is free; the result lands in a file; you get an event when it ends or when your machine
disconnects. Never launch `harness/run.sh`, `harness/mp/run.sh`, `harness/showcase-record.sh`,
`harness/ui-drive.py workshop`, an ssh wrapper, or an encode (`ffmpeg`, `harness/encode-av1-hdr.sh`,
`harness/stitch-*.sh`) yourself while the queue exists: an encode running beside a benchmark corrupts
its readings and the benchmark stretches the encode.

## 1. Where am I, what is going on

```bash
harness/queue.sh machines        # every machine: connected / disconnected / local, queue depth, running job, bound sessions,
                                 # and "this session: <id> -> <machine>" (your affinity)
harness/queue.sh list            # every job: machine, status, label, note (blocked reason / exit + Jev verdict)
harness/queue.sh events          # the last 20 events for this session (jobs ended, machine dropped / came back)
```

Your session id is `$CLAUDE_CODE_SESSION_ID` (automatic). The first `submit` binds the session to a
machine (default `desktop`); after that **every run of this session goes to that machine**. Moving needs
`submit --rebind --machine <m>` or `bind <m>`; do it only when the user asks for another computer.

## 2. Submit

Always say in the message that a run is queued and on which machine. Then:

```bash
# desktop bench / preset / drive (the run.sh arguments go after --, unchanged from the bench-run skill)
harness/queue.sh submit run -- --label <name> --mode bench --flag zoom=max --prop instrument=true --no-dashboard
harness/queue.sh submit run -- --label <name> --preset storm --prop instrument=true --no-dashboard
harness/queue.sh submit run -- --label <name> --mode drive --flag route=E:1200 --route-seconds 90 --prop instrument=true --no-dashboard --record

# with a goal for Jev (uplift verdict against earlier runs / a baseline json) and visual parity
harness/queue.sh submit run --goal "puddleVbo halves storm frame time" --against storm-stock-1 --parity-against storm-stock-1 \
    -- --label <name> --preset storm --record --prop instrument=true --no-dashboard

# a laptop (binds this session to it on first use); --install opt ships this checkout's build/classes there first
harness/queue.sh submit run --machine flip --install opt -- --label <name> --mode bench --flag zoom=max --prop instrument=true
harness/queue.sh submit run --machine dell -- --label <name> --mode drive --flag route=E:1200 --route-seconds 90 --prop instrument=true
harness/queue.sh submit run --machine mac  -- --label <name> --mode drive --flag route=E:1200 --prop instrument=true

# stock comparison on the desktop: uninstall for the job, reinstalled once the desktop queue drains
harness/queue.sh submit run --install stock -- --label <name>-stock ...      # or keep the classes and pass --prop enabled=false

# desktop-only kinds
harness/queue.sh submit mp -- <label> [stock]                                   # 120 km/h drive against the stock dedicated server
harness/queue.sh submit workshop --notes "Release <commit> (game revision <rev>). ..." -- --tag win-<rev>-<commit>
harness/queue.sh submit cmd --label <name> -- harness/showcase-record.sh storm120 opt

# media: every encode, re-encode, stitch or GIF render (desktop only; shares the FIFO with the runs, so it
# can never overlap one; runs go first, encodes fill the gaps; waits for any ffmpeg / run started outside the queue)
harness/queue.sh submit media --label <name> -- harness/encode-av1-hdr.sh <in.mp4> docs/media/<name>.mp4 [width]
harness/queue.sh submit media --label <name> -- harness/stitch-storm-sbs.sh
harness/queue.sh submit media --label <name> --out docs/media/<name>.mp4 --out docs/media/<name>.jpg -- python3 harness/stitch-showcase.py
harness/queue.sh submit media --label <name> -- ffmpeg -y -i <in> ... docs/media/<out>.gif
```
`--out` names the files to probe when the command does not list them; otherwise every video / image path
in the command that the job wrote is probed.

Options before `--`: `--machine`, `--rebind`, `--install opt|stock|keep|<repo>`, `--goal "..."`,
`--against <run|baseline.json>` (repeatable), `--parity-against <recorded run>`, `--cap N`, `--wait`,
`--notes` (workshop), `--label` (cmd, media), `--out <file>` (media, repeatable). `submit` prints the job id, its dir, the `result.txt` path and how
many jobs are ahead on that machine; it starts the worker and the connection monitor when they are not
running. A disconnected laptop still accepts the job; it waits (`blocked: <m> disconnected`) and runs
when the machine is back.

## 3. Wait for it without polling

Pick one:

- `harness/queue.sh submit ... --wait` — blocks, prints `result.txt`, exit code = the job's (0 = done).
- `harness/queue.sh watch --exit-on any` in a **background Bash** — returns the moment something happens
  to this session: exit 0 = one of your jobs ended (the line names it and its result path), exit 3 = your
  machine disconnected. Re-run it after each wake-up.
- `Monitor` the job's `status` file (`pending → running → done|failed|cancelled`) or
  `~/.local/state/pzopt-queue/sessions/<your id>/events`.

Never `sleep`-poll `list` in a loop; never touch `~/Zomboid`, the game dir or the laptops while a job
of yours is `running`.

## 4. Read the result

```bash
harness/queue.sh result <id|label>       # result.txt
harness/queue.sh log <id|label> [-f]     # everything the job printed (run.sh output, rsync, wrapper)
harness/queue.sh status <id|label>       # the job spec, blocked reason, pid, exit
```

`result.txt` for a `run`: `status= exit=`, `run_dir=` (a laptop run is collected to
`harness/runs/<machine>-<label>-<ts>/`), `route_complete=` (must be ≥ 1 for bench / drive),
resolution / OpenGL lines, `bench:` (`zoom=2.5` on bench), `opts:` (crashed, launcher, machine), the
whole `analyze.py` output, console errors, then Jev's `judge.py` block ending in

```
verdict=achieved|partial|no_change|regressed|invalid confidence= goal_met= tail_regressed= setup_matches_goal= headroom_finding=
parity=<kind> confidence= parity_maintained= look_needed=      # only with --parity-against
```

Report the verdict line plus the frame-tail and utilization numbers (the objective: consistent frame
time, hardware saturated unless pegged at the cap). `invalid` with a `run_dir` means the setup or logs
do not answer the goal (read the judge block); `invalid (no run)` means run.sh never produced a run dir
(read `log`). `exit=70` = the connection to the laptop was lost mid-run. For `mp`: the mp summary,
`window.py <run>:27` and the same verdict. For `workshop`: the `workshop_log.txt` tail (`Upload
finished ... : OK`), the change-notes page's newest entry, the Jev-judged UI steps; a failure has
`failure.png`. For `media`: an `output=` line per file with size, duration, `codec= pix_fmt= transfer=
primaries= hdr_av1_ok=yes|no`; a `WARNING` under any video that is not AV1 10-bit PQ/BT.2020 — never publish
that file under `docs/media/`, re-encode it with `harness/encode-av1-hdr.sh` (queued) first. Afterwards
`analyze-run` as usual (compare.py, dashboard).

## 5. Events: a machine dropped or came back

An event line `<ts> <machine>: disconnected (...)` reaches every session bound to the machine or with a
job queued there (and `notify-send` on the desktop). What to do: tell the user which machine dropped and
which of your jobs are waiting (`list` shows `blocked: <m> disconnected`); do not cancel them — they
resume on `connected again`. A job that was running when the link dropped is `failed` with `exit=70`:
resubmit it once the machine is back. Detection is ~2 s for a closed connection, ≤ 15 s for a silent
loss (ssh keepalive 5 s × 2).

## 6. Control

```bash
harness/queue.sh cancel <id|label>       # pending: dropped; running: SIGTERM to its process group (run.sh restores latestSave.ini)
harness/queue.sh start [machine...]      # monitor + workers (transient user units pzq-monitor, pzq-<m>); submit does this itself
harness/queue.sh stop [--now]            # after the current jobs / interrupt them; use only when the user asks
```

State lives outside the repo in `~/.local/state/pzopt-queue/` (`jobs/`, `machines/<m>/state`,
`sessions/<sid>/`, `events.log`, `worker-<m>.log`, `monitor.log`). Machines are defined in
`harness/queue/machines.conf` (host, key, checkout, PZ_ROOT, run_args, display env, inhibit, installer);
edit it when a laptop's address or path changes. Details: `harness/CLAUDE.md` "Run queue".

## Do not

- Run `harness/run.sh` (or an ssh wrapper to a laptop) directly while the queue exists, or launch behind a
  running job. The old preflight (`pgrep`, peer messages) is for the queue's own worker now.
- Start an `ffmpeg` / stitch / GIF encode outside the queue: a benchmark may be running or about to start
  on this desktop, and both would read wrong. Only short probes (`ffprobe`, a single frame extraction) are
  fine inline.
- Submit a run for another session's machine, or rebind without being asked.
- `stop --now` a queue with other sessions' jobs running.
- Poll `list` in a loop; use `--wait`, `watch --exit-on any` or `Monitor`.
- Trust an in-game "finished" for the Workshop upload; the result's `workshop_log.txt` line is the proof.
