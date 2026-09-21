#!/usr/bin/env bash
# Build the release zip of the class overrides (Windows and Linux) and (optionally) publish it as a
# GitHub release asset.
#
#   scripts/release.sh            # build + test + zip into build/pzopt-<rev>-classes.zip
#   scripts/release.sh --publish  # ...and gh release create win-<rev>-<commit> with the zip + install.sh/.ps1
#   scripts/release.sh --publish --notes "extra sentence for the release body"
#
# The zip is the flat content of build/classes/ (class files, media/lua, pzopt/build-info)
# plus a manifest pzopt-files.txt listing every entry, which the Windows uninstall step in
# docs/windows-test.md reads back. Nothing is compiled on Windows: the runtime guard
# (pzopt.Overrides) compares the game revision and the sha256 of every shadowed stock class
# against the Windows jar and runs stock if anything differs.
#
# Publishing requires HEAD to be pushed (the tag targets the full commit SHA) and a clean
# tree under src/ so the tag really describes the bytes in the zip.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"

publish=0; extra_notes=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --publish) publish=1 ;;
    --notes) extra_notes="$2"; shift ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

if [[ $publish -eq 1 ]]; then
  command -v gh >/dev/null || { echo "gh not on PATH" >&2; exit 1; }
  if [[ -n "$(git status --porcelain -- src scripts/build.sh)" ]]; then
    echo "uncommitted changes under src/ (or build.sh); commit or stash before publishing" >&2
    exit 1
  fi
  git fetch -q origin
  if ! git merge-base --is-ancestor HEAD origin/master; then
    echo "HEAD is not on origin/master; push first (the release tag targets this commit)" >&2
    exit 1
  fi
fi

scripts/build.sh
scripts/test.sh

rm -f build/pzopt-files.txt build/classes/pzopt-files.txt
# no `zip` binary on this machine; Python's zipfile writes the same archive
read -r zipname sha nfiles < <(python3 - <<'EOF'
import os, re, zipfile, hashlib
root = 'build/classes'
rev = re.search(r'^revision=(\S+)', open(f'{root}/pzopt/build-info.properties').read(), re.M).group(1)
files = sorted(os.path.relpath(os.path.join(d, f), root) for d, _, fs in os.walk(root) for f in fs)
name = f'build/pzopt-{rev}-classes.zip'
with zipfile.ZipFile(name, 'w', zipfile.ZIP_DEFLATED) as z:
    for f in files:
        z.write(os.path.join(root, f), f)
    z.writestr('pzopt-files.txt', '\n'.join(files + ['pzopt-files.txt']) + '\n')
print(name, hashlib.sha256(open(name, 'rb').read()).hexdigest(), len(files) + 1)
EOF
)
rev=$(sed -n 's/^revision=//p' build/classes/pzopt/build-info.properties)
echo "wrote $zipname ($nfiles manifest entries, $(stat -c %s "$zipname") bytes)"
echo "sha256 $sha"

[[ $publish -eq 1 ]] || exit 0

short=$(git rev-parse --short HEAD)
full=$(git rev-parse HEAD)
tag="win-${rev}-${short}"
version=$(sed -n 's/.*Build \(42\.[0-9.]*\).*/\1/p' docs/windows-test.md | head -1)
if gh release view "$tag" >/dev/null 2>&1; then
  echo "release $tag already exists; delete it or commit first" >&2
  exit 1
fi
notes="Prebuilt class overrides for Windows, Linux and macOS, built $(date -u +%Y-%m-%d) from $short for game revision $rev${version:+ (Build $version)}."
[[ -n "$extra_notes" ]] && notes="$notes $extra_notes"
notes="$notes Install with install.ps1 (Windows) or install.sh (Linux, macOS) from this release, or unpack the zip into the game folder by hand (README; $nfiles manifest entries). sha256 $sha"
gh release create "$tag" "$zipname" install.sh install.ps1 --target "$full" \
  --title "Windows build${version:+ ($version / $rev)} from $short" --notes "$notes"
gh release view "$tag" --json url,assets -q '.url, (.assets[] | .name + " " + (.size|tostring))'
