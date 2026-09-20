# PZ_Optimization

Performance patches for **Project Zomboid Build 42**, on the Java side of the game.
Not a Lua mod: a set of drop-in `.class` files that shadow 25 game classes and remove
the worst stalls from the chunk streamer, the renderer and the loading path.
`projectzomboid.jar` is never modified. Every change has a kill switch, and every
number in this file comes from the hands-off benchmark harness in this repo.

**Target: Build 42.20.4, jar revision `b0bbce05d5`, Windows and Linux.** Both Steam
depots ship the same jar. The overrides refuse to run against any other revision:
they log one line and the game behaves as stock.

Single-player only. Read [Known limitations](#known-limitations) before installing on
a machine you play on.

---

## Contents

1. [Results at a glance](#results-at-a-glance)
2. [Install on Windows](#install-on-windows)
3. [Install on Linux](#install-on-linux)
4. [Settings](#settings) ([in the game menu](#in-the-game-menu), [in a file](#in-a-file))
5. [Uninstall](#uninstall)
6. [After a game update](#after-a-game-update)
7. [How the optimizations work](#how-the-optimizations-work)
8. [Roadmap](#roadmap)
9. [How the install works without touching the jar](#how-the-install-works-without-touching-the-jar)
10. [Known limitations](#known-limitations)
11. [Benchmark harness](#benchmark-harness)
12. [Repository layout](#repository-layout)

---

## Results at a glance

All runs: same save, same car, same 1,200-tile highway route east of Rosewood, max
zoom, 5120x2160. Machine: Ryzen 7 9800X3D, RTX 4090, Crucial T705 NVMe, 32 GB DDR5.
"Stock" is this build with every optimization switched off, which reproduces the
shipped game exactly.

### 120 km/h drive: stock at its 244 fps cap vs optimized uncapped

Full video with sound: https://www.youtube.com/watch?v=6jaipKCG7Po. Native Linux
build, NVIDIA OpenGL, runs `sbs-stock120-1` / `sbs-opt120-uncap-1`, 2026-09-19.
Stock has the in-game limiter at its maximum (244) and every optimization off,
including the boot and load ones.

**Boot and load.** Both games are launched together; the optimized one is in the
world and waiting while stock is still loading:

[![Boot and load, stock vs optimized](docs/media/drive-120kmh-stock-244cap-vs-optimized-uncapped-load.gif)](https://www.youtube.com/watch?v=6jaipKCG7Po)

**The drive.** First 10 s of the route, then the result lines:

[![120 km/h drive, stock at the 244 fps cap vs optimized uncapped](docs/media/drive-120kmh-stock-244cap-vs-optimized-uncapped-drive.gif)](https://www.youtube.com/watch?v=6jaipKCG7Po)

Route window 39 s at about 122 km/h. Both sides fall off the cap here, so the
difference is pure per-frame cost.

| Metric | Stock (244 cap) | Optimized (uncapped) | Change |
|---|---|---|---|
| Boot, launch to main menu | 7.31 s | 6.01 s | -18 % |
| Load, Continue to world ready | 8.74 s | 3.68 s | -58 % |
| fps, mean | 122 | 412 | 3.4x |
| Frame time, mean | 8.2 ms | 2.4 ms | -71 % |
| Frame time, p99 | 18.9 ms | 6.3 ms | -67 % |
| Frame time, p99.9 | 22.6 ms | 11.4 ms | -50 % |
| Worst frame | 53.1 ms | 23.0 ms | -57 % |
| Frames over 33 ms | 1 | 0 | |
| 1 % low fps | 57 | 147 | 2.6x |
| GPU busy | 91 % | 86 % | uncapped, so the GPU stays busy |
| Game CPU (of one core) | 289 % | 347 % | |

The full-length video (396 MB, 3840x1450, 63 s) is too large for the repository;
its YouTube description is in
[`docs/media/drive-120kmh-stock-244cap-vs-optimized-uncapped.youtube.txt`](docs/media/drive-120kmh-stock-244cap-vs-optimized-uncapped.youtube.txt).

### Windows: bench route, 500 fps cap

Windows 11, NVIDIA driver 616.92, game fullscreen at desktop resolution, vsync off,
launched through Steam, zoom 2.5, two stock runs for the noise floor. Details, boot
timings and the procedure are in [docs/windows-test.md](docs/windows-test.md).

| Metric | Stock (best of 2) | Optimized | Change |
|---|---|---|---|
| fps, mean | 122 | 194 | 1.6x |
| Frame time, mean | 8.2 ms | 5.2 ms | -37 % |
| Frame time, p99 | 26.1 ms | 18.5 ms | -29 % |
| Frame time, p99.9 | 36.5 ms | 26.6 ms | -27 % |
| Frames over 33 ms | 30 | 8 | |
| Chunk queue wait, mean | 180 ms | 32 ms | 5.7x |
| GPU busy | 65 to 68 % | 52 % | |

Noise floor between the two stock runs: 0.7 fps, 0.9 ms at p99. The optimized
build is bound by the game thread (93 % of wall) with the GPU at half load. The p99
gap to Linux (18.5 vs 8.3 ms) is the open Windows question; the driver and the cap
are the candidates.

### 60 km/h drive, 240 fps cap (the first video)

[![Stock vs optimized, 60 km/h drive](docs/media/drive-60kmh-stock-vs-optimized.jpg)](docs/media/drive-60kmh-stock-vs-optimized.mp4)

Runs `show-stock-1` / `show-opt-1` (in-game frame sampler); the "at the cap" row
is from MangoHud on the repeat pair `show-stock-2` / `show-opt-2`.

| Metric | Stock | Optimized | Change |
|---|---|---|---|
| Frame time, mean | 8.2 ms | 4.2 ms | 2.0x |
| Frame time, p90 | 14.4 ms | 4.2 ms | 3.4x |
| Frame time, p99 | 18.3 ms | 5.4 ms | 3.4x |
| Frame time, p99.9 | 23.1 ms | 18.4 ms | see note |
| fps, mean | 121 | 238 (capped at 240) | 2.0x |
| Frames sitting at the 240 fps cap | 34 % | 86 % | |
| GPU busy | 92 % | 54 % | half the GPU work |
| Chunk latency, p50 (ask to ready) | 154 ms | 4 to 5 ms | 30x |
| Chunk latency, p99 | 296 ms | 21 ms | 14x |
| Render thread busy | 74 % | 12 % | |

Note: the remaining p99.9 is a Lua `OnTick` burst every 2.00 s from the
PZDashboard mod, not this code. With that mod disabled the same route runs p99.9
at 11.8 ms.

![Stock vs optimized at 60 and 120 km/h: frame time percentiles, GPU busy, chunk latency](docs/media/drive-results.svg)

### Boot and load on a warm cache

Bench save, `harness/loadtime.py`, best measured pair in the load loop
(`docs/plan-instant-load.md`). The first boot after a cache wipe is slower because
the animation and texture-pack caches under `Zomboid/pzopt/` are being written.

| Phase | Stock | Optimized |
|---|---|---|
| Launch to main menu | 7.35 s | 5.00 s |
| Continue to world ready | 6.53 s | 4.03 s |

### Chunk latency, per streamer change

100 s route, `docs/results.md`. Time from the game asking for a chunk to the chunk
being ready for the game thread.

| Variant | p50 | p90 | p99 |
|---|---|---|---|
| Stock | 166 ms | 310 ms | 1024 ms |
| Wake on enqueue only | 20 ms | 184 ms | 811 ms |
| Wake + 4 recalc workers (default) | 9.3 ms | 84 ms | 517 ms |
| Wake + 8 workers | 9.1 ms | 87 ms | 503 ms |

---

## Install on Windows

Nothing is compiled on Windows. One script finds the game, downloads the zip for your
game revision, checks it and unpacks it. Total time: about two minutes.

**You need:** Project Zomboid on Steam, on the **Build 42.20.4** beta (Steam,
right-click the game, Properties, Betas). Nothing else.

### 1. Close the game

The overrides are read when the game starts.

### 2. Download the installer and run it

Open PowerShell (Start menu, type `powershell`) and run:

```powershell
Invoke-WebRequest https://github.com/DiegoVillalobosFlores/PZ_Optimization/releases/latest/download/install.ps1 -OutFile "$env:USERPROFILE\Downloads\install.ps1"
powershell -ExecutionPolicy Bypass -File "$env:USERPROFILE\Downloads\install.ps1"
```

(`install.ps1` is also on the
[release page](https://github.com/DiegoVillalobosFlores/PZ_Optimization/releases).)

The script locates the game through Steam's library list (pass `-Dir <folder>` if it
cannot), reads the game revision from the jar, downloads `pzopt-<revision>-classes.zip`
from the matching release, refuses to run if the launcher classpath would not load
loose classes or if any file it would write already exists, unpacks the zip, and
records every file in `pzopt-installed.txt` so the uninstall is exact. It never
touches `projectzomboid.jar`.

Offline, or to use a zip you already have, download `pzopt-b0bbce05d5-classes.zip`
(543 KB) from the release page and pass it:

```powershell
powershell -ExecutionPolicy Bypass -File "$env:USERPROFILE\Downloads\install.ps1" -Zip "$env:USERPROFILE\Downloads\pzopt-b0bbce05d5-classes.zip"
```

The revision in the file name must match your game (Build 42.20.4 is `b0bbce05d5`);
the script refuses a zip for another revision, and if one is unpacked by hand the
classes disable themselves at start-up.

`install.ps1 -Status` lists what is installed and whether any file changed;
`install.ps1 -Uninstall` removes exactly those files. Without the script,
`Expand-Archive` of the zip into the game folder (no `-Force`) is the same install,
and the [uninstall](#uninstall) block below is the same removal.

### 3. Launch from Steam and check the log

Start the game from Steam as usual. The first boot is slower than later ones:
the animation-clip cache and texture-pack index under `%USERPROFILE%\Zomboid\pzopt\`
are written on that boot and read on every boot after it.

Once you are at the main menu, in the same PowerShell window:

```powershell
Select-String -Path "$env:USERPROFILE\Zomboid\console.txt" -Pattern "\[pzopt\] loaded override" | Measure-Object | Select-Object -ExpandProperty Count
```

| You see | Meaning |
|---|---|
| `20` or more | the overrides loaded and are active (one line per class; a few more appear once a world is loaded). Done. |
| `0` | the class files did not load. Check that `$PZ\pzopt\Overrides.class` exists and that `$PZ\ProjectZomboid64.json` lists `"."` before `"projectzomboid.jar"` under `classpath` (it does on the stock depot). |
| a line in `console.txt` saying the overrides were built for another revision | your game is not 42.20.4 / `b0bbce05d5`. The game runs as stock. Switch Steam to that beta or wait for a matching zip. |

Options gains an **Optimizations** tab with every optimization as a toggle, and
Display & Performance gains the **Uncapped** entry, 300 to 500 fps caps and a
separate **Menu framerate** combo; see [Settings](#settings) for screenshots.
Everything else is frame time.

### Optional: change a setting

Use the Optimizations tab, or create `$PZ\pzopt.properties` with only the keys you
want to change; everything else keeps its default. A key in that file wins over the
tab and shows there as pinned. See [Settings](#settings) for the list.

```properties
workers=2
hotsaveIntervalSec=60
treesInChunkTexture=false
```

### Optional: the repository on Windows

You do not need it to play. If you want the harness or the sources, clone to a
short path such as `C:\Users\<you>\PZ_Optimization`; some files sit ten folders
deep, and from a long path the checkout fails with "Filename too long" unless
you pass `-c core.longpaths=true`. The build scripts do not run on Windows; the
zip is built on Linux with `scripts/release.sh`.

---

## Install on Linux

The release zip is the same on both platforms: the class files are plain JVM bytecode
and both Steam depots ship the identical jar, so the Linux install is the Windows one
with `install.sh`. Building from source is the alternative for anyone changing the
code.

**You need:** Project Zomboid on Steam on the **Build 42.20.4** beta, `bash`, `curl`
or the `gh` CLI, and `unzip` (or `python3`). No JDK.

### 1. Close the game

### 2. Run the installer

```sh
curl -fsSLO https://github.com/DiegoVillalobosFlores/PZ_Optimization/releases/latest/download/install.sh
chmod +x install.sh
./install.sh
```

It finds the game through Steam's `libraryfolders.vdf` (or `--dir <folder>` /
`PZ_DIR`), reads the game revision from the jar, downloads
`pzopt-<revision>-classes.zip` from the matching release, checks the launcher
classpath and that no file it would write exists, unpacks, and records what it wrote
in `pzopt-installed.txt`. The jar is never modified. To use a zip you already have,
pass `--zip pzopt-b0bbce05d5-classes.zip`.

```sh
./install.sh --status      # installed for which revision, any MISSING/MODIFIED file
./install.sh --uninstall   # removes exactly the files it wrote and the empty folders
```

`scripts/pzopt.sh` in the repository reads the same manifest, so either tool can
remove what the other installed.

### 3. Launch from Steam and check the log

Start the game from Steam as usual (first boot is slower: caches under
`~/Zomboid/pzopt/` are written). `~/Zomboid/console.txt` shows one
`[pzopt] loaded override ... active` line per class in its first seconds. If a
line says the overrides were built for another revision, the game and the files
do not match and everything runs as stock.

Settings are toggles in Options > Optimizations (saved to `~/Zomboid/pzopt/options.ini`,
applied on the next launch), or go in `pzopt.properties` next to `projectzomboid.jar`,
same format as on Windows; that file wins over the tab, and `./install.sh --status`
prints it when it exists.

### From source

For changing the code, or a game folder the zip does not match: a JDK 25 or newer
(`javac`, `javap`), Python 3, `git` and `bash`.

```sh
# Arch / CachyOS
sudo pacman -S jdk-openjdk
# Debian / Ubuntu
sudo apt install openjdk-25-jdk      # or the newest available
# Fedora
sudo dnf install java-latest-openjdk-devel
```

```sh
git clone https://github.com/DiegoVillalobosFlores/PZ_Optimization.git
cd PZ_Optimization
scripts/build.sh
scripts/pzopt.sh install
scripts/pzopt.sh status
```

If the game is not in `/games/steamapps/common/ProjectZomboid`, export the path
first in the same shell (the folder is right if it contains `projectzomboid/`
with `projectzomboid.jar` and `ProjectZomboid64.json` inside):

```sh
export PZ_ROOT="$HOME/.local/share/Steam/steamapps/common/ProjectZomboid"
```

`build.sh` compiles everything under `src/` against your jar and ends with a
line like `built 101 class files into build/classes for game revision b0bbce05d5`.
It also runs a structural check against the stock classes and fails loudly if
your game revision does not match the sources. Optional, the unit tests (no
game needed, a few seconds): `scripts/test.sh`. `pzopt.sh install` checks the
launcher classpath and the revision, copies `build/classes/` into the game folder,
records every file in `pzopt-installed.txt`, and refuses to overwrite any existing
file; `status` must print `installed: yes` with no `MISSING` or `MODIFIED` entries.
`scripts/release.sh` is what builds the release zip.

---

## Settings

### In the game menu

Every optimization is a toggle in **Options > Optimizations**, a tab of its own right
after Display & Performance. Tick boxes are the on/off switches; combos hold the
numeric budgets and thread counts, with the build's default on your machine as the
first entry. Hover a control for what it does and its key name. Changes apply on the
next launch: the game shows its usual "restart required" dialog when a change
matters, and the choices are kept in `Zomboid/pzopt/options.ini` (Linux
`~/Zomboid`, Windows `%USERPROFILE%\Zomboid`). Choosing "Default" removes the key
again.

![Options > Optimizations: every optimization as a tick box or combo, grouped as rendering, chunk streaming, boot and load](docs/media/options-optimizations-tab.jpg)

Display & Performance keeps the stock layout and gains the **Uncapped** entry, the
300 to 500 fps caps and the separate **Menu framerate** combo:

![Options > Display & Performance with the extended Lock Framerate combo and the Menu framerate combo](docs/media/options-display-tab.jpg)

### In a file

Keys also go in `pzopt.properties` in the game directory (next to `projectzomboid.jar`),
or as `-Dpzopt.<key>=` JVM properties. Only list the keys you change. A key set to
`false` or `0` restores stock behaviour for that item alone. A key set this way wins
over the menu and shows there as a disabled control whose tooltip names the file.
Full list with comments: `src/pzopt/pzopt/Config.java`.

| Key | Default | What it controls |
|---|---|---|
| `parallel` | `true` | recalc chunks on a worker pool (`false` = stock single thread) |
| `workers` | `min(4, cores-1)` | recalc pool width |
| `wake` | `true` | wake the streamer on enqueue instead of the 140 ms poll |
| `hotsaveIntervalSec` | `30` | minimum seconds between game-thread hot saves (`0` = stock) |
| `treesInChunkTexture` | `true` | static trees bake into the chunk texture |
| `windowsInChunkTexture` | `true` | windows and glass doors bake |
| `translucentTilesInChunkTexture` | `false` | `Translucent`-flagged tiles bake (black tile bug open) |
| `bakeBudget` | `8` | chunk textures baked per frame (`0` = unlimited) |
| `lightingBudget` | `8` | chunk lighting refreshes per frame (`0` = stock) |
| `cutawayFast` | `true` | replay the stored occluder mask on clean levels |
| `treeBakeDirect` | `true` | bake trees through the plain sprite path (the batched path dropped JUMBO trees near buildings) |
| `textureBufferMb` | `50` | texture upload buffer size |
| `lightingRebakeMs` / `cutawayRadius` / `gridStackInterval` | `0` | measured and not adopted (see below); `0` = stock |
| `persistentVbo` | `false` | persistently mapped sprite buffers |
| `fileThreads` / `fileInflight` | `max(4, cores/2)` / `4x` | async file system width and queue depth |
| `parallelDepthMaps` | `true` | decode depth-map tilesets concurrently |
| `loaderCpuFixes` | `true` | algorithmic fixes on the loader thread |
| `scriptParserFast` | `true` | linear script parser |
| `itemParamSwitch` | `true` | `Item.DoParam` switch dispatch |
| `fmodAsync` | `true` | FMOD init on a boot thread |
| `bootPump` / `bootFileThreads` / `earlyModels` | `true` / `max(4, cores-6)` / `true` | boot-time file pool pump |
| `luaPrecompile` | `true` | compile all Lua on a pool at boot |
| `preloadAnimSets` | `true` | parse animation sets at boot |
| `animClipCache` | `true` | cache imported animation clips under `Zomboid/pzopt/` |
| `packIndex` | `true` | cache texture-pack page offsets |
| `shaderCache` | `true` | reuse model shaders instead of one render step per model |
| `mipmapArrays` | `true` | texture mipmaps on byte[] rows instead of per-byte direct-buffer loops (issue #2; the crash itself was the laptop) |
| `loadWorkers` | `max(workers, cores/2)` | recalc pool width while a world loads |
| `noLoadFade` | `true` | skip the loading screen's fade to black |
| `noIntroWait` | `true` | new game: click-to-start as soon as the world is loaded, not after the 33 s intro |
| `uncappedFps` | `auto` | `true`/`false` force the cap off/on for a run |
| `instrument` | `false` | write per-chunk and per-frame timings for the harness |

To compare against stock on your own machine, put every switch off in
`pzopt.properties` (the full list is in [docs/windows-test.md](docs/windows-test.md))
and delete the file to return to the defaults.

---

## Uninstall

Close the game first. The jar was never modified, so no Steam file verification
is needed afterwards. The caches under `Zomboid/pzopt/` can be deleted by hand;
the frame-cap setting lives there too (`framecap.ini`), and without the overrides
the game uses whatever `options.ini` holds.

**Windows:** `install.ps1 -Uninstall`, or by hand, which removes exactly the files
the zip added, then the empty folders:

```powershell
$PZ = "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid"
Get-Content "$PZ\pzopt-files.txt" | ForEach-Object { Remove-Item -LiteralPath (Join-Path $PZ $_) -ErrorAction SilentlyContinue }
Remove-Item "$PZ\pzopt-files.txt", "$PZ\pzopt.properties" -ErrorAction SilentlyContinue
foreach ($d in "pzopt","zombie","org","se","media\lua\client\pzopt") {
  Get-ChildItem "$PZ\$d" -Recurse -Directory -ErrorAction SilentlyContinue | Sort-Object FullName -Descending |
    Where-Object { -not (Get-ChildItem $_.FullName -Force) } | Remove-Item
  if ((Test-Path "$PZ\$d") -and -not (Get-ChildItem "$PZ\$d" -Force)) { Remove-Item "$PZ\$d" }
}
Test-Path "$PZ\pzopt"   # False
```

**Linux:** removes exactly the files it installed and the empty folders it
created, then deletes the manifest. Either tool works on either install.

```sh
./install.sh --uninstall        # release install
scripts/pzopt.sh uninstall      # from-source install
```

---

## After a game update

The classes are compiled against one exact jar. After Steam updates the game they
disable themselves (one log line, stock behaviour). Never leave class files built
for an older revision on a newer game; they are inert but pointless.

**Windows:** `install.ps1 -Uninstall` and wait for a release whose zip carries the
new revision; `install.ps1` then installs it.

**Linux:** `./install.sh --uninstall` and wait for a release of this repo that targets
the new build. From source, on an unchanged revision (a Steam re-verify, for example)
just rebuild and reinstall:

```sh
scripts/pzopt.sh uninstall
scripts/build.sh
scripts/pzopt.sh install
```

If `build.sh` reports a revision or signature mismatch, the game changed and the
overrides need updating (`scripts/regen-overrides.sh` plus the edit log is the
maintainer's path; see `.claude/skills/game-update`).

---

## How the optimizations work

The game runs one main thread that simulates and renders, one streamer thread that
loads chunks, a save worker and an async file system. At max zoom while driving,
profiling (JFR) showed the main thread spending 80 % of an ordinary frame inside
`IsoCell.render`, and a third of *all* samples in the "translucent" pass that
redraws every tree, window and fence each frame. The streamer was idle 90 % of the
time but still delivered chunks 150 ms late because of a polling sleep. The
loading path was a chain of single-threaded parsers. Each group of changes below
attacks one of those. Every item names its `pzopt.properties` key.

### 1. Chunk streaming

**Wake the streamer on enqueue** (`wake`). The stock streamer thread checks its
queue every 140 ms. A chunk the game asked for waited about 150 ms doing nothing
before any work started. The override signals the thread the moment a job is
added. This alone takes chunk latency from 166 ms to 20 ms at the median.

**Parallel grid recalc** (`parallel`, `workers`). The expensive part of loading a
chunk is recalculating every square against its 3x3x3 neighbourhood. Stock does it
on the one streamer thread, and a mutable static (`IsoChunk.chunkGetter`) makes two
chunks recalculating at once unsafe. The override gives each job its own getter,
runs the pass on a small pool, and publishes results back in queue order so the
game thread sees chunks exactly as stock would. A worker failure is retried on the
streamer thread. The output is byte-identical to stock: a parity gate compares
131,133 squares in 1,653 chunks at 1, 2, 4 and 15 workers.

**Hot-save throttle** (`hotsaveIntervalSec`). Whenever the chunk save queue drains,
stock serialises the whole meta grid, game time, world map and entities on the
game thread. While driving the queue drains every 0.45 s, so this was a 2 to 5 ms
stall twice a second. The override runs it at most every 30 s.

### 2. Renderer

The game caches walls and floors of each chunk level in a texture and redraws only
what changed. Trees, windows and "translucent" tiles were excluded from that cache
and drawn every frame, thousands of draws at max zoom.

**Static trees bake into the chunk texture** (`treesInChunkTexture`). A tree is
drawn per frame only while it fades around the player, sways in the wind or
carries an effect; the level texture is invalidated when a tree changes state.
Frame mean 6.2 to 5.2 ms, GPU busy 84 to 61 % on its own.

**Windows and glass doors bake** (`windowsInChunkTexture`). Within noise alone,
kept because it removes 30 to 130 draws per frame at no cost.

**Per-frame bake budget** (`bakeBudget`). When a new chunk row comes into view
stock bakes dozens of chunk textures in one frame. The override bakes at most 8
per frame; textures baked before keep their previous image for a frame, never-baked
ones wait a frame. Slow frames were 26 % bakes; p99 13.8 to 8.3 ms.

**Per-frame lighting budget** (`lightingBudget`). The same idea for the
square-lighting refresh of chunks (12 % of slow frames). A pass that stops early
continues next frame and never drops a dirty flag.

**Cutaway occluder-mask replay** (`cutawayFast`). Clean chunk levels replay their
stored occluder bitmask instead of re-testing every square each frame. The mask
is exact; an earlier int-shifted version drew black one-tile rectangles beside
walls and was replaced.

**Persistently mapped sprite buffers** (`persistentVbo`, **off by default**).
Stock orphans and re-maps a 64 KB vertex buffer per sprite batch. The override
allocates immutable storage once and fences per buffer. Render thread busy 63 to
35 %, but no gain at the 240 cap and suspected in one black building lot, so it
stays off until that is understood.

**Translucent-flagged tiles bake** (`translucentTilesInChunkTexture`, **off by
default**). 16,476 tile definitions carry this flag (damaged fences, railings,
crops, wall decorations). Baking them cut per-frame draws from about 3,000 to
about 100, but some `Translucent` tileset tiles bake opaque black, so it is off
until the tile set is filtered.

### 3. Boot: launch to main menu

**FMOD on a boot thread** (`fmodAsync`). Sound system and 12 bank files (1.6 s)
initialise on a thread started at the top of the main thread's init and are
joined right before the first sound script needs them. The sound managers are
built at the join, not before it: their FMOD global parameters (music state,
intensity, time of day, ...) resolve against the loaded banks in their
constructors, and built too early they silently never register, which left the
menu music playing forever and the in-game audio dead (issue #3).

**Boot-time file-pool pump** (`bootPump`, `bootFileThreads`, `earlyModels`). The
async file system only advanced once per rendered frame, and frames start at the
menu, so for 4 s of boot the pool sat idle. A thread pumps it every 3 ms from the
start of init, and model creation moves right after the scripts load so the
2,209 animation imports are queued 2 s earlier.

**Lua precompile** (`luaPrecompile`). Every Lua file (game, mods, map objects)
compiles on a pool during boot; the game's `LuaCompiler` takes the prototype from
that cache, keyed by name and contents. A miss goes through the stock path.

**Animation clip cache** (`animClipCache`). Importing 2,209 `.X` animation files
through jassimp costs 14 to 17 thread-seconds per boot. After a stock import the
resulting clips are written under `Zomboid/pzopt/anims/` and read from there on
later boots (2.9 thread-seconds).

**Texture pack index** (`packIndex`). Version-0 texture packs were scanned byte by
byte (526 MB) at every boot to find page boundaries. The offsets are kept in
`Zomboid/pzopt/packs/*.idx` so the reader seeks.

**Linear script parser** (`scriptParserFast`). `ScriptParser.stripComments` was
quadratic (a `StringBuilder.replace` per comment on a multi-MB string, 1.56 s);
now a single pass with the same nesting rule and the same output (unit-tested).

**Item parameter switch** (`itemParamSwitch`). `Item.DoParam` tested each
parameter against a chain of 361 `equalsIgnoreCase` calls, 0.9 s of boot. Now a
`switch` on the lower-cased key.

**Logo screens skipped.** `TISLogoState` is a from-scratch replacement that goes
straight to the main menu.

### 4. Load: Continue to world ready

**File pool sized to the machine** (`fileThreads`, `fileInflight`). Stock decodes
every texture, model and depth map on 4 threads with 16 tasks in flight; the
loader thread then waits 3.5 s for them. Now half the cores and 4 tasks per thread
(more starves the game's own 8 meta-grid loader threads).

**Depth maps decode concurrently** (`parallelDepthMaps`). Stock ran all 218
tileset loads one at a time under a single lock, 2 s of one thread.

**Loader-thread fixes** (`loaderCpuFixes`), same results as stock:
`checkVehiclesZones` was called 11 times per load over 9,690 zones with an O(n²)
duplicate check; `MapCollisionData.init` and `IsoMetaCell.getChunk` looked up the
lot header per chunk instead of per cell; `checkBuildingAndRoomIDs` used
`indexOf` per room (O(rooms²) per cell).

**Animation sets preloaded at boot** (`preloadAnimSets`). The player and zombie
animation-set XML trees (1.1 s of JAXB on the loader thread) parse on a boot thread
instead.

**Model shader cache** (`shaderCache`). `Model.CreateShader` posted to the render
thread and waited one loading-screen step per model. On a laptop whose
loading-screen step is 220 ms the 73 animal models cost 16.5 s (GitHub issue #1).
Repeat shaders now come from a cache.

**Mipmaps on byte arrays** (`mipmapArrays`). Mip levels and alpha premultiply are built row
by row on `byte[]` copies instead of per-byte direct-buffer accesses, byte-identical to stock.
Written for GitHub issue #2 (a JVM SIGSEGV in `ImageData.scaleMipLevelMaxAlpha` on a laptop),
whose crash log shows a plain stack reload faulting on a 32-bit-truncated address alongside
two other truncated-address faults on that machine the same evening: a hardware or kernel
problem there, not the game code.

**Wider recalc pool while loading** (`loadWorkers`). The 361 chunks of the initial
chunk map recalc on half the cores, then the pool shrinks back.

**No fade to black** (`noLoadFade`). The loading screen's 350 ms of sleeps before
the world's own 2 s fade-in are removed.

**No intro wait** (`noIntroWait`). A new game shows "click to start" as soon as the
world is loaded; stock holds it back until the three intro lines ("This is how you
died") have played for 33 s. The lines still fade in and out behind the prompt.

### 5. Frame cap

The Display options get a real **Uncapped** entry and a separate **Menu framerate**
combo, plus 300, 330, 400, 430 and 500 fps entries in both (`pzopt.FrameCap`,
Lua under `src/lua/`, setting stored in `Zomboid/pzopt/framecap.ini`). The
in-game choice survives the game's own rewrite of `options.ini`.

### What was measured and not adopted

Lighting re-bake hold-off, cutaway visit radius, grid-stack interval (all within
noise); G1 instead of ZGC (p99 -13 % but 3x the frames over 33 ms); Mesa Zink
instead of NVIDIA GL (blocks 1.8 ms per frame in swap); a 256 MB texture upload
buffer (a 5 s frame a few seconds into the world); native Wayland (a wash at the
240 cap).

---

## Roadmap

Where the frame goes today, after everything above: on NVIDIA GL at max zoom the
machine is GPU-bound (fill for the 12800x5400 zoom-out buffer, 69 Mpixel per frame);
everywhere else, lower zoom, a smaller screen, a slower card, or Windows, the
**game thread** is the limit (93 % of wall on the Windows bench with the GPU at half
load). Items 1 and 2 attack that in order of value, each with a plan document
holding the measurements, the design and a go/no-go gate that is measured before any
work starts. Item 3 is a standalone experiment that runs independently of the other
two. Dates are not promised.

### 1. Game thread (next)

`docs/plan-driving-frame-time.md` §3, `docs/plan-resource-use.md` §4.3.

Measured shares of game-thread CPU on a CPU-visible run: `IsoCell.render` 58 %,
of which the translucent pass (windows, glass doors, `Translucent` tiles, wall
lighting) is still about 35 % of the whole thread; the Lua UI 25 to 34 %; the
view-cone stencil (`VisibilityPolygon2`) about 7 %; cutaway occlusion recompute
every frame while any chunk texture is dirty.

- **Translucent list built once per invalidation, not once per frame**
  (`translucentCache`, off today). The per-frame walk over every object of every
  chunk level is the largest single cost left; the list only changes when a chunk
  level is invalidated, which the bake path already tracks.
- **View-cone polygon off the game thread.** `calculateVisibilityPolygon` reads
  only state that changes in `logic()`, so it can start on the game's own fork-join
  pool right after `logic()` and be joined in `renderMain`. Gate: vertex-list
  parity, then the frame-time compare on a zoom-1.0 route, where the CPU is the limit.
- **Cutaway skip while driving outdoors** and the remaining per-frame lookups
  (`TilePropertyAliasMap` string lookups per object, per-sprite uniform HashMap
  lookups, `IOpenGLState` redundant sets, 5 %).
- **Lua UI** stays where it is: the stock `uiRenderOffscreen` option already moves
  it to its own rate, and the Lua VM itself is out of scope.
- **`Translucent`-flagged tiles bake** once the tile set that bakes opaque black
  is filtered (the flag exists, off by default).

Gate for each: byte-identical recalc parity where it applies, `harness/compare.py`
on the bench route, and the visual verify run on a real-save copy.

### 2. Vulkan renderer (measured gate first)

`docs/plan-vulkan-renderer.md`.

The inventory is done: LWJGL 3.4.1 is bundled without the `vulkan` module (loose
classes can carry it the same way the overrides load), the window is GLFW so a
`VkSurfaceKHR` is one call, the game thread already records commands that a
second thread replays (55 `TextureDraw` types), and about 1,800 direct GL call sites
in 119 files plus 61 raw-GL escape hatches would have to go through a backend seam.

What Vulkan can buy: the driver's CPU share of the render thread, ownership of
presentation (the Zink swap stall goes away, native Wayland), parallel command
recording for the offscreen world and the chunk passes, one draw per chunk level
through descriptor indexing, and exact per-frame GPU timestamps. What it cannot
buy: cheaper fragments (the fill cost is identical) or a faster game thread.

- **Phase 0, go/no-go:** native-frame profile of the render thread on the route.
  The port only starts if driver plus swap is **at least 20 % of the frame** on
  NVIDIA GL (30 % on Zink). Under that, the effort goes to GL-level batching
  (array textures, bindless, the persistent ring the VBO override already has)
  and to item 1.
- **Phase 1 regardless:** the backend seam with a GL implementation, pixel-parity
  tested (mean error at most 1/255 outside UI text). It is where the batching work
  lives either way.
- **Phases 2 to 5 on a go:** sprite path, then 3D and effects, then the reasons to
  have done it (parallel recording, bindless, timeline semaphores), then the tail.
  Every phase ends in a runnable, benchmarkable game. Native Linux first; nothing in
  the design blocks Windows later.

Targets at completion: GPU busy at least 95 % when not at the cap, game thread
blocked on the ready slot at most 10 % (39 % on Zink today), p99 better than the
GL baseline by at least the driver share Phase 0 measured.

### 3. Rust interop (standalone experiment)

A separate track, not sequenced behind items 1 and 2 and not a dependency of
either: does moving a whole pass from Java to Rust make it faster than the JIT,
by enough to pay for a native library in the install? No plan document yet; this is
the shape of the experiment.

The game already runs native code next to the JVM (lighting, pathfinding and
networking are C++ through JNI), and its bundled JRE is Java 25, so the Foreign
Function & Memory API is available without JNI glue. A Rust library installed
beside the loose classes, one build per platform, behind its own `Config` key so it
is off unless chosen. Candidate passes, one at a time, each a self-contained
experiment with its own result in `docs/results.md`:

- the translucent list build and the cutaway occluder scan (flat arrays, SIMD,
  no allocation, no GC pressure on the game thread);
- the view-cone polygon and `IsOnScreen` culling;
- chunk-texture bake preparation and texture decode / mipmaps on the loader threads;
- the sprite command replay on the render thread, later, if item 2's seam exists.

The JIT is already good at this kind of loop, so the gain has to be measured, not
assumed. Per pass: a microbenchmark against the Java version on the same input,
byte-identical output through the existing parity harness, then a bench-route
compare. A pass that does not beat Java by a clear margin is written up and
dropped. What the experiment has to answer before anything ships: the cost of a
native toolchain in the build, per-platform artifacts in each release, and the new
crash surface (a JVM fault in native code is not recoverable).

### Not on the roadmap

Lower render resolution or a smaller zoom-out buffer (meets the GPU target by
lowering the objective; only if Diego wants that trade), multiplayer, moving
`IsoCell.render` to another thread wholesale (GL context ownership), and further
chunk-streamer work (latency is at 4 to 5 ms median; done).

---

## How the install works without touching the jar

The game's launcher config `ProjectZomboid64.json` ships with
`"classpath": [".", "projectzomboid.jar"]`. The install directory comes before
the jar, so a loose `.class` file in the install directory **shadows** the same
class inside the jar. The overrides are copied in as loose files and removed by
deleting them; the jar's checksum never changes. This is the "manual class
replacement" method described on the [PZ wiki's Java page](https://pzwiki.net/wiki/Java).

Shadowed classes (25 game classes plus one from-scratch shim):

| Area | Classes |
|---|---|
| Streaming and render | `zombie.iso.IsoChunk`, `zombie.iso.WorldStreamer`, `zombie.iso.ChunkSaveWorker`, `zombie.iso.IsoMetaCell`, `zombie.core.VBO.GLVertexBufferObject`, `zombie.iso.fboRenderChunk.FBORenderCell`, `zombie.GameWindow`, `zombie.core.PerformanceSettings` |
| Boot and load | `zombie.fileSystem.FileSystemImpl`, `zombie.fileSystem.TexturePackDevice`, `zombie.tileDepth.TileDepthTextures`, `zombie.core.textures.TextureIDAssetManager`, `zombie.MapCollisionData`, `zombie.iso.IsoMetaGrid`, `zombie.gameStates.GameLoadingState`, `zombie.buildingRooms.BuildingRoomsEditor`, `zombie.core.skinnedmodel.advancedanimation.AnimationSet`, `zombie.core.skinnedmodel.model.AnimationAssetManager`, `zombie.core.skinnedmodel.model.Model`, `zombie.core.textures.ImageData`, `zombie.scripting.ScriptParser`, `zombie.scripting.objects.Item`, `se.krka.kahlua.luaj.compiler.LuaCompiler` |
| Window shims | `org.lwjglx.opengl.Display`, `org.lwjglx.input.Mouse` |
| From scratch | `zombie.gameStates.TISLogoState` |

The edited sources live under `src/overrides/` with every change marked
`// pzopt:` and described in prose in `docs/override-edits.md`. The new helper
code is the `pzopt` package under `src/pzopt/`.

Safety rails:

- **Build guard.** The classes record the game revision they were compiled against
  and the sha256 of every stock class they shadow, and disable themselves, with one
  log line, when the installed game differs. `scripts/pzopt.sh install` refuses to
  install a mismatched build.
- **Signature check.** The build fails if any non-private member of a shadowed
  class is missing or changed, so other game classes always link.
- **Kill switches.** Every optimization is a key in `pzopt.properties`.
- **Game-thread-only code stays there.** Pathfinding registration is never reached
  from a worker; dev builds assert it.
- **Save format and network payloads are untouched.**
- **All files the mod writes** (caches, frame-cap setting, traces) stay under
  `Zomboid/pzopt/`. The game directory only receives the files listed in
  `pzopt-files.txt` (Windows) or `pzopt-installed.txt` (Linux).

---

## Known limitations

- **Version pin.** One exact game build. Every Build 42 patch needs a new build of
  these classes; until then they disable themselves.
- **Other Java mods.** Only one mod can replace a given class. Anything that also
  replaces `IsoChunk`, `WorldStreamer`, `GameWindow`, `Item`, `ScriptParser` or
  any class in the table above conflicts, and whichever file is found first wins
  silently. Mods built on ZombieBuddy or Leaf patch methods and can coexist when
  they do not patch the same methods. ZombieBuddy 2.3.3 with ZBBetterFPS loaded
  fine next to these class files with the optimizations switched off; both sets
  active together has not been tested.
- **Single-player only.** Several shadowed classes run server-side in multiplayer
  (`IsoChunk`, `WorldStreamer`, `ChunkSaveWorker`, `IsoMetaGrid`). None of that
  has been exercised. Do not install on a server or join one with this installed.
- **Security.** Build 42.20.4 removed Lua `loadstring` and restricted the file
  types Lua may write. This mod does not widen either: the `LuaCompiler` override
  only caches compiled prototypes of the same source text, and all writes stay
  under `Zomboid/pzopt/`.
- **Visual changes still under soak.** Baked trees, windows and the cutaway mask
  have been verified on the bench route and on copies of real saves, but a long
  free-play soak (interiors, zombies behind fences, curtain and door state
  changes) is still open. Two rendering flags stay off because of known artifacts
  (see [Renderer](#2-renderer)).
- **Platforms.** Measured on Windows 11 with NVIDIA GL (`docs/windows-test.md`)
  and on the native Linux depot with NVIDIA GL under XWayland (Mesa Zink and
  native Wayland too). On Windows the bench route was run and the frame-cap
  combos checked; the long free-play soak above is open there as well.
- **Development install contents.** The build also carries the harness classes
  (`pzopt.Harness`, `pzopt.AutoStart`, `pzopt.Parity`, `pzopt.Stats`,
  `pzopt.ScriptDump`). They are inert unless the harness launches the game.

---

## Benchmark harness

One hands-off game run: reset a bench save from a template, auto-continue into it,
run a scripted route, quit, and collect logs, sysmon samples and per-frame timings
into `harness/runs/<label>-<timestamp>/`. The bench save template is checked in
as `harness/bench-save/pzopt-bench-template.tar.zst`.

Modes: `bench` (teleport route, fixed tiles per second), `drive` (spawns a car,
cruise control, follows the road), `parity` (captures every chunk's recalc output
for the byte-for-byte comparison), `verify` (a copy of a real save, for visual
checks). Bench runs must pin `--flag zoom=max`: frame-time baselines are only
comparable at the same zoom, resolution and renderer. Measurement runs keep the
PZDashboard mod off so its 2 s collectors stay out of the tail.

**Windows:** `harness/run-win.ps1` runs the bench through Steam, samples CPU and
GPU load with `Get-Counter` and `nvidia-smi`, and the analysis scripts run on any
Python 3 (the embeddable build is enough). No MangoHud, JFR or recording. The game
pauses on focus loss, so keep the window focused during a run.

```powershell
harness\run-win.ps1 -Label bench-opt -Flag zoom=max -Prop instrument=true
harness\run-win.ps1 -Label bench-stock -Flag zoom=max -Prop instrument=true,parallel=false,wake=false,treesInChunkTexture=false,windowsInChunkTexture=false,bakeBudget=0,lightingBudget=0,cutawayFast=false,hotsaveIntervalSec=0
python harness\analyze.py harness\runs\bench-opt-*
```

**Linux:** `harness/run.sh` adds an optional MangoHud CSV, JFR and a screen
recording. Restore the bench save once with:

```sh
zstd -dc harness/bench-save/pzopt-bench-template.tar.zst | tar -C ~/Zomboid/Saves/Sandbox -xf -
```

```sh
# the two runs behind the 60 km/h video
harness/run.sh --label show-stock --mode drive --flag route=E:1200 --record \
  --mangohud 98 --mangohud-config config/mangohud-showcase.conf --launcher direct \
  --prop instrument=true --prop parallel=false --prop wake=false --prop persistentVbo=false \
  --prop treesInChunkTexture=false --prop windowsInChunkTexture=false \
  --prop translucentTilesInChunkTexture=false --prop hotsaveIntervalSec=0 \
  --prop bakeBudget=0 --prop lightingBudget=0 --prop cutawayFast=false
harness/run.sh --label show-opt --mode drive --flag route=E:1200 --record \
  --mangohud 98 --mangohud-config config/mangohud-showcase.conf --launcher direct \
  --prop instrument=true

python3 harness/analyze.py harness/runs/show-stock-* harness/runs/show-opt-*   # frame tail + utilization
python3 harness/compare.py harness/runs/show-stock-1-* harness/runs/show-opt-1-*
python3 harness/loadtime.py harness/runs/<run>                                # boot and load phases
python3 harness/attribute.py --thread main harness/runs/<jfr-run>             # JFR attribution
python3 harness/dashboard.py                                                  # docs/benchmark-progress.html
```

Steam launches need the launch options set to
`<repo>/harness/steam-launch.sh %command%`; `--launcher direct` starts the native
game itself when Steam is not running. Pass `--no-dashboard` on measurement runs.

---

## Repository layout

| Path | What |
|---|---|
| `src/pzopt/pzopt/` | New classes: `Config`, `Overrides`/`BuildInfo` (build guard), `RecalcPool`, `OrderedPublisher`, `StreamerWake`, `BootPump`, `LuaPrecompiler`, `AnimClipCache`, `ModelShaders`, `FrameCap`, `Stats`, `Harness`/`Parity` |
| `src/overrides/` | The 25 shadowed game classes, edits marked `// pzopt:` |
| `src/shims/` | From-scratch replacements (`TISLogoState`) |
| `src/lua/` | The frame-cap and Optimizations-tab options Lua, installed under `media/lua/client/pzopt/` |
| `install.sh`, `install.ps1` | Standalone installers (Linux, Windows) for the release zip; also attached to every release |
| `scripts/` | `build.sh`, `pzopt.sh`, `release.sh` (release zip + GitHub release), `test.sh`, `accept.sh`, `regen-overrides.sh`, `decompile.sh`, `pz-env.sh` |
| `harness/` | `run-win.ps1` (Windows) and `run.sh` (Linux), analysis scripts, `parity-gate.sh`, the `pzopt-harness` Lua mod, bench save template, `baseline/` captures (`baseline/windows/` for the Windows runs) |
| `config/` | MangoHud profiles |
| `tools/` | Standalone Java probes (JFR sample dump, GLFW swap probe, static audit) |
| `tests/` | JVM-only unit tests (`scripts/test.sh`) |
| `docs/` | `results.md` (every run, in order), `override-edits.md` (every edit, in prose), `windows-test.md`, the plans, `benchmark-progress.html` |
| `docs/media/` | The comparison videos, posters and the results chart |

Project Zomboid is by The Indie Stone. This repository contains no game assets;
the edited class sources under `src/overrides/` are derived from the shipped jar
for the sole purpose of these patches.
