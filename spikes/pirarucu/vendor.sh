#!/bin/sh
# Flatten pirarucu-common into a plain Kotlin/JVM source tree.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
SRC=$HERE/pirarucu/pirarucu-common/src/main/kotlin
DST=$HERE/flat/engine/src/main/kotlin
rm -rf "$DST"; mkdir -p "$DST"; cp -R "$SRC/" "$DST/"
cd "$DST/pirarucu"
# UCI front end (stdin/exit/getVersion), stdout listener, EPD tuning helper: not needed in a Tool.
rm -f uci/UciInput.kt uci/IInputHandler.kt uci/UciOutput.kt search/SimpleSearchInfoListener.kt
rm -rf util/epd
# Only real race: stop flag written by UI thread, read by search thread.
sed -i '' 's/^    var stop = false/    @Volatile var stop = false/' search/SearchOptions.kt
cp "$HERE/PlatformSpecific.kt" util/PlatformSpecific.kt
