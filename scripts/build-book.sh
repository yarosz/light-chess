#!/usr/bin/env bash
# Builds the opening Book (tool/src/main/assets/book/) from the pinned Lichess dump (docs/book.md).
#
#   scripts/build-book.sh                  stream the dump (no local copy), check its SHA-256, write the Book
#   scripts/build-book.sh --out DIR        the same, into DIR instead of the assets (to compare two runs)
#   scripts/build-book.sh --from-filtered  rebuild from build/book/filtered.pgn (the kept games, saved by
#                                          the last streaming run) into build/book/rebuild and compare it
#                                          with the committed Book
#
# Needs curl, zstd and shasum. The dump is 5.47 GB compressed; it is streamed, never stored.
set -euo pipefail

URL="https://database.lichess.org/standard/lichess_db_standard_rated_2018-01.pgn.zst"
# From https://database.lichess.org/standard/sha256sums.txt
PINNED_SHA256="8ac6ff9d722a4bba1c1d72c700523408dff2e09cc52cbfe4e454289ca60e8d6b"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/build/book"
ASSETS="$ROOT/tool/src/main/assets/book"
CLI="$ROOT/build/book-build/install/book-build/bin/book-build"

mode=stream
out="$ASSETS"
case "${1:-}" in
  "") ;;
  --out) out="$(mkdir -p "$2" && cd "$2" && pwd)" ;;
  --from-filtered) mode=filtered ;;
  *) echo "usage: $0 [--out DIR | --from-filtered]" >&2; exit 2 ;;
esac

for tool in curl zstd shasum; do
  command -v "$tool" >/dev/null || { echo "build-book: $tool is not installed" >&2; exit 1; }
done

"$ROOT/gradlew" -p "$ROOT/scripts/book-build" --console=plain -q installDist
mkdir -p "$WORK"

if [ "$mode" = filtered ]; then
  [ -f "$WORK/filtered.pgn" ] || { echo "build-book: no $WORK/filtered.pgn; run a streaming build first" >&2; exit 1; }
  rm -rf "$WORK/rebuild"
  "$CLI" --out "$WORK/rebuild" --source-url "$URL" --dump-sha256 "$PINNED_SHA256" < "$WORK/filtered.pgn"
  # The rebuild reads only the kept games, so its "games.read" count differs; the Book must not.
  if cmp -s "$WORK/rebuild/book.bin" "$ASSETS/book.bin"; then
    echo "build-book: OK the rebuild from the kept games is byte-identical: $(shasum -a 256 "$ASSETS/book.bin" | cut -d' ' -f1)"
  else
    echo "build-book: FAIL the rebuild differs from $ASSETS/book.bin" >&2
    exit 1
  fi
  exit 0
fi

# Stream: curl -> tee (-> shasum through a fifo) -> zstd -> the builder. Write to a staging directory
# and publish only once the streamed bytes' SHA-256 matches the pin.
stage="$WORK/stage"
rm -rf "$stage" "$WORK/dump.fifo"
mkfifo "$WORK/dump.fifo"
shasum -a 256 < "$WORK/dump.fifo" | cut -d' ' -f1 > "$WORK/dump.sha256" &
sha_pid=$!
curl -sSfL "$URL" | tee "$WORK/dump.fifo" | zstd -d -c | \
  "$CLI" --out "$stage" --source-url "$URL" --dump-sha256 "$PINNED_SHA256" --save-filtered "$WORK/filtered.pgn"
wait "$sha_pid"
rm -f "$WORK/dump.fifo"

streamed="$(cat "$WORK/dump.sha256")"
if [ "$streamed" != "$PINNED_SHA256" ]; then
  echo "build-book: FAIL the streamed dump's SHA-256 is $streamed, not the pinned $PINNED_SHA256" >&2
  exit 1
fi
echo "build-book: dump SHA-256 matches the pin ($streamed)"
mkdir -p "$out"
cp "$stage/book.bin" "$stage/book-manifest.json" "$out/"
echo "build-book: wrote $out/book.bin ($(wc -c < "$out/book.bin" | tr -d ' ') bytes, sha256 $(shasum -a 256 "$out/book.bin" | cut -d' ' -f1))"
