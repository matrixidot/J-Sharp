#!/usr/bin/env bash
# Builds trimmed Java 25 runtimes for the J# CLI and VS Code extension (D099), one per platform,
# into build/runtimes/<platform>. jlink links another platform's modules as long as the versions
# match exactly, so one Linux or macOS machine builds them all from the same Temurin release: the
# host's JDK runs jlink, and each platform's jmods (a separate Temurin download since JDK 24) are
# linked.
#
#   scripts/runtimes.sh                      all platforms
#   scripts/runtimes.sh linux-x64 win32-x64  some
set -euo pipefail
cd "$(dirname "$0")/.."

PLATFORMS=("$@")
[ ${#PLATFORMS[@]} -gt 0 ] || PLATFORMS=(linux-x64 linux-arm64 win32-x64 darwin-x64 darwin-arm64)
CACHE=build/jdks
OUT=build/runtimes
mkdir -p "$CACHE" "$OUT"

# The newest Temurin 25 release, pinned for every download of this run.
RELEASE=$(curl -fsSL "https://api.adoptium.net/v3/assets/latest/25/hotspot?image_type=jdk&vendor=eclipse&os=linux&architecture=x64" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["release_name"])')
echo "Temurin $RELEASE"

adoptium() { # <platform> -> "<os> <arch>"
  case "$1" in
    linux-x64) echo "linux x64" ;;
    linux-arm64) echo "linux aarch64" ;;
    win32-x64) echo "windows x64" ;;
    darwin-x64) echo "mac x64" ;;
    darwin-arm64) echo "mac aarch64" ;;
    *) echo "unknown platform $1" >&2; exit 2 ;;
  esac
}

download() { # <platform> <image type: jdk|jmods> -> the unpacked directory, downloaded once
  local p=$1 type=$2 dir="$CACHE/$RELEASE/$1-$2"
  if [ ! -d "$dir/home" ]; then
    read -r os arch < <(adoptium "$p")
    local release
    release=$(python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$RELEASE")
    rm -rf "$dir" && mkdir -p "$dir/unpack"
    echo "downloading the $p $type" >&2
    curl -fsSL "https://api.adoptium.net/v3/binary/version/$release/$os/$arch/$type/hotspot/normal/eclipse" \
      -o "$dir/archive"
    if [ "$os" = windows ]; then
      python3 -m zipfile -e "$dir/archive" "$dir/unpack"
    else
      tar -xzf "$dir/archive" -C "$dir/unpack"
    fi
    rm "$dir/archive"
    local top
    top=$(find "$dir/unpack" -mindepth 1 -maxdepth 1 -type d | head -1)
    if [ -d "$top/Contents/Home" ]; then top="$top/Contents/Home"; fi
    mv "$top" "$dir/home"
    rm -rf "$dir/unpack"
  fi
  echo "$dir/home"
}

jmods() { # <platform> -> the directory holding its .jmod files
  dirname "$(find "$(download "$1" jmods)" -name java.base.jmod | head -1)"
}

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) HOST=linux-x64 ;;
  Linux-aarch64) HOST=linux-arm64 ;;
  Darwin-x86_64) HOST=darwin-x64 ;;
  Darwin-arm64) HOST=darwin-arm64 ;;
  *) echo "build runtimes on Linux or macOS" >&2; exit 2 ;;
esac
JLINK="$(download "$HOST" jdk)/bin/jlink"

# Everything a J# program, the compiler (javac for Java sources) and Gradle may need; not the
# JDK's development tools.
SKIP='^(jdk\.incubator\.vector|jdk\.hotspot\.agent|jdk\.jpackage|jdk\.jlink|jdk\.jdeps|jdk\.jartool|jdk\.javadoc|jdk\.jshell|jdk\.jconsole|jdk\.jstatd|jdk\.editpad|jdk\.internal\.ed|jdk\.internal\.opt|jdk\.internal\.le|jdk\.internal\.md|jdk\.jsobject|jdk\.graal\.compiler.*|jdk\.internal\.vm\.ci)$'

for p in "${PLATFORMS[@]}"; do
  mods=$(jmods "$p")
  modules=$(ls "$mods" | sed 's/\.jmod$//' | grep -Ev "$SKIP" | paste -sd, -)
  rm -rf "${OUT:?}/$p"
  "$JLINK" --module-path "$mods" --add-modules "$modules" \
    --strip-debug --no-man-pages --no-header-files --compress zip-9 \
    --output "$OUT/$p"
  echo "$p: $(du -sh "$OUT/$p" | cut -f1) in $OUT/$p"
done
