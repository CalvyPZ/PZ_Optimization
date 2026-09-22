# Steam Workshop item (pure distribution)

The Workshop item is a mirror of the GitHub release, packaged the way
[BetterFPS](https://steamcommunity.com/workshop/filedetails/?id=3022543997) does it: Steam
downloads the files, the player installs them by hand or with the installer that ships in the
item. The game loads nothing from it (no `media/` folder); `mod.info` only makes it appear in
the Mods list with the instructions. GitHub releases stay the canonical channel; the Workshop
page states the commit and the zip sha256 so the two can be checked against each other.

## Layout (`scripts/workshop.sh` writes it)

```
~/Zomboid/Workshop/PZ_Optimization/          staging folder the in-game uploader reads
├── workshop.txt                             title / description= lines / tags=Build 42; / visibility
├── preview.png                              512x512 (256 or 512 square, <= 1 MB) from the showcase thumbnail
├── preview.gif                              animated preview (steamcmd route, see Images)
├── item.vdf                                 steamcmd workshop_build_item file (written once id= is known)
└── Contents/mods/PZ_Optimization/42/        B42 versioned mod layout
    ├── mod.info                             id=PZ_Optimization, modversion=<commit>, versionMin
    ├── poster.png
    ├── install.ps1                          the repo-root installer (the next release's asset)
    ├── install.bash                         install.sh renamed (.sh is a banned extension)
    └── pzopt-classes/                       the release zip unpacked (pzopt-files.txt included)
```

Steam installs it under `steamapps/workshop/content/108600/<id>/mods/PZ_Optimization/42/`.
Both installers look for a `pzopt-classes/` folder next to themselves first, so the whole
Windows instruction is one `powershell -ExecutionPolicy Bypass -File ...\install.ps1` line and
the Linux and macOS one is `bash .../install.bash` (on macOS the item lives under
`~/Library/Application Support/Steam/steamapps/workshop/...` and the files go into
`Project Zomboid.app/Contents/Java`); `--from <dir>` / `-From <dir>` name the folder
explicitly. The page text lives in `docs/workshop/description.txt` (Steam BBCode;
`@REV@ @VERSION@ @COMMIT@ @NFILES@ @NOVERRIDES@ @SHA@ @ID@` are filled in by the script).
**Steam caps the description at 8,000 characters** and the game appends `\n\nWorkshop ID: <id>\nMod ID:
PZ_Optimization` (~50) before submitting, so the staged `description=` lines must stay under ~7,950;
over that the upload ends in `failed to update workshop item, result=8` (`Invalid Parameter` in
`workshop_log.txt`) with nothing changed (2026-09-21, three attempts). A description-only update gets no
change-notes entry (`No content change detected`); verify it on the item page instead.

Rules the game's validator (`zombie.core.znet.SteamWorkshopItem.validateContents`) enforces,
checked by the script before the in-game screen has to refuse:

- only `mods/`, `buildings/`, `creative/` directly under `Contents/`, no loose files;
- no `*.exe *.dll *.bat *.app *.dylib *.sh *.so *.zip` anywhere in `Contents/`;
- `preview.png` must be a square 256 or 512 PNG under 1,024,000 bytes.

## Publishing

1. Publish the GitHub release first (`scripts/release.sh --publish`, skill `release-windows`),
   so the item mirrors a tagged, pushed commit.
2. Stage the published asset: `scripts/workshop.sh --tag win-<rev>-<commit>` (gh downloads
   the zip into `build/workshop/<tag>/`, the commit for `modversion` comes from the tag).
   `--zip <file> [--commit <sha>]` stages a local zip; with neither, the script runs
   `scripts/release.sh` (build + test + zip) and stages that as HEAD.
3. Launch the game **through Steam, logged in** (the uploader is SteamAPI; `run.sh --launcher
   direct` cannot upload). Main menu > Workshop > Create/Update item > `PZ_Optimization`.
   The screen shows the validation result, the title, the description and the tags read from
   `workshop.txt`; Upload. The first upload opens the Steam Workshop legal agreement in the
   overlay and writes `id=<number>` back into `workshop.txt`.
4. Copy that `workshop.txt` to `docs/workshop/workshop.txt` and commit it: the script reads the
   `id=` from there on every later staging, so updates go to the same item, and the
   description's install commands carry the real path.
5. After the first upload, re-stage (step 2) and upload once more so the description no longer
   says `<item id>` in the install command.

Updating after a new release is steps 1-3 again; the game keeps the id and the visibility.
Hands-off version of step 3 (xdotool click sequence, the Steam connection preflight, log-based
verification): `.claude/skills/release-windows`, "Steam Workshop deploy". A `result=2` failure in the
game is a dead Steam session ("Session Replaced" in `connection_log.txt`); restart Steam.

## Images

`docs/workshop/images/` holds the page images: `00` the Workshop thumbnail (2560x1440; the
YouTube one with the header "PZ Optimized" and only the 632 fps readout: `THUMB_HEADER="PZ
Optimized" THUMB_ONLY_OPT=1 python3 harness/showcase-thumbnail.py docs/workshop/images/00-showcase-thumbnail.jpg`;
`preview.png` is its square centre crop), `01`-`07`
one SDR still per segment of `docs/media/showcase-stock-vs-all-optimizations.mp4` (boot/load,
120 km/h drive, options tab, Rosewood spin, fog, storm, results card; 1920x900, the posters'
hable tone-map at 18 / 33 / 50 / 65 / 82 / 98 / 116 s), `08` the options-tab close-up from
`docs/media/`, `09` the overlay, `10` the Workshop-mods comparison table (`docs/media/workshop-mods-comparison.png`
from `harness/mods-table.py`, scaled to 1920 wide as JPG), `11` the "New! Low-end hardware mode" table
(`docs/media/dell-lowend-comparison.png` from `harness/lowend-table.py`; the image carries the whole
section's text and the date, the description holds only the `[img]`, see the release-windows skill),
`12` the "New! macOS support" table (`docs/media/mac-comparison.png` from `harness/mac-table.py`: the
M1 Pro 120 km/h drive, two-run means, the Terminal install line in the footer; 2026-09-21), `13` the "New! Better
profiling" card (`docs/media/profiler-card.png` from `harness/profiler-card.py`: the whole F9 overlay cropped from a
recorded run, `docs/media/profiler-overlay-full.png`, with the caption lines; 2026-09-22), `14` the "New! See what
every setting does" card (`docs/media/preview-card.png` from `harness/preview-card.py`: the Optimizations tab with its
preview panel, `docs/media/options-preview-full.png`; 2026-09-22; the macOS section left the page with it, macOS stays
in the intro line), `15` the "New! In-game updater" card (`docs/media/updater-card.png` from `harness/updater-card.py`:
the main menu with the item greyed out and enabled plus the dialog before and after the install, from the desktop
captures `docs/media/updater-menu-current.png`, `updater-menu.png`, `updater-dialog-available.png`,
`updater-dialog-installed.png`; 2026-09-22; the two earlier "New!" sections lost their "New!" with it).
`description.txt` embeds them with `[img]` from the raw GitHub URL of `master`,
so they render only after the folder is pushed. The same files go in the item's own carousel:
on the Workshop page, "Add/edit images & videos" takes the JPGs (upload `00` first, it becomes
the header) and a YouTube URL for the showcase video.

### Animated thumbnail

`docs/workshop/images/00-showcase-thumbnail.gif` (`harness/showcase-thumbnail-gif.py`): the
results-card capture from 25 s, a square crop centred on the character (the game camera follows
them, so the crop is fixed), "PZ Optimized" on a band at the top and the performance overlay
pasted live along the bottom (the left 747x305 of the panel at 0.6x: fps / ms, percentiles,
1 %-low / jitter / spikes, loads, verdict, graph; text ~14 px, not denoised). 448x448, 12 fps,
53 frames (4.4 s: the shot, then the in-game zoom-out and the walk; per-frame delays 8/8/9 cs so
the GIF plays at real time), 96 colours, median-3 denoise on the game part, gifsicle
`--lossy=100`: 987,416 bytes. Steam's preview limit is
1,000,000 bytes, not 1 MiB (the game's own check says 1,024,000): a 1,011,209-byte GIF came back
from steamcmd with `Failed to update workshop item (Limit exceeded)`. The asphalt
grain is what costs (plain LZW is ~145 KB a frame at 512 px whatever the palette); ImageMagick's
fuzz transparency ghosts on the panning camera and dither triples the size, so neither is used.

Until 2026-09-21 the GIF was 6 fps (26 frames at ~35 KB). Doubling the rate at the same per-frame
quality came from the HUD elements, not the scene (script header for the numbers): the banner's
text rows and the header band are translucent, so the scrolling game behind them changed every
pixel of a static panel; they now keep their previous pixels within a tolerance (glyph changes
always pass, the panel background freezes: `GIF_TEXT_TOL`, `GIF_HUD_TOL`), and the frame graph is
redrawn every 4th frame (`GIF_GRAPH_EVERY`). The scene rows only get a spatial denoise
(`GIF_SCENE_VF`, hqdn3d 3/2 + a mild bilateral: SSIM 0.943 against the composited frames vs the old
GIF's 0.948). Temporal holds on the scene are out: the camera is never still in this clip (a
(-4,-2) px/frame drift under the opening shot), so held low-contrast asphalt turns into a stale
mosaic (SSIM 0.915 at a 6-level tolerance); gifski's dither crawls; 64-80 colours posterize.
Steam re-encodes the GIF on its CDN (frames kept) and shows it at 268 px on the item page, 448 px
behind the enlarge click, and at native size where the description embeds it from GitHub.
Needs `gifsicle` (`pacman -S gifsicle`, or `GIFSICLE=<binary>`; on the desktop it is built from
source into `~/.local/bin`). `GIF_END=x:y:w` gives the older zoom-into-the-overlay variant.
`09-performance-overlay.jpg` is the overlay panel cropped
from the 25 s frame for the carousel and the "Performance overlay" section.

The game's uploader hard-codes `preview.png` and rejects anything that is not a PNG, so the GIF
cannot go up from the in-game screen. Steam's own tool takes it: install `steamcmd` (AUR), then

```sh
steamcmd +login <steam user> +workshop_build_item ~/Zomboid/Workshop/PZ_Optimization/item.vdf +quit
```

`item.vdf` (written by `workshop.sh` once `id=` is known) names the same `Contents/` folder and
`preview.gif`; it carries no title or description, so the page text stays what the in-game
upload set. Steam Guard asks for the code on the first login. Any later in-game upload
sends `preview.png` again and replaces the GIF, so re-run the steamcmd line after one.

## What the item cannot do

- Nothing on the Workshop can write to the game folder; the install stays manual.
- The three Lua files under `pzopt-classes/media/lua/` are not loaded from the item (they are
  not under `42/media/`); they reach the game with the class files, as on the GitHub path.
- A game update makes the runtime guard turn the classes off until a build for the new
  revision is uploaded; the page says so under "Updates".
