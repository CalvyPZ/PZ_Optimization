---
name: mangohud-overlay
description: Diagnose or change how the MangoHud HUD and CSV log attach to the game across launchers (Steam, direct), renderers (NVIDIA GL, Zink) and display servers (X11, native Wayland). Use when a run has no mangohud CSV, no overlay, or the log window is wrong.
---

# MangoHud on this game

Hook matrix (verified 2026-09-19):

| Renderer | Launcher | What hooks |
|---|---|---|
| NVIDIA GL | steam | `steam-launch.sh` preloads `libMangoHud_opengl.so` host-side; Steam's gameoverlayrenderer is already ahead of it |
| NVIDIA GL | direct | run.sh preloads `ubuntu12_64/gameoverlayrenderer.so` then `libMangoHud_opengl.so` (glvnd + LWJGL dlsym resolution otherwise bypasses the hook) |
| NVIDIA GL | native Wayland | the Display override calls the preloaded lib's `eglSwapBuffers` (FFM downcall) |
| Zink | any | Vulkan layer, `MANGOHUD=1` only; adding the GL preload kills the game at "VSync: OFF" |

Never `PZOPT_MANGOHUD_LIB=shim` (dlsym shim deadlocks the JNI launcher). Quick hook tests:
`tools/GlfwSwapProbe.java` under a launcher not named `java`.

Log window: run.sh reads `~/Zomboid/pzopt-schedule.out` (route start = world ready + settle)
and drives the log over the abstract socket `mangoapp` with its python `mh_control`, which
waits for the game's greeting before sending `:logging=1;` / `:logging=0;`. `mangohudctl`
does not wait and the message is dropped: never use it. Fallback is xdotool Shift_L+F2 (X11
only). CSV appears only if logging stops while the game is alive. Do not touch the socket
during someone else's run.

Checks when a CSV is missing: `launcher=` and `renderer=` in run.opts, `LC_NUMERIC=C`,
console.txt "OpenGL version" (Mesa = Zink), `ls harness/runs/<run>/*.csv`, whether the route
ever started (`route` lines in console.txt), whether verify mode was used (nothing starts).
HUD appearance knobs: `config/CLAUDE.md`. Diego wants the overlay visible and the game to close
itself; no `no_display`.
