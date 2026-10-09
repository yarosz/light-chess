#!/usr/bin/env bash
# Build the Chess Tool the way Light's release builder does (light-sdk/builder: Dockerfile +
# bin/build-apk.sh), minus the container:
#   1. a clean copy of the SDK's committed files, like the image's baked SDK source;
#   2. warm the Gradle cache by building the SDK's own template tool with the image build's flags;
#   3. Light's extractor copies the allowlisted tool/ files from a clean clone of our committed HEAD;
#   4. an unsigned, minified release build with bin/build-apk.sh's flags (-DlightSdk.toolOnly=true,
#      -DlightSdk.abiFilters=arm64-v8a). Light's builder fetches what its warmed cache lacks through a
#      Maven proxy; this build runs --offline instead, which is stricter: Chess must need nothing the
#      SDK's template didn't pull in.
#
#   scripts/light-build.sh [SDK_DIR]     SDK_DIR defaults to the pinned light-sdk submodule
#
# Prints "light-build: OK sdk=<ref> files=<n>" on success. Needs git, python3, JDK 17, ANDROID_HOME.
set -euo pipefail

repo=$(git rev-parse --show-toplevel)
sdk=$(cd "${1:-$repo/light-sdk}" && pwd)
if [ -z "${1:-}" ]; then
  # Default SDK = the pinned submodule: it must be checked out at the commit this Chess commit pins.
  pinned=$(git -C "$repo" ls-tree HEAD light-sdk | awk '{print $3}')
  [ "$(git -C "$sdk" rev-parse HEAD)" = "$pinned" ] \
    || { echo "light-build: FAIL light-sdk is not at the pinned commit (git submodule update)" >&2; exit 1; }
fi
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
gradle=(./gradlew --no-daemon --no-build-cache --console=plain -q)

# 1. Clean SDK: committed files only (no local edits, no stale build/ or .gradle/ directories).
mkdir -p "$work/ws"
git -C "$sdk" archive --format=tar HEAD | tar -x -C "$work/ws"
if [ -n "${ANDROID_HOME:-}" ]; then echo "sdk.dir=$ANDROID_HOME" > "$work/ws/local.properties"; fi
ref=$(git -C "$sdk" describe --tags --always --dirty 2>/dev/null || echo unknown)

# 2. A dedicated Gradle home that only the SDK's template tool ever fills, like the image's
#    /opt/gradle-cache. Never the shared ~/.gradle: other builds (e.g. unit tests) would pre-fill it
#    and the offline check below would prove nothing. Kept per SDK commit so warm-ups are cheap.
sdk_sha=$(git -C "$sdk" rev-parse HEAD)
GRADLE_USER_HOME="${LIGHT_BUILD_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/light-build}/$sdk_sha"
export GRADLE_USER_HOME
mkdir -p "$GRADLE_USER_HOME"
# The flags of Light's builder: unsigned, and native libraries for the ABI its phones run only.
light_flags=(-DlightSdk.unsigned=true -DlightSdk.abiFilters=arm64-v8a)
(cd "$work/ws" && "${gradle[@]}" :tool:assembleRelease "${light_flags[@]}")   # builder/Dockerfile's warm-up
find "$work/ws" -path '*/build' -type d -prune -exec rm -rf {} +

# 3. Our committed files only: untracked local files must not make the build pass.
git clone --quiet --no-hardlinks "$repo" "$work/dev"
git -C "$work/dev" checkout --quiet "$(git -C "$repo" rev-parse HEAD)"
# Light builds releases from this exact file: it must bind to LightOS on the phone, never the emulator.
grep -qx 'serverPackage = "com.lightos"' "$work/dev/tool/lighttool.toml" \
  || { echo "light-build: FAIL tool/lighttool.toml serverPackage must be \"com.lightos\"" >&2; exit 1; }
mkdir -p "$work/out"
if ! (cd "$sdk/builder" && python3 -m lightbuilder prepare --dev-repo "$work/dev" \
      --workspace-tool "$work/ws/tool" --tool-path tool --output-dir "$work/out"); then
  cat "$work/out/error.json" 2>/dev/null >&2 || true
  echo "light-build: FAIL extraction policy" >&2
  exit 1
fi
files=$(python3 -c "import json,sys;print(len(json.load(open(sys.argv[1]))['files']))" "$work/out/extraction.json")

# 4. Offline, unsigned, minified release, with Light's reproducibility timestamp and build-apk.sh's
#    flags (toolOnly configures only the modules :tool needs).
SOURCE_DATE_EPOCH=$(git -C "$work/dev" log -1 --format=%ct) \
  bash -c 'cd "$1" && shift && "$@"' _ "$work/ws" "${gradle[@]}" --offline :tool:assembleRelease \
  "${light_flags[@]}" -DlightSdk.toolOnly=true
apk="$work/ws/tool/build/outputs/apk/release/tool-release-unsigned.apk"
[ -f "$apk" ] || { echo "light-build: FAIL no unsigned APK" >&2; exit 1; }
! unzip -Z1 "$apk" | grep '^lib/' | grep -qv '^lib/arm64-v8a/' \
  || { echo "light-build: FAIL native libraries beyond arm64-v8a (abiFilters)" >&2; exit 1; }

echo "light-build: OK sdk=$ref files=$files"
