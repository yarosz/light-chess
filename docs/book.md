# The opening Book

The Book is the set of opening Moves the computer may play instead of searching, in the first plies
of a Game (decision log, "Opening book rulings"). It lives in `tool/src/main/assets/book/`:
`book.bin`, a Polyglot book, and `book-manifest.json`. The Tool reads it through `Book`
(`tool/src/main/kotlin/com/yarosz/chess/book/`); `BookPolicy` decides, per Level, whether and what to
play from it.

## Source and licence

The games come from the [Lichess standard rated games database](https://database.lichess.org/#standard_games),
released under CC0 1.0. Credit: lichess.org (NOTICE, and About through `UiCopy.ABOUT_BOOK`).

The Book is pinned to one monthly dump, streamed and never stored:

| | |
|---|---|
| URL | `https://database.lichess.org/standard/lichess_db_standard_rated_2018-01.pgn.zst` |
| Size | 5,472,079,625 bytes (Last-Modified 2022-11-05) |
| SHA-256 | `8ac6ff9d722a4bba1c1d72c700523408dff2e09cc52cbfe4e454289ca60e8d6b` (Lichess's `standard/sha256sums.txt`; the build checks the streamed bytes against it) |
| Games | 17,945,784 |

Not used: Lichess broadcasts (CC BY-SA, not CC0), the masters data (API only), nikonoel's books (no
licence), TWIC (personal use only), Fruit's `book_small.bin` (unclear licence).

## File format

`book.bin` follows the Polyglot book format, https://hgm.nubati.net/book_format.html: 16-byte
entries, big-endian, sorted by key (unsigned).

| Bytes | Field |
|---|---|
| 0-7 | key: the Position's Polyglot key (`Position.polyglotKey` in the rules core) |
| 8-9 | move: to file, to row, from file, from row (3 bits each), then the promotion piece (none 0, knight 1, bishop 2, rook 3, queen 4) |
| 10-11 | weight: the Move's game count, scaled so each Position's most played Move weighs 65,535 |
| 12-15 | learn: always 0 |

Castling is written as the king taking its own rook (e1h1, e1a1, e8h8, e8a8), as the spec requires;
the reader turns it into our king's step (e1g1). Within one key, entries go by weight, highest
first, then by move code. The key uses the spec's Random64 table, generated from the spec page by
`scripts/polyglot-random64.sh` (`--check` compares the committed table with the page). Unlike our
canonical FEN, the key counts the en passant file whenever a pawn of the side to move stands beside
the pawn that just moved two squares, legal capture or not, as the spec says.

`book-manifest.json` records the dump's URL and SHA-256, every filter parameter (including the
minimum count actually used), the game counts, the number of Positions and entries, and the size and
SHA-256 of `book.bin`. `CommittedBookTest` checks that the hash and counts match the file.

## How the Book is built

`scripts/build-book.sh` (`mise run book`) runs
`curl <url> | tee <fifo for shasum -a 256> | zstd -d -c | book-build`, where `book-build` is a Kotlin
JVM program (`scripts/book-build/`) compiled from the Tool's own rules core and `Book`, so the build
and the Tool share one key function, one move encoding and one reader.

1. Keep a game when both players are rated 2200 or more, its estimated time (base + 40 x increment)
   is at least 180 seconds, its Termination is "Normal", its Result is a win or a draw, and it starts
   from the normal start Position.
2. Parse its first 20 plies with our SAN parser. For each ply, count the (Position, Move) pair, and
   the points the side playing the Move scored in the game.
3. Keep a pair seen at least 10 times that is at least 5% of the games from its Position. Drop a Move
   played in 30 games or more that scored under 40% for the side playing it.
4. Keep only the Positions the Book reaches from the start Position by book Moves within 20 plies.
   The others could never be used, since the computer leaves the Book for good once a Game leaves
   it. Every entry is decoded and checked legal by the rules core on the way.
5. If more than 40,000 entries remain (640 KB), raise the minimum count by one until they fit. The
   January 2018 dump doesn't need it: the minimum stays at 10.
6. Scale the weights, sort, write `book.bin` and the manifest.

The script writes to `build/book/stage` first, and publishes to the assets only when the SHA-256 of
the streamed bytes equals the pin. The streaming run takes about 5 minutes on a fast connection.

Result (January 2018): 77,509 games kept, 533,436 Positions counted, 7,082 entries in 4,801
Positions, 113,312 bytes, SHA-256
`0e5b8eb75b6556cf66d8a9526c682abdc32bc340d7565c7137be7b404d8c311c`.

## Regenerating

Needs `curl`, `zstd` and `shasum`, and JDK 17 (mise's).

```sh
mise run book                          # stream the dump, write tool/src/main/assets/book/
scripts/build-book.sh --out DIR        # the same into DIR, to compare two streaming runs
scripts/build-book.sh --from-filtered  # rebuild from build/book/filtered.pgn, compare with the assets
scripts/polyglot-random64.sh --check   # the key table still matches the spec
./gradlew :tool:testDebugUnitTest      # PolyglotKeyTest, BookTest, BookPolicyTest, CommittedBookTest
```

The build is deterministic: it counts, then sorts, so the same games give the same bytes in any
order. Two streaming runs give byte-identical `book.bin` files, and so does a rebuild from the kept
games alone (`build/book/filtered.pgn`, saved by each streaming run, gitignored). Never commit the
dump or `build/book/`.

To move to another dump, change the URL and SHA-256 in `scripts/build-book.sh`, `CommittedBookTest`
and this file in the same change as the new Book.

## Using the Book (Levels)

`BookPolicy(level).pick(book, start, moves, seed)` returns the book Move for the computer, or null
for the engine (book ruling 4):

- Level 1: never.
- Levels 2-4: to ply 8, a pick proportional to the square root of the weight.
- Levels 5-8: to ply 20, a pick proportional to the weight.
- Once the Game leaves the Book (a Position with no entry, or a Move the Book doesn't offer, by
  either side), the engine plays the rest of the Game, even after a transposition back into it.

The ply limit counts real Game plies. Each pick draws from a `Random` seeded by the Game's book seed
(stored with the saved Game by the caller) and the ply, so a resume or a Takeback replays the same
pick. The strip says nothing when a Move comes from the Book, and the Game Hint stays the engine's
best Move.
