#!/usr/bin/env bash
# Compile the class overrides in src/ against the game's projectzomboid.jar.
#
# Output: build/classes/ — a package tree of .class files that install.sh
# copies over the game directory, plus build/classes/pzopt/build-info.properties
# recording which game build they were compiled against.
#
# The overridden game classes (OVERRIDES below) are compiled from Vineflower
# output of the shipped jar (see scripts/regen-overrides.sh), with our changes
# on top. New helper classes live in src/pzopt/ under the pzopt.* package.
#
# Compiles with --release <game JRE version> so the bytecode the game's bundled
# JRE loads is never newer than it can read.
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/pz-env.sh"
JAR="$PZ_DIR/projectzomboid.jar"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$REPO/src"
BUILD="$REPO/build"
OUT="$BUILD/classes"
RELEASE="${RELEASE:-25}"   # java.class.version 69 in the shipped jar

# Game classes we shadow. Every inner class of these is shadowed too.
# The edited decompiled copies live in src/overrides/ (not committed); the two org.lwjglx classes are
# The Indie Stone's LWJGL 2 compatibility shim (HiDPI/Wayland fix, see docs/override-edits.md);
# TISLogoState is a from-scratch replacement in src/shims/ (committed).
OVERRIDES=(zombie/iso/IsoChunk zombie/iso/WorldStreamer zombie/iso/ChunkSaveWorker zombie/core/VBO/GLVertexBufferObject zombie/iso/fboRenderChunk/FBORenderCell zombie/GameWindow zombie/gameStates/TISLogoState org/lwjglx/opengl/Display org/lwjglx/input/Mouse zombie/fileSystem/FileSystemImpl zombie/tileDepth/TileDepthTextures zombie/core/textures/TextureIDAssetManager zombie/MapCollisionData zombie/iso/IsoMetaGrid zombie/scripting/ScriptParser zombie/iso/IsoMetaCell zombie/buildingRooms/BuildingRoomsEditor zombie/gameStates/GameLoadingState se/krka/kahlua/luaj/compiler/LuaCompiler zombie/core/skinnedmodel/advancedanimation/AnimationSet zombie/core/skinnedmodel/model/AnimationAssetManager zombie/fileSystem/TexturePackDevice zombie/scripting/objects/Item zombie/core/PerformanceSettings zombie/core/skinnedmodel/model/Model zombie/core/textures/ImageData zombie/iso/weather/fx/WeatherFxMask zombie/iso/objects/IsoLightSwitch se/krka/kahlua/j2se/KahluaTableImpl zombie/core/opengl/RenderThread zombie/iso/fboRenderChunk/FBORenderCutaways zombie/audio/parameters/ParameterZone zombie/iso/IsoChunkMap zombie/core/opengl/VBORenderer zombie/iso/IsoPuddles zombie/iso/weather/fx/ParticleRectangle zombie/iso/weather/fx/WeatherParticleDrawer)

[[ -f "$JAR" ]] || { echo "jar not found: $JAR" >&2; exit 1; }
command -v javac >/dev/null || { echo "javac not on PATH" >&2; exit 1; }

rm -rf "$OUT" "$BUILD/stock"
mkdir -p "$OUT" "$BUILD/stock"

echo "compiling src/ against $JAR (--release $RELEASE)"
mapfile -t sources < <(find "$SRC/overrides" "$SRC/shims" "$SRC/pzopt" -name '*.java' | sort)
javac --release "$RELEASE" -nowarn -Xlint:-options -parameters -g \
  -cp "$JAR" -d "$OUT" "${sources[@]}"

# Loose Lua under src/lua/ ships next to the classes: install copies build/classes/ onto the
# game dir, so build/classes/media/lua/client/pzopt/*.lua lands in media/lua/client/pzopt/.
if [[ -d "$SRC/lua" ]]; then
  mkdir -p "$OUT/media/lua"
  cp -r "$SRC/lua/." "$OUT/media/lua/"
fi

# Extract the stock copies of the overridden classes for comparison.
patterns=()
for c in "${OVERRIDES[@]}"; do patterns+=("$c.class" "$c\$*.class"); done
( cd "$BUILD/stock" && for pat in "${patterns[@]}"; do unzip -q -o "$JAR" "$pat" || [[ $? -eq 11 ]]; done )  # 11 = pattern matched nothing (a class without inner classes)

# Every class file the jar has for an override must exist in our output too,
# otherwise the loose top-level class would load against the jar's inner class.
missing=0
while IFS= read -r f; do
  [[ -f "$OUT/$f" ]] || { echo "MISSING from build: $f" >&2; missing=1; }
done < <(cd "$BUILD/stock" && find . -name '*.class' | sed 's|^\./||' | sort)
[[ $missing -eq 0 ]]

# Structural check: every member other game classes can link against
# (everything but private) must still exist with the same descriptor in our
# build. Added members are fine; removed or changed ones would break linking.
sig() { javap -p -cp "$1" "$2" 2>/dev/null | grep -vE '^\s*(private|Compiled from|})' | grep -v 'lambda\$' | sort; }
for c in "${OVERRIDES[@]}"; do
  cls="${c//\//.}"
  missing_members=$(comm -23 <(sig "$BUILD/stock" "$cls") <(sig "$OUT" "$cls"))
  if [[ -n "$missing_members" ]]; then
    echo "SIGNATURE MISMATCH in $cls — stock members missing or changed in build:" >&2
    echo "$missing_members" | head -20 >&2
    exit 1
  fi
done

# Record what we built against. The overrides read this at runtime and
# disable themselves if the loaded game's revision differs.
revision=$(javap -constants -cp "$JAR" zombie.GitVersion | sed -n 's/.*REVISION = "\([^"]*\)".*/\1/p')
[[ -n "$revision" ]] || { echo "could not read zombie.GitVersion.REVISION from jar" >&2; exit 1; }
jar_sha=$(sha256sum "$JAR" | cut -d' ' -f1)
{
  echo "# generated by scripts/build.sh $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "revision=$revision"
  echo "jar.sha256=$jar_sha"
  echo "jar.size=$(stat -c %s "$JAR")"
  echo "release=$RELEASE"
  echo "overrides=$(IFS=,; echo "${OVERRIDES[*]}")"
  # sha256 of each stock class we shadow, so a same-revision hotfix is caught too
  while IFS= read -r f; do
    echo "stock.$(echo "$f" | tr '/$' '._' | sed 's/\.class$//')=$(sha256sum "$BUILD/stock/$f" | cut -d' ' -f1)"
  done < <(cd "$BUILD/stock" && find . -name '*.class' | sed 's|^\./||' | sort)
} > "$OUT/pzopt/build-info.properties"

n=$(find "$OUT" -name '*.class' | wc -l)
echo "built $n class files into build/classes for game revision $revision"
