# A committed Puzzle Pack and no network in v1

The Pack is about 50,000 Puzzles from the Lichess puzzle database (CC0): 2,000 per 100-point Band
from 400 to 2800, filtered for quality and one per source game. It is written as one plain-text file
per Band (`id;FEN;UCI moves;rating;RD;themes`), about 6.5 MB in all, and committed to the repo. A
committed script regenerates it from a pinned dump (2026-09-09, recorded with its SHA-256), so
anyone can check the committed files against their source. v1 declares no INTERNET permission, and
the Pack grows only through Tool updates.

Committing about 6.5 MB of generated data looks wrong, but Light's builder builds offline from the
public commit, so the Pack can't be fetched or generated at build time. Plain text reuses the FEN
and UCI parsers the rules core needs anyway, stays readable when debugging, and fits the 5 MB
per-file asset limit Band by Band. "Nothing leaves the phone" is the whole privacy story for v1.

## Considered Options

- A bit-packed binary Pack: smaller, but space isn't the constraint, and it adds a class of packing
  bugs.
- Downloading more Puzzles: it needs the INTERNET permission and a network error story for a
  feature that 2,000 Puzzles per Band doesn't need.
- A 100,000 to 200,000 Puzzle Pack: Puzzles are drawn from a window of ±100 around the Player
  Rating, so a user only ever uses one or two Bands, and 2,000 per Band lasts months.

## Consequences

- Finished Puzzles are saved by Lichess puzzle id, not by position in the Pack, so a new Pack keeps
  the user's history.
