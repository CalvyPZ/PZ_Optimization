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
the Linux one is `bash .../install.bash`; `--from <dir>` / `-From <dir>` name the folder
explicitly. The page text lives in `docs/workshop/description.txt` (Steam BBCode;
`@REV@ @VERSION@ @COMMIT@ @NFILES@ @NOVERRIDES@ @SHA@ @ID@` are filled in by the script).

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

## Images

`docs/workshop/images/` holds the page images: `00` the Workshop thumbnail (2560x1440; the
YouTube one with the header "PZ Optimized" and only the 632 fps readout: `THUMB_HEADER="PZ
Optimized" THUMB_ONLY_OPT=1 python3 harness/showcase-thumbnail.py docs/workshop/images/00-showcase-thumbnail.jpg`;
`preview.png` is its square centre crop), `01`-`07`
one SDR still per segment of `docs/media/showcase-stock-vs-all-optimizations.mp4` (boot/load,
120 km/h drive, options tab, Rosewood spin, fog, storm, results card; 1920x900, the posters'
hable tone-map at 18 / 33 / 50 / 65 / 82 / 98 / 116 s), `08` the options-tab close-up from
`docs/media/`. `description.txt` embeds them with `[img]` from the raw GitHub URL of `master`,
so they render only after the folder is pushed. The same files go in the item's own carousel:
on the Workshop page, "Add/edit images & videos" takes the JPGs (upload `00` first, it becomes
the header) and a YouTube URL for the showcase video.

### Animated thumbnail

`docs/workshop/images/00-showcase-thumbnail.gif` (`harness/showcase-thumbnail-gif.py`): the
results-card capture from 25 s, a square crop centred on the character (the game camera follows
them, so the crop is fixed), "PZ Optimized" on a band at the top and the performance overlay
pasted live along the bottom (the left 747x305 of the panel at 0.6x: fps / ms, percentiles,
1 %-low / jitter / spikes, loads, verdict, graph; text ~14 px, not denoised). 448x448, 6 fps,
26 frames (4.4 s: the shot, then the in-game zoom-out and the walk), 96 colours, median-3
denoise on the game part, gifsicle `--lossy=100`: ~917,000 bytes. Steam's preview limit is
1,000,000 bytes, not 1 MiB (the game's own check says 1,024,000): a 1,011,209-byte GIF came back
from steamcmd with `Failed to update workshop item (Limit exceeded)`. The asphalt
grain is what costs (plain LZW is ~145 KB a frame at 512 px whatever the palette); ImageMagick's
fuzz transparency ghosts on the panning camera and dither triples the size, so neither is used.
Needs `gifsicle` (`pacman -S gifsicle`, or `GIFSICLE=<binary>`). `GIF_END=x:y:w` gives the
older zoom-into-the-overlay variant. `09-performance-overlay.jpg` is the overlay panel cropped
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
