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
