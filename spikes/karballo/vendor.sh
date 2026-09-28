#!/bin/sh
# Flatten Karballo (karballo-common + the useful part of karballo-jvm) into a plain Kotlin/JVM tree
# that compiles with Kotlin 2.3.20 and passes the Light plugin scan.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
K=$HERE/karballo
DST=$HERE/flat/engine/src/main/kotlin
rm -rf "$DST"; mkdir -p "$DST"
cp -R "$K/karballo-common/src/main/kotlin/" "$DST/"
cp -R "$K/karballo-jvm/src/main/kotlin/" "$DST/"
cd "$DST/karballo"
# UCI front end (stdin/stdout/exit), JVM thread wrapper, PGN file reader (java.io),
# classpath-resource book (javaClass.getResourceAsStream) and JVM platform utils (System.exit/gc).
rm -f Main.kt uci/Uci.kt search/SearchEngineThreaded.kt pgn/PgnFile.kt book/FileBook.kt util/JvmPlatformUtils.kt
rmdir uci
cp "$HERE/overlay/LightPlatformUtils.kt" util/
cp "$HERE/overlay/PolyglotBook.kt" book/
# Kotlin 1.2 -> 2.3: String/Char toLowerCase/toUpperCase are deprecated at ERROR level.
sed -i '' 's/toString()\.toLowerCase()/toString().lowercase()/' Board.kt
sed -i '' 's/})\.toLowerCase()/}).lowercase()/' pgn/PgnParser.kt
sed -i '' 's/\.toLowerCase()/.lowercaseChar()/g; s/\.toUpperCase()/.uppercaseChar()/g' Board.kt Move.kt
# Logger prints to stdout by default; silence it.
sed -i '' 's/var noLog = false/var noLog = true/' log/Logger.kt
# Stop from another thread: stop() writes these, the search thread polls them.
sed -i '' -e 's/^    private var stop = false/    @Volatile private var stop = false/' \
  -e 's/^    private var thinkToTime: Long = 0/    @Volatile private var thinkToTime: Long = 0/' \
  -e 's/^    private var thinkToNodes = 0/    @Volatile private var thinkToNodes = 0/' search/SearchEngine.kt
# Lazy singleton of attack tables is not thread-safe.
sed -i '' 's/^        fun getInstance(): BitboardAttacks {/        @Synchronized fun getInstance(): BitboardAttacks {/' bitboard/BitboardAttacks.kt
