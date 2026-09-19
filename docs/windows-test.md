# Testing the overrides on Windows

First Windows test of the class overrides. Nothing has to be compiled on Windows:
the zip built on Linux carries the finished class files, and the runtime guard
(`pzopt.Overrides`) checks both the game revision and the sha256 of every stock
class it shadows against the Windows jar. If anything differs it logs one line and
the game runs as stock, so the worst case of a mismatch is "no effect".

Package: `build/pzopt-b0bbce05d5-classes.zip` (518 KB, 97 files plus the
manifest `pzopt-files.txt`), built 2026-09-19 from commit `a202778` for game
revision `b0bbce05d5` (Build 42.20.4).

## Before rebooting (on Linux)

Get the zip somewhere Windows can read. The Linux side is ZFS and XFS, which
Windows cannot mount, so use one of:

```sh
# a) the NTFS partition (sda2, 480 GB, unmounted right now)
sudo mount -t ntfs3 /dev/sda2 /mnt && cp build/pzopt-b0bbce05d5-classes.zip /mnt/ && sudo umount /mnt

# b) a GitHub release asset on the private repo (download from a browser on Windows)
gh release create win-test-b0bbce05d5 build/pzopt-b0bbce05d5-classes.zip \
  --title "Windows test build (42.20.4 / b0bbce05d5)" --notes "class overrides for the Windows test, see docs/windows-test.md"
```

Also bring this file (or open it on GitHub).

## On Windows

All commands are PowerShell. `$PZ` is the game folder; adjust it if Steam is not
in the default place (Steam, right-click the game, Manage, Browse local files).

### 1. Check the game

- Steam, Properties, Betas: the game must be on **Build 42.20.4**. Any other
  version makes the guard disable the overrides.
- Close the game.

```powershell
$PZ = "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid"
Get-Content "$PZ\ProjectZomboid64.json" | Select-String classpath
```

The classpath line must list `"."` **before** `"projectzomboid.jar"`. If it does
not, stop: loose class files would never load on this depot.

Confirm the folders the zip will create do not already exist (they should not on
a stock install; `media` exists and only gets one Lua file added under
`media\lua\client\pzopt`):

```powershell
Test-Path "$PZ\pzopt"; Test-Path "$PZ\zombie"; Test-Path "$PZ\org"; Test-Path "$PZ\se"
```

All four should print `False`.

### 2. Install

Unpack the zip straight into the game folder. It only adds files; it never
touches `projectzomboid.jar`.

```powershell
Expand-Archive -Path "$env:USERPROFILE\Downloads\pzopt-b0bbce05d5-classes.zip" -DestinationPath $PZ
Get-Content "$PZ\pzopt-files.txt" | Measure-Object -Line     # 97
Get-Content "$PZ\pzopt\build-info.properties" | Select-String "^revision"
```

`Expand-Archive` refuses to overwrite existing files unless `-Force` is given.
Do not give it.

### 3. First launch

Launch from Steam normally. On the first boot the caches under
`%USERPROFILE%\Zomboid\pzopt\` (animation clips, texture-pack index, Lua
prototypes) are written, so it is a slower boot than the ones after it.

Then read the log:

```powershell
Select-String -Path "$env:USERPROFILE\Zomboid\console.txt" -Pattern "\[pzopt\]" | Select-Object -First 40
```

What to look for:

| Line | Meaning |
|---|---|
| `[pzopt] loaded override zombie.iso.IsoChunk (target revision b0bbce05d5, active)` | the guard passed; one such line per class |
| `game revision is X but overrides were built for b0bbce05d5; overrides disabled` | Windows depot is a different revision. Note X and stop; the test is over |
| `jar copy of ... differs from the one the overrides were built against` | same revision, different class bytes on the Windows jar. Note which class; the overrides are off |
| no `[pzopt]` line at all | the classes did not load. Re-check the classpath in step 1 and that `$PZ\pzopt\Overrides.class` exists |

If the guard disabled the overrides, everything below is moot but the game
should still run normally. That result is itself worth bringing back.

### 4. What to test

Do these in order and write down what happens at each step.

1. **Main menu.** Time from double-click to the menu, roughly. The TIS logo
   screens are skipped; the menu should appear directly.
2. **Options, Display.** The framerate combo has an **Uncapped** entry and
   300/330/400/430/500 fps entries, and a separate **Menu framerate** combo
   sits below it. Pick a value, apply, quit to desktop, relaunch: the value
   must survive. The setting lives in `%USERPROFILE%\Zomboid\pzopt\framecap.ini`.
3. **Continue a save.** Use a copy of a save, not the real one, if the save
   matters. Note the Continue-to-world time.
4. **Drive at max zoom** for a couple of minutes on a road with trees and
   buildings. Watch for:
   - black one-tile rectangles beside walls or black floor rectangles
   - trees that are missing, or that pop in late next to buildings
   - windows or glass doors that do not draw, or draw when they should be cut away
   - chunk edges that arrive noticeably late at speed
   - stutter on the 2 s beat (that would be a mod, PZDashboard, not this)
5. **Walk through buildings.** Cutaway walls around the player, doors and
   curtains opening and closing, windows breaking: the baked chunk textures must
   refresh when the state changes.
6. **Quit to menu and Continue again** without closing the game.
7. **Frame rate.** With the in-game limiter at Uncapped and vsync off, note the
   fps the game shows (or use the Steam overlay's fps counter). No MangoHud on
   Windows; a rough number is enough.

For a stock comparison on the same machine, create `$PZ\pzopt.properties` with
every switch off and relaunch; delete the file to return to the defaults:

```properties
parallel=false
wake=false
persistentVbo=false
treesInChunkTexture=false
windowsInChunkTexture=false
translucentTilesInChunkTexture=false
hotsaveIntervalSec=0
bakeBudget=0
lightingBudget=0
cutawayFast=false
fmodAsync=false
bootPump=false
earlyModels=false
luaPrecompile=false
preloadAnimSets=false
animClipCache=false
packIndex=false
scriptParserFast=false
itemParamSwitch=false
loaderCpuFixes=false
parallelDepthMaps=false
shaderCache=false
noLoadFade=false
```

For per-frame numbers add `instrument=true` to that file (or to an otherwise
empty one): the game then writes `pzopt-frames.out` and `pzopt-chunks.out` in
`%USERPROFILE%\Zomboid`, which `harness/analyze.py` can read back on Linux.

### 5. If the game crashes

Bring back `%USERPROFILE%\Zomboid\console.txt` and any `hs_err_pid*.log` from
`$PZ`. The line right before the crash in console.txt is usually the answer.

### 6. Uninstall

Removes exactly the files the zip added, then the empty folders. The jar was
never modified, so no Steam file verification is needed.

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

The caches in `%USERPROFILE%\Zomboid\pzopt\` can be deleted by hand; the game
never reads them without the overrides installed.

## Results 2026-09-19 (first Windows test)

Machine: the same box booted into Windows 11 IoT Enterprise LTSC 2024 (Ryzen 7 9800X3D, RTX 4090,
NVIDIA driver 616.92 / 32.0.16.1692, Azul Zulu 25 bundled JRE, ZGC). Game at
`C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid`, Build 42.20.4, jar sha256 and
size identical to the Linux depot's, classpath `"."` before the jar. Desktop 5120x2160, game
fullscreen at desktop resolution, vsync off, in-game cap 500 fps (`framecap.ini gameFps=500`),
launched through Steam. No MangoHud; utilization from `Get-Counter` + `nvidia-smi`.

Install: `Expand-Archive` of the zip, 97 manifest files, no pre-existing folders. Guard: every
override logged `active`, no revision or class-hash mismatch. Nothing had to be compiled.

### Boot

| Boot | Menu after process start | Notes |
|---|---|---|
| 1 (cold caches) | ~40 s | 1522 anim clips written, FMOD "Error initializing output device" (no audio device set up on this Windows install, not ours) |
| 2 (warm) | 21 s | anim clip cache 2161 hits / 0 misses |
| 3+ (warm, bench runs) | ~15 s | boot pump 5.9 s, anim sets preloaded 1.0–1.2 s |

Quitting from the main menu of boot 1 crashed on exit: `EXCEPTION_ACCESS_VIOLATION` in
`ZNetJNI64.dll` under `SteamWorkshop.n_GetInstalledItemFolders`, reached from
`RenderThread.shutdown → IsoPuddles.getInstance → Texture.getSharedTexture → ZomboidFileSystem.validatePrefix`
(the puddle renderer is constructed for the first time during shutdown and asks Steam for mod
folders after the Steam API is gone). Stock code path; a menu quit of a later boot did not
reproduce it. Dump kept as `harness/runs/win-boot-20260919/hs_err_pid3536-boot1-quit.log`.

Frame-cap combos: Diego confirmed Uncapped, 300–500 and the separate Menu framerate combo; a
500 fps choice survived a relaunch (`framecap.ini`, `[pzopt] frame cap: game 500 fps`).

### Bench route, optimized vs stock switches

`harness/run-win.ps1` (a PowerShell port of the run.sh steps a bench needs) on the committed
bench save, `--flag zoom=max` (max zoom on this install is **2.0**, not the 2.5 of the Linux
runs: options.ini `zoomLevels2x` tops out at 200), dashboard off, `instrument=true`. Stock =
every switch in the list above off. Analysis with `harness/analyze.py` (embeddable Python 3.12
under `%LOCALAPPDATA%\Programs\Python312-embed`).

| Run | fps mean | frame mean | p50 | p90 | p99 | p99.9 | max | >33 ms |
|---|---|---|---|---|---|---|---|---|
| `win-bench-stock-20260919-222950` | 172.5 | 5.8 ms | 4.8 | 10.1 | 19.1 | 28.4 | 46.8 | 8 |
| `win-bench-opt-20260919-222646` | 251.7 | 4.0 ms | 3.2 | 6.8 | 13.9 | 19.9 | 42.2 | 2 |

| Run | chunks | queue wait mean | queue wait p99 | recalc threads |
|---|---|---|---|---|
| stock | 4294 | 174 ms | 351 ms | World Streamer only |
| optimized | 4294 | 30 ms | 74 ms | 4 × pzopt-recalc |

Utilization over the route window (sysmon, ~62 samples each):

| Run | machine CPU | busiest core | game process | main thread | GPU load | GPU W |
|---|---|---|---|---|---|---|
| stock | 29 % | 66 % | 271 % of a core | 88 % of wall | 70 % (p90 99) | 139 |
| optimized | 28 % | 70 % | 274 % of a core | 93 % of wall | 60 % (p90 82) | 157 |

Finding against the objective: at 252 fps under a 500 cap neither the machine (28 % of 16
cores) nor the GPU (60 %) is saturated; the game thread is (`main` 93 % of wall). The
optimized build is game-thread-bound on Windows exactly as on Linux. Stock burns more GPU per
frame (the per-frame tree/translucent pass) for fewer frames.

Not directly comparable with the Linux native numbers (zoom 2.0 vs 2.5, 500 vs 240 cap,
Windows driver 616.92), but the direction and the p99 gain (19.1 → 13.9 ms) match the Linux
result (19.3 → 8.3 ms at zoom 2.5).

One stock run only: no noise floor yet. Two runs were discarded and are kept with an
`-INVALID-` suffix: the first optimized run lost window focus for 11 s right after the world
came up (options.ini `focusloss=true` pauses the game; `IsoChunk.update` stops, so the
sampler's first route frame was 12.6 s and the route started late), and the first stock run
got its properties on one comma-joined line (`powershell -File` does not split `-Prop a,b`;
run-win.ps1 now splits on commas itself).

Not yet done from the list above: the manual drive at max zoom, walking through buildings,
quit-to-menu-and-Continue, and the visible-fps reading with the in-game limiter at Uncapped.

## What to bring back to Linux

- `console.txt` from the first optimized boot (the `[pzopt]` lines) and from a
  drive.
- The rough boot, load and fps numbers, optimized and stock.
- Screenshots of any artifact.
- `pzopt-frames.out` / `pzopt-chunks.out` if `instrument=true` was used.
- Whether the frame-cap combos and the menu cap worked.
