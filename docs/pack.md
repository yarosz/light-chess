# The Puzzle Pack

The Pack is the set of Puzzles that ships inside the Tool (ADR 0003). It lives in
`tool/src/main/assets/pack/`: one `<band>.txt` per Band and a `manifest.json`. The Tool reads it
through `Pack` (`tool/src/main/kotlin/com/yarosz/chess/puzzles/`), one Band file at a time.

## Source and licence

The Puzzles come from the [Lichess puzzle database](https://database.lichess.org/#puzzles), released
under CC0 1.0. Credit: lichess.org. Each Puzzle keeps its Lichess id, so a Puzzle can be opened at
`https://lichess.org/training/<id>`.

The Pack is pinned to one dump:

| | |
|---|---|
| URL | `https://database.lichess.org/lichess_db_puzzle.csv.zst` |
| Date (Last-Modified) | 2026-09-09 |
| SHA-256 | `95fd454bec9efe8f940d5863d5db4c57474f281a865834997bd8cb5d6a149bb9` |
| Rows | 6,100,952 |

## File format

Each Band file has one Puzzle per line, sorted by Puzzle Rating and then id:

```
id;FEN;UCI moves separated by spaces;rating;RD;themes separated by spaces
```

The FEN is the Position before the opponent's setup Move. The first UCI Move is that setup Move,
and the rest is the Solution, ending with the user's Move. Files are named by the Band's lower bound,
zero-padded: `0400.txt` holds 400 to 499 (and anything lower), `2800.txt` holds 2800 and up.

`manifest.json` records the dump's date and SHA-256, the filters, the seed, the counts at each step,
and for each Band its file, Puzzle count (`strict` and `fromFill` per tier), `medianRd` (the lower
median of its Puzzles' rating deviations), rating range, size and SHA-256. The build also prints this
per-Band report. `packSha256` is the
SHA-256 of the manifest's other fields as canonical JSON (sorted keys, no spaces). The save file
keeps it to notice a new Pack (A8, F1).

## How the Puzzles are chosen

1. Keep a Puzzle if it passes the strict filter (A1): Popularity >= 95, NbPlays >= 1000, rating
   deviation <= 80. Also keep it as a fill candidate if it passes R1.4's looser filter:
   Popularity >= 85, NbPlays >= 500, rating deviation <= 90.
2. Keep one Puzzle per source game (the Lichess game id, without `/black` or the `#ply` fragment):
   the strict one if there is one, then the most played, then the lowest id.
3. Put each Puzzle in its 100-point Band. Ratings below 400 go in the 400 Band, and every rating of
   2800 or more goes in one 2800 Band.
4. In each Band, take 2,000 Puzzles. The top Band takes every Puzzle it has. A Band with 2,000 or
   more strict Puzzles takes 2,000 of them. A Band short of 2,000 takes every strict Puzzle, then
   fill Puzzles to make up the count (expert ruling, decision log "Pack fill").
5. Theme mixing: group the Puzzles being drawn by motif (their rarest tactical theme in that Band,
   ignoring themes about length, phase, source or evaluation). Share the places between motifs in
   proportion to the square root of each motif's size. Within each motif, strict Puzzles are drawn
   at random; fill Puzzles are drawn by Popularity, then NbPlays, highest first, with a random
   order breaking ties. The random seed is fixed, so the output is the same every time.

The strict filter alone leaves only 33,707 Puzzles: Bands 400 and 2700+ are empty and only 800 to
2200 reach 2,000. The fill step brings the Pack to 46,971 Puzzles, of which 13,264 come from the
looser filter, all in Bands 400 to 700 and 2300 and up:

| Band | Strict | Fill | Total | Median RD |
|---|---|---|---|---|
| 400 | 0 | 608 | 608 | 88 |
| 500 | 7 | 1,993 | 2,000 | 86 |
| 600 | 141 | 1,859 | 2,000 | 82 |
| 700 | 1,836 | 164 | 2,000 | 78 |
| 800 to 2200 | 2,000 | 0 | 2,000 | 75 to 78 |
| 2300 | 1,259 | 741 | 2,000 | 77 |
| 2400 | 389 | 1,611 | 2,000 | 79 |
| 2500 | 71 | 1,929 | 2,000 | 82 |
| 2600 | 4 | 1,996 | 2,000 | 85 |
| 2700 | 0 | 2,000 | 2,000 | 88 |
| 2800 | 0 | 363 | 363 | 89 |

The Pack is 6,535,382 bytes. The top Band has 363 Puzzles (2800 to 2983). Splitting off a 2900+ Band would leave
it with a few dozen, too few for a window to draw from.

## Regenerating

Needs Python 3.10 or later and the `zstd` command-line tool.

```sh
scripts/build-pack.py                      # dump in build/lichess (gitignored)
scripts/build-pack.py --dump-dir DIR       # or LICHESS_DUMP_DIR=DIR
./gradlew :tool:testDebugUnitTest          # CommittedPackTest checks every Puzzle
```

The first run downloads the 304 MB dump. Later runs reuse it. Never commit the dump or a decompressed
CSV. The script refuses a dump whose date or SHA-256 differs from the pinned one. To move to a newer
dump, run it with `--allow-new-dump`, then pin the new date and SHA-256 in the script and in this
file, in the same change as the new Pack.

Two runs give byte-identical files. To check, build into a scratch directory with
`--out DIR` and compare it with `diff -r DIR tool/src/main/assets/pack`.

`CommittedPackTest` checks what Light's builder will see: only `.txt` and `.json` files, each under
5 MB. It checks that the manifest's hashes match the files. It also parses every Puzzle with the
rules core: the FEN, every Move in order, the setup Move being the opponent's, and a checkmate at
the end of every Puzzle tagged `mate`.
