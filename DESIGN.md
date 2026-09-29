# Design notes

Tunable values and the rules behind them. Rulings live in `docs/design/decision-log.md` (cited by id);
this file holds the constants we expect to adjust from measurements on the Light Phone III, and the
small decisions v1 PRs 3 and 4 made that the log doesn't cover.

## Layout (R1.8)

The LP3's app area is 1080 × 1168 px at 480 dpi: 360 dp wide, about 389 dp tall. The board is 312 dp
square (8 × 39 dp, 117 px squares), centred, 12 dp below the top of the app area, with 24 dp either
side. The strip is exactly as wide as the board, directly under it, and two `Copy` lines tall (90
design px, 58 dp on the LP3; never under 48 dp). About 7 dp stay free at the bottom.

The Side at the bottom is a parameter of the board view (`PositionView(bottom = ...)`). v1 has no
board flip (D10); the puzzle flow puts the side to move after the setup Move at the bottom (A5).

## Palette (D10)

Every colour is a gray level, so a screencap, taken before LightOS's grayscale filter, shows what the
panel shows. The constants live in `tool/src/main/kotlin/com/yarosz/chess/board/Shades.kt`, and
`ShadesTest` enforces every floor below. All values are starting points to tune on the LP3, with one
photo of the panel as the final check.

| Constant | Level | Hex | Used for |
|---|---|---|---|
| `LIGHT_SQUARE` | 216 | `#D8D8D8` | light squares (R1.7) |
| `DARK_SQUARE` | 140 | `#8C8C8C` | dark squares (R1.7) |
| `LAST_MOVE` | 178 | `#B2B2B2` | both squares of the last Move (A5) |
| `MARKER` | 30 | `#1E1E1E` | dots, capture rings, check ring, selection border, corner marks, drag outline |
| `COORDINATE_ON_LIGHT` | 90 | `#5A5A5A` | coordinates on light squares |
| `COORDINATE_ON_DARK` | 240 | `#F0F0F0` | coordinates on dark squares |
| `PICKER` | 240 | `#F0F0F0` | promotion picker cells; the rest of the board is dimmed with `MARKER` at 60% |

Floors, in gray levels:

- The two squares are at least 60 apart (76).
- The last-move shade is at least 25 from both squares (38 from each). One shade serves both
  squares, halfway between them, and the corner marks carry the rest.
- Marker readability, the rule `ShadesTest` checks: a marker's level is at least 60 (the squares'
  own floor) from every ground it can be drawn on, which is the light square, the dark square and the
  last-move shade (186, 110 and 148 here); every marker stroke is at least 2 dp thick; a dot is at
  least 8 dp across (12.5 dp here).
- Coordinates follow the same 60-level rule against their own square and against the last-move shade
  (126 / 88 on light squares, 100 / 62 on dark ones).
- The picker cells are at least 60 above the dimmed board under them.

## Marks (R1.6, R1.7, A5)

Sizes are fractions of a square (39 dp), in `Marks` next to the shades.

- Legal target, empty square: a dot, radius 0.16 (12.5 dp across).
- Legal target, capture (en passant included): a ring hugging the square, radius 0.46, stroke 0.08
  (3.1 dp), drawn over the piece.
- Check: a ring on the king, radius 0.40, stroke 0.11 (4.3 dp): heavier and smaller than a capture
  ring. The two never share a square, since a king in check can't be captured.
- Selection: a heavy square border, 0.11 (4.3 dp).
- Puzzle Hint (A6): a heavy ring hugging the piece to move, radius 0.45, stroke 0.11 (4.3 dp): as heavy
  as the check ring and as wide as a capture ring, drawn over the piece until the next Move.
- Last Move: the last-move shade plus four L-shaped corner marks (0.28 long, 0.08 thick), chosen over
  an outline so it can't be mistaken for the selection border.
- Drag: the square under the finger gets a thin outline (0.06, 2.3 dp).
- Coordinates: inside the edge squares, rank numbers top-left of the left column, file letters
  bottom-right of the bottom row, 0.26 of a square high, drawn under the pieces.

## Input (R1.6, A4)

The rules are in `MoveInput`, a pure-Kotlin state machine with its own unit tests; the board view only
maps touches to squares. Every legality question goes to the rules core.

- Tap-tap: tap a piece of the side to move, then a target. Tapping another of that side's pieces
  reselects. Tapping the selected piece, an empty square that is no target, or an opponent's piece
  that can't be taken deselects. An illegal target plays nothing.
- Drag: a press that moves past the system touch slop lifts the piece. It is drawn one square above
  the finger, and it drops on the square under the finger, which gets the drag outline. Dropping
  anywhere that isn't a target (its own square, an illegal square, off the board) puts the piece back
  and leaves it selected, so its dots stay visible. A press that lifts nothing counts as a tap.
- Castling: the king's two-square step, or the king onto its own rook, by tap or drop (A4). Only the
  king's destination shows a dot; the rook square takes the tap without a mark of its own.
- Promotion: a pawn Move to the last rank opens the picker over the promotion file, from the
  promotion square inward: queen, rook, bishop, knight. The next tap picks a piece; a tap anywhere
  else cancels and deselects. Underpromotion is one tap, like the queen.

## Wheel and Review (R1.9, F2)

- Counter-clockwise enters Review one Ply back, then steps back to the start. Clockwise steps forward;
  reaching the latest Position leaves Review. A click returns to the latest Position.
- Outside Review, clockwise and click return false, as does counter-clockwise before any Move, so
  LightOS keeps brightness and the flashlight.
- A key's up and repeats go where its down went (R4.17): LightActivity asks the screen about each
  separately and hands LightOS every wheel event the screen doesn't take, so a screen that took only
  the down turned the flashlight on with the up of a click it had used (seen on the LP3). Every view
  model that takes the wheel extends `WheelViewModel`, which records the taken presses (`TakenKeys`).
- A tap on the board during Review returns to the latest Position (the "live" tap of R1.9); the strip
  shows a Latest button while Review is on.
- The emulator's Android build doesn't know Light's wheel keycodes: an injected 317-319 arrives as
  keycode 0. The wheel is covered by `ReviewTest` and must be checked on the LP3.

## The strip (R1.8, F11, contradiction 2)

A status line on the left and up to three text buttons on the right, as in Reader's footer: LightOS
`Copy` text, the status in the secondary content colour, buttons in the content colour with LightOS's
press without a ripple, 8 dp padding around each button. Every button carries a semantics label and
the Button role (F11). The buttons sit edge to edge, 4 dp after the status: with 4 dp between them,
"White to move" was cut short next to Hint, Solution and Menu on the emulator.

The status wraps to a second line instead of being cut short. On the LP3, in LightOS's font
(Akkurat), a one-line status lost its end: "Tap a piece, then a squ…" next to Menu. Measured the same
way, "White to move" next to Hint, Solution and Menu and "Review · 12 of 14" next to Latest, Next
and Menu don't fit on one line either, so they show as "White to / move" and "Review · / 12 of 14".
The strip is always two lines tall, so the board never moves when a status wraps.

`StripFitTest` checks every strip in the table below: each button on one line, each status in at
most two lines in the room its buttons leave, with the widest numbers ("Failed −999",
"Review · 99 of 99"). Akkurat can't ship with the repo, so the test measures with Helvetica's
advance widths scaled by 1.15 at `Copy` size for a 389 dp tall screen (19.45 sp). Six words measured
on LP3 screencaps are never wider than the stand-in; the widest, "Restart", ran 12% wider than plain
Helvetica. A new strip string or state goes into the test.

Buttons by context:

| When | Buttons |
|---|---|
| The user's Move, the Solution's reply pending | Hint, Solution, Menu |
| The very first Puzzle, before its first Move (F6) | Menu |
| The Solution playing | Menu |
| The result (D1) | Next, Menu |
| Review | Latest, then Next at the result, then Menu |

## The puzzle flow (A3-A8, D1-D4, F1-F6)

The rules are pure Kotlin: `Attempt` (one try at one Puzzle) and `PuzzleFlow` (scoring, selection,
Missed, Reset rating, the save file and the Pack carry-over), each with unit tests. `PuzzleOwner` is
the process-wide owner (PLATFORM.md: a relaunch keeps the old view model alive) that runs the clock
and writes the file; the two screens' view models are views onto it.

- Timing (A5): hold the Position 500 ms, slide the setup Move in 250 ms, reply 300 ms after the user's
  Move lands. The Solution plays one Move every 600 ms (our choice). Every Move that plays itself
  (setup, reply, Solution) slides in over 250 ms; the user's own Moves are instant (F11).
- A wrong Move is never drawn: it is taken back at once and the status reads "Try again" until the
  next correct Move.
- Scoring: a Failed Attempt is scored at the wrong Move or at Solution, once; a Solved one when its
  last Move lands; a Hinted one ends unrated. Each scored Attempt adds a history row, marks the Puzzle
  finished, and (Failed or Hinted) puts it at the top of Missed. The next Puzzle is chosen and parsed
  in the background at that moment (D1).
- Glicko-2 uses τ = 0.75, Lichess's value for puzzles; the log doesn't fix τ. A measured consequence
  of RD 500 and volatility 0.09 with one Puzzle per rating period: the deviation settles near 73-74
  after about 48 Puzzles, just under the 75 of "1500?", and the 45 floor is never reached in practice.
- Selection picks at random among the candidates `Pack.candidates` returns.
- A Missed replay sits on top of the rated Puzzle: Next after it returns to the rated one. The save
  file doesn't keep a replay; a relaunch returns to the rated Puzzle.
- Reset rating drops a rated Attempt that is still Open or already at its result, so the next Puzzle
  suits the new seed; one scored and still in Try Mode stays.
- Keep the screen on (D3) while the Attempt on screen is under way (Open, or Failed/Hinted in Try Mode,
  up to its result) and the last touch or wheel event was under 5 minutes ago.
- The save file, `puzzles.json` in filesDir, is written on a background thread after every change to
  what it keeps (results, seed, Next, reset, Missed), and on the main thread in onAppPause, which also
  saves the Moves played so far. It writes this build's schemaVersion; unknown fields are dropped.
- Cold start: `ChessPerf` logs "session loaded ms=" and, once per process, "first puzzle drawn ms=...
  since process start". LightActivity keeps its splash screen up for at least 1 s after onCreate
  (light-sdk `LightActivity.kt`), so what the user sees first can't come sooner than that.

## Copy (F11: English, one strings object)

All copy lives in `UiCopy`. The board's accessibility label is "Chess board"; "board" is fine in UI
copy for the object on screen, while code names the chess state a Position.

- The strip: "White to move" or "Black to move" (A5); "Tap a piece, then a square" on the very first
  Puzzle until its first Move (F6); "Try again" after a wrong Move; "Correct" while the reply is
  pending; "Solution" while it plays; "Review · $ply of $latest" in Review.
- Results (D1): "Solved +12", "Failed −9" (a real minus sign), "Solved, unrated" for a Hinted Attempt,
  "Hinted, unrated" for a Hinted one whose Solution was shown, and for a Missed replay "Solved,
  unrated" or "Failed, unrated". "Every Puzzle is finished" when the Pack runs out.
- Buttons and their labels: "Hint" ("Puzzle Hint: mark the piece to move"), "Solution" ("Play the
  Solution"), "Next" ("Next Puzzle"), "Menu" ("Open the Menu"), "Latest" ("Back to the latest
  position").
- The seed screen (D4): "How well do you play chess?", then "I'm new to chess" (800), "I play now and
  then" (1200), "I play often and study the game" (1600), "I play in a club or in tournaments" (2000),
  and "Skip" (1500).
- The Menu: "Menu", "Player Rating · 1500?", "Missed · 3", "About". The rating page: "Player Rating",
  the rating, "Reset rating" then "Tap again to reset" (F5), and rows "1523 · Solved +12"; "No rated
  Puzzles yet" when empty. Missed: rows "1541 · Failed" or "1541 · Hinted"; "Nothing missed yet".
- About (D7): plain text, one paragraph per line below, that scrolls by touch and by the wheel (F3).
  - "Chess $VERSION" (0.1.0, equal to `versionName`)
  - "Copyright 2026 Nicolas Yarosz."
  - "Free software under the GNU General Public License, version 3 or later, with no warranty."
  - "Source: $SOURCE" (github.com/yarosz/light-chess, as text: the phone has no browser)
  - "Chess never uses the network. Nothing leaves this phone." Not "No network permission": the
    Tool declares none, but Light's SDK libraries merge INTERNET, CAMERA and six more into every
    Tool's manifest, which `scripts/release-check.sh apk` pins.
  - "Puzzles: the Lichess puzzle database (lichess.org), CC0, from the dump of $packDate." with the
    Pack manifest's `source.date` ("Puzzles: the Lichess puzzle database (lichess.org), CC0." if a
    manifest has none).
  - Then `tool/src/main/assets/about/notices.txt`, verbatim legal text kept out of code: the cburnett
    licence in full (BSD-3 asks a binary to reproduce it), Light's SDK (MIT) and the libraries of the
    release APK's runtime classpath by licence, with the Apache-2.0 notice. NOTICE names the same
    libraries. The v2 engine's credit joins with v2.
