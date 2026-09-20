#!/usr/bin/env bash
# Install, remove or inspect the PZ_Optimization class overrides on Linux from a release zip.
# Standalone: needs bash, curl or gh, and unzip (or python3 / bsdtar). No JDK, no clone.
#
#   ./install.sh                     # find the game, download the zip for its revision, install
#   ./install.sh --zip pzopt-b0bbce05d5-classes.zip
#   ./install.sh --from /path/to/pzopt-classes   # an unpacked zip, e.g. the Steam Workshop item
#   ./install.sh --dir /path/to/ProjectZomboid/projectzomboid
#   ./install.sh --status
#   ./install.sh --uninstall
#
# The zip holds the same class files for Windows and Linux (both Steam depots ship one jar);
# the runtime guard disables them, with one console.txt line, if the game revision differs.
# Files written are recorded in <game dir>/pzopt-installed.txt, the manifest scripts/pzopt.sh
# uses, so either tool can uninstall what the other installed. projectzomboid.jar is never
# modified.
#
# The zip is fetched from the GitHub releases with curl (GITHUB_TOKEN is used if set, to
# avoid API rate limits) or with the gh CLI when it is logged in; --zip skips the download.
# --from installs the same tree from a folder instead (no network, no unzip); a pzopt-classes/
# folder next to this script (the Steam Workshop item layout) is used automatically.
set -euo pipefail

REPO_SLUG="DiegoVillalobosFlores/PZ_Optimization"
mode=install; zip=""; from=""; dir="${PZ_DIR:-}"; tag=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --zip) zip="$2"; shift ;;
    --from) from="$2"; shift ;;
    --dir) dir="$2"; shift ;;
    --tag) tag="$2"; shift ;;
    --uninstall) mode=uninstall ;;
    --status) mode=status ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

die() { echo "error: $*" >&2; exit 1; }

# --- locate the game ----------------------------------------------------------------------

candidates() {
  local lib
  for vdf in "$HOME/.local/share/Steam/steamapps/libraryfolders.vdf" "$HOME/.steam/root/steamapps/libraryfolders.vdf" \
             "$HOME/.steam/steam/steamapps/libraryfolders.vdf" "$HOME/.var/app/com.valvesoftware.Steam/.local/share/Steam/steamapps/libraryfolders.vdf"; do
    [[ -f "$vdf" ]] || continue
    sed -n 's/^[[:space:]]*"path"[[:space:]]*"\(.*\)"/\1/p' "$vdf" | while IFS= read -r lib; do
      echo "$lib/steamapps/common/ProjectZomboid/projectzomboid"
      echo "$lib/steamapps/common/ProjectZomboid"
    done
  done
  echo "$HOME/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid"
  echo "$HOME/.steam/steam/steamapps/common/ProjectZomboid/projectzomboid"
}

if [[ -z "$dir" ]]; then
  while IFS= read -r c; do
    [[ -f "$c/projectzomboid.jar" && -f "$c/ProjectZomboid64.json" ]] && { dir="$c"; break; }
  done < <(candidates)
  [[ -n "$dir" ]] || die "game folder not found; pass --dir <folder containing projectzomboid.jar>"
fi
[[ -f "$dir/projectzomboid.jar" ]] || die "no projectzomboid.jar in $dir"
JAR="$dir/projectzomboid.jar"
JSON="$dir/ProjectZomboid64.json"
MANIFEST="$dir/pzopt-installed.txt"

jar_revision() {
  # zombie.GitVersion holds REVISION as a constant-pool string; no JDK needed to read it
  if command -v unzip >/dev/null; then unzip -p "$JAR" zombie/GitVersion.class
  else python3 -c 'import zipfile,sys; sys.stdout.buffer.write(zipfile.ZipFile(sys.argv[1]).read("zombie/GitVersion.class"))' "$JAR"
  fi | grep -aoE '\b[0-9a-f]{10}\b' | head -1
}
REV=$(jar_revision || true)

# --- status / uninstall -------------------------------------------------------------------

if [[ $mode == status ]]; then
  echo "game dir:      $dir"
  echo "game revision: ${REV:-unknown}"
  if [[ -f "$MANIFEST" ]]; then
    echo "installed:     yes, for $(sed -n 's/^# revision=\([^ ]*\).*/\1/p' "$MANIFEST") ($(grep -vc '^#' "$MANIFEST") files)"
    bad=0
    while read -r rel sha; do
      [[ "$rel" == \#* || -z "$rel" ]] && continue
      if [[ ! -f "$dir/$rel" ]]; then echo "  MISSING  $rel"; bad=1
      elif [[ "$(sha256sum "$dir/$rel" | cut -d' ' -f1)" != "$sha" ]]; then echo "  MODIFIED $rel"; bad=1; fi
    done < "$MANIFEST"
    [[ $bad -eq 0 ]] && echo "  all files present and unchanged"
  else
    echo "installed:     no"
  fi
  [[ -f "$dir/pzopt.properties" ]] && { echo "pzopt.properties:"; sed 's/^/  /' "$dir/pzopt.properties"; }
  exit 0
fi

if [[ $mode == uninstall ]]; then
  list=""
  if [[ -f "$MANIFEST" ]]; then list=$(grep -v '^#' "$MANIFEST" | cut -d' ' -f1)
  elif [[ -f "$dir/pzopt-files.txt" ]]; then list=$(cat "$dir/pzopt-files.txt")
  else echo "not installed (no pzopt-installed.txt or pzopt-files.txt in $dir)"; exit 0
  fi
  n=0
  while IFS= read -r rel; do
    [[ -z "$rel" ]] && continue
    [[ -f "$dir/$rel" ]] && { rm -f "$dir/$rel"; n=$((n+1)); }
    d=$(dirname "$rel")
    while [[ "$d" != "." && -d "$dir/$d" && -z "$(ls -A "$dir/$d")" ]]; do rmdir "$dir/$d"; d=$(dirname "$d"); done
  done <<< "$list"
  rm -f "$MANIFEST" "$dir/pzopt-files.txt"
  echo "removed $n files; projectzomboid.jar was never modified"
  echo "caches under ~/Zomboid/pzopt/ (anims, packs, framecap.ini, options.ini) can be deleted by hand"
  exit 0
fi

# --- install ------------------------------------------------------------------------------

for pid in $(pgrep -f '[P]rojectZomboid64' || true); do
  [[ "$(readlink -f "/proc/$pid/cwd" 2>/dev/null)" == "$(readlink -f "$dir")" ]] && die "the game is running from $dir; close it first"
done
[[ -f "$MANIFEST" ]] && die "already installed (see --status); run --uninstall first"
[[ -n "$REV" ]] || die "could not read the game revision from $JAR"

# the launcher must search "." before the jar or loose classes never load
if command -v python3 >/dev/null; then
  python3 - "$JSON" <<'EOF' || die "$JSON does not list \".\" before projectzomboid.jar on the classpath; loose classes would never load"
import json,sys
cp=json.load(open(sys.argv[1])).get("classpath",[])
sys.exit(0 if "." in cp and "projectzomboid.jar" in cp and cp.index(".") < cp.index("projectzomboid.jar") else 1)
EOF
else
  tr -d '\n ' < "$JSON" | grep -q '"classpath":\[".","projectzomboid.jar"' || die "$JSON classpath does not put \".\" before projectzomboid.jar"
fi

if [[ -z "$zip" && -z "$from" ]]; then
  sibling="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/pzopt-classes"
  [[ -f "$sibling/pzopt/build-info.properties" ]] && from="$sibling"
fi
if [[ -n "$from" ]]; then
  [[ -f "$from/pzopt/build-info.properties" ]] || die "$from is not an unpacked PZ_Optimization release (no pzopt/build-info.properties)"
  echo "installing from folder $from"
elif [[ -z "$zip" ]]; then
  pattern="pzopt-${REV}-classes.zip"
  tmp=$(mktemp -d)
  if command -v gh >/dev/null && gh auth status >/dev/null 2>&1; then
    if [[ -z "$tag" ]]; then
      tag=$(gh release list -R "$REPO_SLUG" --json tagName -q '.[].tagName' | grep -- "-${REV}-\|-${REV}\$" | head -1 || true)
      [[ -n "$tag" ]] || die "no release for game revision $REV (your game is a build these classes were not built for)"
    fi
    echo "downloading $pattern from release $tag"
    gh release download "$tag" -R "$REPO_SLUG" -p "$pattern" -D "$tmp"
  else
    command -v curl >/dev/null || die "need curl (or the gh CLI) to download; or pass --zip"
    auth=(); [[ -n "${GITHUB_TOKEN:-}" ]] && auth=(-H "Authorization: Bearer $GITHUB_TOKEN")
    api="https://api.github.com/repos/$REPO_SLUG/releases"
    rels=$(curl -fsSL "${auth[@]}" -H "Accept: application/vnd.github+json" "$api?per_page=50") || die "could not list releases of $REPO_SLUG"
    found=$(python3 - "$rels" "$pattern" "$tag" <<'EOF2'
import json,sys
rels,pattern,tag=json.loads(sys.argv[1]),sys.argv[2],sys.argv[3]
for r in rels:
    if tag and r["tag_name"]!=tag: continue
    for a in r["assets"]:
        if a["name"]==pattern: print(r["tag_name"], a["url"]); sys.exit(0)
sys.exit(1)
EOF2
) || die "no release has $pattern (your game revision $REV is a build these classes were not built for)"
    tag=${found%% *}; url=${found#* }
    echo "downloading $pattern from release $tag"
    curl -fsSL "${auth[@]}" -H "Accept: application/octet-stream" -o "$tmp/$pattern" "$url"
  fi
  zip="$tmp/$pattern"
fi
[[ -n "$from" || -f "$zip" ]] || die "zip not found: $zip"

list_zip() {
  if command -v unzip >/dev/null; then unzip -Z1 "$1"
  else python3 -c 'import zipfile,sys; print("\n".join(n for n in zipfile.ZipFile(sys.argv[1]).namelist() if not n.endswith("/")))' "$1"; fi
}
extract_zip() {
  if command -v unzip >/dev/null; then unzip -q -n "$1" -d "$2"
  elif command -v bsdtar >/dev/null; then bsdtar -xkf "$1" -C "$2"
  else python3 -c 'import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "$1" "$2"; fi
}

read_zip_entry() {
  if command -v unzip >/dev/null; then unzip -p "$1" "$2"
  else python3 -c 'import zipfile,sys; sys.stdout.buffer.write(zipfile.ZipFile(sys.argv[1]).read(sys.argv[2]))' "$1" "$2"; fi
}
# the same three operations on a folder: list, read one entry, copy without overwriting
list_dir() { (cd "$1" && find . -type f | sed 's#^\./##'); }
copy_dir() {
  local rel
  while IFS= read -r rel; do
    [[ -z "$rel" ]] && continue
    mkdir -p "$2/$(dirname "$rel")"
    cp -n "$1/$rel" "$2/$rel"
  done < <(list_dir "$1")
}

if [[ -n "$from" ]]; then
  src="$from"
  files=$(list_dir "$from" | LC_ALL=C sort)
  zip_rev=$(sed -n 's/^revision=//p' "$from/pzopt/build-info.properties")
else
  src="$zip"
  files=$(list_zip "$zip" | grep -v '/$' | LC_ALL=C sort)
  echo "$files" | grep -qx 'pzopt/build-info.properties' || die "$zip is not a PZ_Optimization release zip"
  zip_rev=$(read_zip_entry "$zip" pzopt/build-info.properties | sed -n 's/^revision=//p')
fi
if [[ "$zip_rev" != "$REV" ]]; then
  die "$src was built for game revision $zip_rev but this game is $REV; the classes would disable themselves. Get the build for $REV"
fi
while IFS= read -r rel; do
  [[ -e "$dir/$rel" ]] && die "refusing to overwrite existing file: $dir/$rel (a previous install? run --uninstall)"
done <<< "$files"

jar_before=$(sha256sum "$JAR" | cut -d' ' -f1)
if [[ -n "$from" ]]; then copy_dir "$from" "$dir"; else extract_zip "$zip" "$dir"; fi
{
  echo "# files written by install.sh — do not edit"
  echo "# revision=$zip_rev installed=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  while IFS= read -r rel; do echo "$rel $(sha256sum "$dir/$rel" | cut -d' ' -f1)"; done <<< "$files"
} > "$MANIFEST"
jar_after=$(sha256sum "$JAR" | cut -d' ' -f1)
[[ "$jar_before" == "$jar_after" ]] || die "projectzomboid.jar changed during install (this should be impossible)"
[[ -n "${tmp:-}" ]] && rm -rf "$tmp"

echo "installed $(echo "$files" | wc -l) files into $dir for game revision $zip_rev; projectzomboid.jar untouched"
echo "launch from Steam; ~/Zomboid/console.txt shows one '[pzopt] loaded override ... active' line per class"
echo "settings: Options > Optimizations in the game, or $dir/pzopt.properties"
