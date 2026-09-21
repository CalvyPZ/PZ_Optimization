---
name: release-windows
description: Build the release zip of the class overrides (same zip for Windows and Linux) and publish it with the install.sh / install.ps1 installers as GitHub release assets. Use when asked for a new Windows or Linux build, a release, a zip for the Windows test, or to update the release asset after a change under src/ or the installers.
---

# Windows release

```bash
scripts/release.sh                        # build + test + build/pzopt-<rev>-classes.zip
scripts/release.sh --publish              # ...then gh release create win-<rev>-<commit>
scripts/release.sh --publish --notes "Adds X and Y over the previous build."
```

What the script does, in order:

1. `scripts/build.sh` (javac against the local jar, `build/classes/`) and `scripts/test.sh`.
2. Zips the flat content of `build/classes/` (class files, `media/lua/...`,
   `pzopt/build-info.properties`) plus a manifest `pzopt-files.txt` listing every entry.
   Python `zipfile` is used because this machine has no `zip` binary.
3. With `--publish`: tags the full SHA of HEAD as `win-<game revision>-<short commit>` and
   uploads the zip plus the repo-root `install.sh` and `install.ps1` with notes carrying the build date, commit, revision, manifest count and
   sha256. One release per commit; an existing tag makes it stop.

Rules and gotchas:

- **Publish only from a pushed, clean commit.** The script refuses if `src/` or `build.sh`
  has uncommitted changes or HEAD is not on `origin/master`; the tag must describe the bytes
  in the zip. Commit and push first (only when asked for the commit).
- **Never overwrite an earlier asset.** Each build gets its own release; the previous test
  build (`win-test-b0bbce05d5`) stays as the reference the Windows results were taken on.
- Put what changed since the last Windows build in `--notes` (read `git log <last tag>..HEAD`).
- The Windows install/uninstall steps live in `docs/windows-test.md`; it quotes the manifest
  line count (`Measure-Object -Line`). Update that number if it changed, and add a results
  section there when Windows numbers come back.
- The game revision in the tag comes from `build-info.properties`; after a game update run the
  `game-update` skill first, otherwise the guard on Windows disables the overrides.
- The installers (`install.sh`, `install.ps1`) are standalone: they find the game via Steam's
  library list, read the revision from the jar, download the matching zip (curl, or `gh`
  when logged in) and write `pzopt-installed.txt` in the `pzopt.sh`
  format. Test them against a temp folder holding a symlinked jar and a copied launcher JSON,
  never the real game dir; portable PowerShell (`/tmp/pwsh/pwsh` when present, else the
  linux-x64 tarball from PowerShell's GitHub releases) runs `install.ps1` on Linux.
- Building does not touch the game dir, so no peer-session check is needed for a build-only
  run. It does not install either; use `build-install` for that.

## Steam Workshop mirror

After `--publish`, `scripts/workshop.sh --zip build/pzopt-<rev>-classes.zip` stages the same
zip as a Workshop item under `~/Zomboid/Workshop/PZ_Optimization/` (pure distribution: the
tree unpacked under `42/pzopt-classes/` plus `install.ps1` and `install.bash`, which is
`install.sh` renamed because the uploader bans `.sh`, `.zip`, `.bat`, `.exe`, `.dll`, `.so`).
The upload itself is in the game launched through Steam (Workshop > Create/Update item);
the first upload writes `id=` into `workshop.txt`, which goes to `docs/workshop/workshop.txt`.
Both installers auto-detect a `pzopt-classes/` sibling, so the Workshop instructions are one
line per OS. Details: `docs/workshop.md`.

## Steam Workshop deploy (hands-off, 2026-09-21)

### A user-visible change gets a "New!" section on the page, as one image

Before the upload, when the release adds something a player sees (a mode, a button, a scene
that got faster), the description gets a section at the top, right after the showcase GIF:

```
[h1]New! <feature name>[/h1]
[img]https://raw.githubusercontent.com/xD3I/PZ_Optimization/master/docs/workshop/images/<NN>-<slug>.jpg[/img]
```

**Nothing else in the section: the image carries all the text** — the title ("New! ..."), the
date of the measurement (top right, `YYYY-MM-DD`), one or two lines saying what the feature is
and the machine, and the stock-vs-new table with a bar per row. Render it with a script under
`harness/` in the `docs/media-style.md` style (`harness/lowend-table.py` is the template:
`DATE`, `ROWS`, the two colour roles stock amber / new green, a gain column), then
`ffmpeg -y -i docs/media/<name>.png -vf scale=1920:-1 -q:v 3 docs/workshop/images/<NN>-<slug>.jpg`,
numbered after the last image in `docs/workshop/images/` and listed in `docs/workshop.md`
(Images). Steps, in order:

1. Render the PNG and the JPG; look at the JPG (Read) before using it.
2. Add the two lines to `docs/workshop/description.txt`. Keep the substituted page under
   8,000 characters with margin (the game appends ~50): `python3 -c` the length after
   replacing `@VERSION@ @REV@ @NFILES@ @ID@`; aim for ≤ 7,900 and shorten an older caption if
   needed. The previous "New!" section moves down or goes when the next one arrives; the
   README keeps the long form.
3. Commit the script, the PNG, the JPG and the description and **push master first**: the
   `[img]` URLs are raw GitHub links to `master`, so the page shows a broken image until the
   push is public.
4. `scripts/workshop.sh --zip <the release zip>` re-stages `workshop.txt` from the description,
   copy it to `docs/workshop/workshop.txt`, commit, then the click sequence below. A
   description-only upload (same files) gets **no changelog entry** (`No content change
   detected` in `workshop_log.txt`): verify it on the item page
   (`curl -s https://steamcommunity.com/sharedfiles/filedetails/?id=3805285544 | grep -c 'New!'`)
   and by the image URL being reachable, not on the changelog page.

Preflight, in this order; stop at the first failure:

```bash
pgrep -fa '[P]rojectZomboid64'; pgrep -fa '[h]arness/run.sh'        # nothing running, no run.sh
tail -3 ~/.local/share/Steam/logs/connection_log.txt                  # ends in "[Logged On", no "Session Replaced"
ls ~/Zomboid/Lua/pzopt-harness.txt 2>/dev/null                        # must not exist (a stale flag file arms a run)
grep -E '^(id|title)=' ~/Zomboid/Workshop/PZ_Optimization/workshop.txt   # id=3805285544, staged by workshop.sh
```

If the connection log shows `Session Replaced` / `Logged Off`: `steam -shutdown`, wait for `pgrep -x steam`
to clear, `setsid steam &`, wait ~20 s, re-check (the cached login reconnects on its own; if it asks
for a password or Steam Guard, hand over to the maintainer).

Launch and drive (announce it first; the game boots to the main menu, no save is loaded). xdotool
coordinates are screen pixels / 1.25 on this KDE/XWayland desktop (5120x2160); a plain `xdotool click`
only hovers the game's buttons, use mousedown / sleep 0.15 / mouseup. `/tmp/wsclick.sh X Y SLEEP` did
exactly that on 2026-09-21; recreate it if gone. Take `spectacle -b -n -f -o` before every click and
downscale it (`ffmpeg -vf scale=1280:-1`) to check the expected screen is up and has focus — the
2026-09-21 retry clicked and typed into the desktop after the game lost focus.

| step | screen (1280-wide screenshot) | xdotool |
|---|---|---|
| `setsid steam -applaunch 108600 &`, wait for `[P]rojectZomboid64` + ~25 s | main menu | |
| WORKSHOP | (157, 467) | 502 1494 |
| Create and update items | (640, 223) | 2048 714 |
| the `PZ_Optimization` row (only entry; under the overlay graph) | (108, 74) | 346 238 |
| NEXT (Choose item directory) | (1199, 507) | 3837 1622 |
| NEXT (Edit item details: title/description/tags/Public from workshop.txt) | (1199, 507) | 3837 1622 |
| Edit Change Notes (Prepare to publish, Workshop ID 3805285544 shown) | (640, 300) | 2048 960 |
| click into the text box, `xdotool type --delay 12 "<notes>"`, ACCEPT | (656, 503) | 1600 600 / 2099 1610 |
| Upload to Steam Workshop now! | (640, 318) | 2048 1018 |
| native confirm dialog "WARNING: Steam Workshop upload requested!" → Ok | (746, 301) | 2387 963 |
| CLOSE on the "Publishing item" log (returns to the main menu) | (640, 503) | 2048 1610 |
| QUIT the game from the main menu | (146, 499) | 467 1597 |

Change notes: one paragraph, "Release <commit> (game revision <rev>). <what changed for users>.
Everything else is unchanged from the previous upload (...)". The upload is ~6 s.

Verify, never trust the in-game log (it prints "finished" after a failure too):

```bash
grep 3805285544 ~/.local/share/Steam/logs/workshop_log.txt | tail -3   # "Uploaded new content (ManifestID ...)" + "Upload finished ... : OK"
```
and fetch `https://steamcommunity.com/sharedfiles/filedetails/changelog/3805285544`: the new entry
must be the first one. A description-only update (same files) gets no changelog entry at all (`No content
change detected`): verify it on the item page instead. `result=8` / `Invalid Parameter` = the description
is over Steam's 8,000 characters (the game appends ~50 for Workshop ID / Mod ID): trim
`docs/workshop/description.txt`, re-stage, CLOSE the log and start the click sequence again from WORKSHOP
(the game re-reads workshop.txt on the way). `failed to update workshop item, result=2` in the game = `Failed to initialize
build on server (No Connection)` in `workshop_log.txt` = the Steam session is dead (see preflight).

Afterwards the animated `preview.gif` is gone (the in-game uploader sends `preview.png`); restoring it
is the maintainer's `steamcmd +login <user> +workshop_build_item ~/Zomboid/Workshop/PZ_Optimization/item.vdf +quit`
(`docs/workshop.md`, Images). `steamcmd` has no cached login here; never type the password.
