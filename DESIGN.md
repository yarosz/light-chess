# Design notes

Tunable values and the rules behind them. Rulings live in `docs/design/decision-log.md` (cited by id);
this file holds the constants we expect to adjust from measurements on the Light Phone III, and the
small decisions v1 PRs 3 and 4 made that the log doesn't cover.

## Navigation (N1-N20)

LEFT always leaves (toward Home), RIGHT always opens this board's Menu, and a fixed Home list goes
places. No gestures. Rulings: decision log "Navigation D" and "Puzzles page and Pieces".

- Home (`HomeScreen`, the `@InitialScreen`): LightOS's top bar titled "Chess" with no back arrow, then
  the five rows `HomeRows` gives (N11): "Puzzles · 1176?", "Play the computer", "Play a friend" or
  "Play a friend · Your move: 2" (only with the Relay URL set), "Games", "About". Before
  `puzzles.json` is read the Puzzles row reads "Puzzles". System back from Home closes Chess.
- The Puzzles page (`MenuPage.PUZZLES`, rows from `PuzzlesRows`, N12): the top bar with the back
  arrow and the title "Puzzles", then a first row that says what it does, "Missed · 3", "Past
  Puzzles" and "Player Rating · 1176?". The first row, from the Puzzle flow's state
  (`PuzzlesRows.start`, then `PuzzleFlow.toRated` on a tap):

  | State | Row | A tap |
  |---|---|---|
  | An Attempt under way, Try Mode included | "Continue Puzzle" | opens the board |
  | The rated Attempt at its Result | "Next Puzzle" | the next Puzzle, then the board |
  | A Missed replay on screen | "Back to the rated Puzzle" | ends the replay, then the board |
  | Before the seed, or after Reset rating | "Start" | ends a Missed replay, then the board, which asks the seed (D4, N22) |
  | The Pack used up, a Missed replay or not | "Every Puzzle is finished" | nothing: a plain line (N22) |
  | `puzzles.json` not read yet | "Continue Puzzle" | the board, which waits for it |

  "Missed · 3" counts only the rows that replay: a Puzzle the Pack lost is left out (N13, N23).

- Missed (N13, N23): as before, but a row whose Puzzle the Pack no longer has is a lightened line.
  The background read (`PuzzleOwner.prefetchMissed`, when the Puzzles page or Missed opens) finds
  such rows, reading each Band file at most once, and never looks for one again in the process. A
  tap reads no file: on a lost row, or one the read hasn't reached yet, it starts nothing and the
  page stays.
- Past Puzzles (N14): the rated Attempts, newest first, read-only, up to 100 (`HISTORY_CAP`).
- Player Rating (N15): the rating, "The ? goes after about 50 rated Puzzles." while it is
  provisional, and Reset rating ("Tap again to reset").
- Launch (`Navigation.launch`, N16): the place last used (`mode.txt`) is pushed over Home on the
  activity's first show, before Home is drawn: for Puzzles the Puzzles page and the board over it
  (so Chess opens on the board and back walks down the levels), else the computer's board or the Play
  a friend list (the Puzzle when the Relay URL is empty). The launch doesn't write `mode.txt`; opening
  the Puzzles page, the board, the computer's board or Play a friend does.
- Depth (N16): Home; a place (the Puzzles page, the computer's board, Play a friend, Games, About); a
  page or board over it (the Puzzle board, Missed, Past Puzzles, Player Rating, a Correspondence
  Game's board or an invite, a replayed Game); a detail over that (a board's Menu and its pages).
  Play the computer with no Game in progress opens the new-game page, and Start replaces it with the
  board; Reset rating and a Missed replay replace their page with the Puzzle board, over the Puzzles
  page. Home and the Puzzles page each open places through a `HomeNavigator`, whose pushes never sit
  over a board (`overGame` false), so nothing they open lets the computer think.
- Every Menu page is its own screen (S3, N6): the computer's Moves and New game, a Correspondence
  Game's Moves and Rename. A second tap ("Tap again to resign") changes its row in place.
- The wheel (N10, N20): Home's five rows fit the LP3 (40 dp top bar plus five 53 dp rows in 389 dp),
  so it leaves the wheel with LightOS; it still measures, and would take the wheel if a row ever
  overflowed. So do the Puzzles page, the Player Rating page and the Puzzle board's and a replay's
  Menus, which always fit. Missed, Past Puzzles and the Game Menus may scroll and take it (F3).

## Layout (R1.8, layout E: E1-E4)

The LP3's app area is 1080 × 1168 px at 480 dpi: 360 dp wide, about 389 dp tall. Every board (a
Puzzle, the computer's Game, a Correspondence Game, a replayed Game) is laid out the same way, top
to bottom (decision log "Layout E"):

- Top bar (E1, `BoardTopBar`): light-sdk's `LightTopBar`, 3 grid units (40 dp) tall. It has
  `LightIcons.BACK` at the left ("Back", `goBack`, in every state), the status as its title (`Fine`,
  centred, at most 18 grid units, 240 dp, on up to two lines, E3), and at the right N4's three
  squares where the board has a Menu (`PuzzleStrip.menu`, `GameStrip.menu`, `FriendStrip.menu`;
  N17: every board, but not in a Game's Review). The squares are drawn by a painter as the SDK's right
  button, in a 40 dp square box ending one grid unit in, centred on the
  bar's middle, their ink ending 16 dp from the edge.
- Board: 312 dp square (8 × 39 dp, 117 px squares), centred with 24 dp either side, directly under
  the bar (40 + 312 = 352 dp of 389).
- Action row (E2, `ActionRow`): the rest, about 37 dp. It holds this moment's buttons at the right
  (the tables below), in `Copy`, each target the row's height, the last label's ink 16 dp from the
  edge. It has no arrow, mark or status.
- Captured Pieces (E4, E6, P3): on a Game's board, at the action row's left, in a room that never
  changes on that board: from the board's left edge to 4 dp before the widest button set the board
  can show (`BarLayout.capturedRoom`, at most the board's width), whichever set is shown. The other
  Side's end grows inwards from that room's right end, and `CapturedRowLayout.fit` tightens the
  steps when the ends would meet, from that same room, so the row moves only when a piece is taken
  or Review steps. The drawings are centred on the row's middle line.
- When every Puzzle is finished: the top bar alone.

`StripFitTest` measures every board state in the bar and the row (E5). The invite page and the Play
a friend list keep LightOS's top bar and the strip at their foot ("The strip" below).

The Side at the bottom is a parameter of the board view (`PositionView(bottom = ...)`). The puzzle
flow puts the side to move after the setup Move at the bottom (A5). The game screen puts the user's
Side at the bottom; "Flip board" in the Menu turns it (D10, v2), and the flip is kept in `games.json`.
The Captured Pieces row stays aligned with the board's left edge.

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
| `CAPTURED_BLACK_BODY` | 150 | `#969696` | the body of a captured black piece in the Captured Pieces row (P3), at least 60 from the black ground and from white |

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

## Pieces (P1, P2, N17, N19)

Chess's own two sets, original CC0 drawings in `art/pieces/geometric/` and `art/pieces/rounded/` (the
README there has the design rules), converted to ImageVector code at build time by
`scripts/build-pieces.py` (R1.7). Black pieces are solid silhouettes; a white piece is the same
silhouette in white over a wider black outline, so it keeps an edge on the light square (216) as well
as the dark one. Each piece is drawn in its square with a small inset.

The player's Piece Set is geometric until changed. Every board's Menu has a "Pieces · Geometric"
row (N17): first on the Puzzle board's Menu, alone on a replayed Game's, and last on the computer's
and a Correspondence Game's, 16 dp below their actions (`Rows.GAP`), so a wheel over-scroll to the
end lands on Pieces and never on an action. The invite page and the list pages draw no board and
have none. A tap moves to the next set ("Pieces · Rounded", then back) and stays on the Menu, and
every board and its promotion picker draw it from then on: the Puzzle, the Game with the computer, a
Correspondence Game and a finished Game from Games (M1). The row draws the chosen set's white king,
queen and knight beside its label, 24 dp square (`Rows.PIECE`, the height of a `Copy` line), from the
board's own `PieceVectors` (N19), so the change shows on the Menu. The choice is saved once, in
`puzzles.json` (`pieceSet`), so it outlasts a relaunch and Reset rating; a set this build doesn't know
reads as geometric. The puzzle owner reads it from the file before any Band (M4): a cold start into a
Game draws its first frame in the chosen set, and the row waits for it.

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

## Wheel and Review (R1.9, F2, N20)

- Counter-clockwise enters Review one Ply back, then steps back to the start. Clockwise steps forward;
  reaching the latest Position leaves Review. A click returns to the latest Position.
- A board takes every turn (N20), even one that moves nothing: clockwise at the latest Position,
  counter-clockwise at Ply 0 or before any Move. A fast scrub back to the present overshoots the end,
  and each detent past it used to change LightOS's brightness. The click outside Review returns false,
  so LightOS keeps the flashlight. Pages that aren't boards keep F3 (one that fits leaves the wheel
  with LightOS, brightness included); so do the seed screen and the end of the Pack.
- A key's up and repeats go where its down went (R4.17): LightActivity asks the screen about each
  separately and hands LightOS every wheel event the screen doesn't take, so a screen that took only
  the down turned the flashlight on with the up of a click it had used (seen on the LP3). Every view
  model that takes the wheel extends `WheelViewModel`, which records the taken presses (`TakenKeys`).
- A tap on the board during Review returns to the latest Position (the "live" tap of R1.9); the
  action row shows a Latest button while Review is on.
- The emulator's Android build doesn't know Light's wheel keycodes: an injected 317-319 arrives as
  keycode 0. The wheel is covered by `ReviewTest` and must be checked on the LP3.

## Buttons, the title and the strip (R1.8, F11, contradiction 2, E1-E3)

A board's status is its top bar's title and its buttons sit in the action row (Layout, above). Up to
three text buttons by context, in LightOS `Copy` and the content colour, with LightOS's press
without a ripple, 8 dp padding either side of each label, edge to edge, as in Reader's footer. Every
button carries a semantics label and the Button role (F11). The back arrow ("Back") and the Menu
mark ("Open the Menu") carry their labels too; on the boards they are the SDK's `LightBarButton`s in
`LightTopBar`, which set no role, the same as on every LightOS page (we keep SDK parity and don't
change the SDK). The last label ends 16 dp from the screen's edge, under the mark.

The invite page and the Play a friend list keep the strip at their foot (`Strip`, `StripLayout`),
under LightOS's top bar, which holds their back arrow: a status in `Copy` and the secondary content
colour from the board's edge (24 dp), up to three text buttons 4 dp after it, and on the invite page
the Menu mark at the right (N4): three solid squares, 8 by 7 px, 28 px apart centre to centre, drawn
as shapes (a full stop differs between Roboto and Akkurat), ink ending 16 dp (48 px) from the right
edge, centred on the x-height of the `Copy` text in the font in use (the label's baseline less half
an "x"'s ink, `Paint.getTextBounds`), with a 48 dp target the strip's full height. The last text
button ends at the target, its right padding under it. The strip's status wraps to a second line
instead of being cut short, and the strip is always two lines tall.

Superseded (D's strip, N3 and N4 on the boards, before layout E): the boards had this strip under
the board, with the back arrow at its left (ink at pixels 48 to 82, a 48 dp target, the status from
112 px) and the Captured Pieces in its top band. E1 and E2 replaced it; the arrow is the top bar's.

`StripFitTest` checks every state the boards and pages can show, from their own strip functions
(`PuzzleStrip`, `GameStrip`, `FriendStrip`), with the widest numbers ("Failed −999", "Review · 99 of
99", "Review · 9999 of 9999"): each board status in the top bar's 240 dp `Fine` title in at most two
lines (E3), each board's buttons in the action row beside the Captured Pieces (E2, E4), and each
page strip's status in the room its buttons and the mark leave at the LP3's 360 dp. Akkurat can't
ship with the repo, so the test measures with a stand-in: Helvetica's advance widths with the narrow
letters widened (i and j to 280/1000 em, t to 325), scaled by 1.14, at `Copy` size for a 389 dp tall
screen (19.45 sp), and at `Fine` size with its 0.03 em letter spacing for a title. It is calibrated
on LP3 screencaps of v1 and v2 (1080 px wide, 3 px per dp, the strip from x 72 to 1008), three ways:
twelve strings measured there (ink plus 3 dp of side bearings) are each at least 1 dp narrower than
the stand-in; the room before the first button, from where its ink starts, is wider than the
stand-in's for five button sets; and the stand-in wraps "Your move" next to Takeback, Hint and Menu
and "Computer thinking" next to Move now and Menu, as the LP3 did, and keeps "Your move" next to Hint
and Menu and "Review · 4 of 6" next to Latest and Menu on one line, as the LP3 did. The v1 stand-in
(Helvetica × 1.15) fell short on "Hint" and "Latest" (Akkurat's i and t are wider). The calibration
is in the layout those screencaps show (the strip as wide as the board, "Menu" as text). A new
string or state goes into the test.

The Puzzle board's buttons by context (`PuzzleStrip`), in the action row, and the Menu mark in the
top bar in every state but the end of the Pack, which draws no board (N17, E1). Its Menu: "Pieces ·
Geometric", then "Puzzle 00sHx" over "lichess.org/training/00sHx" in grey, for the Puzzle on screen,
a Missed replay's own (N18). A replayed Game's top bar has the mark, in Review too, and its Menu is
Pieces alone (N21). A Missed replay started before the seed shows first; the seed screen follows it
(N22).

| When | Buttons |
|---|---|
| The user's Move, the Solution's reply pending | Hint, Solution |
| The very first Puzzle, before its first Move (F6) | none |
| The Solution playing | none |
| The result (D1) | Next |
| Review | Latest, then Next at the result |
| Every Puzzle is finished | none |

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
  "What it keeps" is everything but those Moves (`PuzzleState.kept`): the Attempt's state, `done`,
  `solutionShown` and "Try again" (`justWrong`) included. So a Failed Attempt's result, a Puzzle Hint,
  Solution and each wrong Move in Try Mode are written at once, and a kill at any of them relaunches
  into it, never scoring twice (v1 smoke fixes). Before, a Failed result reached the file only in
  onAppPause, which a kill skips.
- Band files are read ahead on a background thread (v1 review follow-ups), so the main thread reads
  none in the usual case: when a rated Attempt starts, the Bands for the Player Rating after a win, a
  loss and no change (`PuzzleFlow.prefetchNext`), and when the Missed page opens, the Band lines of
  every Missed Puzzle (`prefetchMissed`). The choice itself still happens at the result (D1). A tap
  that outruns the read ahead reads the file itself, as before.
- The stage clock (A5) starts again only when the Attempt on screen changed, so a tap that changes
  nothing (Hint while the reply is pending) doesn't delay the reply. A slide (setup, reply, Solution)
  is cleared once it has played, and whenever its Move is no longer the latest, so a return to the
  Puzzle board from Home doesn't play it again.
- The owner reads assets through the latest screen that asked for it (`PuzzleOwner.of`): the SDK
  reads them only through a screen's activity, so keeping the first screen's reader kept the first
  activity alive after a relaunch.
- Cold start: `ChessPerf` logs "session loaded ms=" and, once per process, "first puzzle drawn ms=...
  since process start". LightActivity keeps its splash screen up for at least 1 s after onCreate
  (light-sdk `LightActivity.kt`), so what the user sees first can't come sooner than that.

## Copy (F11: English, one strings object)

All copy lives in `UiCopy`. The board's accessibility label is "Chess board"; "board" is fine in UI
copy for the object on screen, while code names the chess state a Position.

- The status (the top bar's title, E1): "White to move" or "Black to move" (A5); "Tap a piece, then a square" on the very first
  Puzzle until its first Move (F6); "Try again" after a wrong Move; "Correct" while the reply is
  pending; "Solution" while it plays; "Review · $ply of $latest" in Review.
- Results (D1): "Solved +12", "Failed −9" (a real minus sign), "Solved, unrated" for a Hinted Attempt,
  "Hinted, unrated" for a Hinted one whose Solution was shown, and for a Missed replay "Solved,
  unrated" or "Failed, unrated". "Every Puzzle is finished" when the Pack runs out.
- Buttons and their labels: "Hint" ("Puzzle Hint: mark the piece to move"), "Solution" ("Play the
  Solution"), "Next" ("Next Puzzle"), "Latest" ("Back to the latest position"); the back arrow
  ("Back") and the Menu mark ("Open the Menu") have no text (N3, N4).
- The seed screen (D4): "How well do you play chess?", then "I'm new to chess" (800), "I play now and
  then" (1200), "I play often and study the game" (1600), "I play in a club or in tournaments" (2000),
  and "Skip" (1500).
- Home (N11): the title "Chess", then "Puzzles · 1500?", "Play the computer", "Play a friend · Your
  move: 2", "Games", "About". `HomeTest` checks that each row fits one line. Each page is its own
  screen, so Back, the arrow or the system's, goes from a page one level down (N16); Reset rating
  and a Missed replay go straight to the Puzzle board, over the Puzzles page.
- The Puzzles page (N12): the title "Puzzles", then "Continue Puzzle", "Next Puzzle", "Back to the
  rated Puzzle", "Start" or "Every Puzzle is finished" (a plain line), then "Missed · 3", "Past
  Puzzles", "Player Rating · 1500?". The Player Rating page: the rating, "The ? goes after about 50
  rated Puzzles." while it ends in "?", "Reset rating" then "Tap again to reset" (F5). Past Puzzles:
  rows "1523 · Solved +12", "1541 · Failed −9"; "No rated Puzzles yet" when empty. Missed: rows
  "1541 · Failed" or "1541 · Hinted", lightened for a Puzzle the Pack lost; "Nothing missed yet".
- A board's Menu (N17-N19): "Pieces · Geometric" or "Pieces · Rounded" with the set drawn beside it
  (P2, M1; a tap moves to the next set and stays on the Menu); on the Puzzle board, "Puzzle 00sHx"
  over "lichess.org/training/00sHx".
- About (D7): plain text, one paragraph per line below, that scrolls by touch and by the wheel (F3).
  - "Chess $VERSION" (0.3.1, equal to `versionName`)
  - "Copyright 2026 Nicolas Yarosz."
  - "Free software under the GNU General Public License, version 3 or later, with no warranty."
  - "Source: $SOURCE" (github.com/yarosz/light-chess, as text: the phone has no browser)
  - "Chess never uses the network. Nothing leaves this phone." Not "No network permission": the
    Tool declares none, but Light's SDK libraries merge INTERNET, CAMERA and six more into every
    Tool's manifest, which `scripts/release-check.sh apk` pins.
  - "Puzzles: the Lichess puzzle database (lichess.org), CC0, from the dump of $packDate." with the
    Pack manifest's `source.date` ("Puzzles: the Lichess puzzle database (lichess.org), CC0." if a
    manifest has none).
  - "Opening book: the Lichess games database (lichess.org), CC0, January 2018." (v2 PR 6, M4,
    docs/book.md; the Book's dump is fixed, so its month is copy, not read from its manifest).
  - Then `tool/src/main/assets/about/notices.txt`, verbatim legal text kept out of code: "Pieces:
    original drawings made for Chess (two sets), released under CC0 1.0 (no rights reserved)." (P1,
    P2; NOTICE and the README carry the same line, `ToolMetadataTest` checks all three), Light's SDK
    (MIT) and the libraries of the release APK's runtime classpath by licence, with the Apache-2.0
    notice. NOTICE names the same libraries.
  - "Engine: Pirarucu by Raoni Campos (ratosh), GPL-3.0." (D7, v2 PR 4), before the Book's line.
    NOTICE carries the longer credit.
  - No Puzzle id since N18: "Puzzle 00sHx" over "lichess.org/training/00sHx" is the Puzzle board's
    Menu's (A9 with D7, S1). The address has its own line, since Android breaks it at a slash;
    `StripFitTest` checks that each line fits 360 dp.

## The game screen (v2 PR 4)

The rules are pure Kotlin in `games/GameFlow.kt`: `GameState` (the file, the Game on screen, a Game
Hint, the draw response, a pending second tap) and `GameFlow` (start, the turn flow, Takeback, Game
Hints, draw offers, Resign, what the computer does next), with `GameFlowTest`. `GameOwner` is the
process-wide owner that runs the computer on `EngineHost.shared`, the clock and `games.json`;
`GameStrip` picks the strip. Rulings: decision log "v2 PR 4".

- The Tool opens on the mode last used (D6, N2), kept in `mode.txt`, over Home. "Play the computer"
  on Home returns to the Game in progress, or opens the new-game page, which Start replaces with the
  board. Leaving the board for Home stops the computer's search until the board shows again
  (`GameScreen.onScreenDestroy`); its Menu and the Menu's pages don't. A page opened from Home, New
  game included, sits over no board and never starts a search (`MenuScreen`'s `overGame`). A Game
  Hint being found when the board leaves is dropped, and one on show goes, so the action row offers
  Hint again on return (`GameOwner.pause`, `GameOwnerTest`).
- The new-game page: "Level" with 1 to 8 on one line, "Play as" with "White", "Black" and "Random",
  and "Think Time" with "3 s", "10 s" and "30 s" at Level 8 only. The chosen option is in the content
  colour, the others lightened. "Start" starts; with a Game in progress it reads "Tap again to
  replace" first, above "The Game in progress is saved as unfinished." The choices are remembered
  (Level 1, White, 3 s the first time).
- The computer's Move lands no sooner than 300 ms after the user's (A5's reply delay), so a book Move
  or a quick Level doesn't land with the user's own, and slides in over 200 ms (F11). The user's Moves
  are instant. The board takes no Move while the computer thinks (contradiction 3); the wheel still
  reviews.
- Game Hint: a Level 8 search at the default Think Time (3 s); the title reads "Finding a Game Hint"
  with the mark while it runs, and the board stays live (a Move stops the search, uncounted). Then the
  Puzzle Hint's ring on the piece and a target mark on its square (the dot, or the capture ring over a
  piece) show for 5 s or until the user's Move. It counts once shown.
- Keep the screen on while the computer thinks, and on the user's Move while the last touch or wheel
  event was under 5 minutes ago (contradiction 4), in the Menu too.

The top bar's title and the action row's buttons by context (contradiction 2, E1, E2); the back
arrow is always in the top bar, and "the mark" is the Menu mark at its right (N4):

| When | Status | Buttons |
|---|---|---|
| The user's Move | "Your move" | Hint, the mark |
| A Game Hint being found | "Finding a Game Hint" | the mark |
| The computer thinking | "Thinking" | Move now, the mark |
| The Result | the Result | Next, the mark |
| Review | "Review · 12 of 40" | Latest (no mark: the Menu's actions act on the live Game, N21) |
| A finished Game from Games | the Result, or Review | the mark, and Latest in Review (N21) |

The Captured Pieces (P3), on every board in a Game (this screen, a Correspondence Game's board and
Games Review), never on a Puzzle's. `CapturedPieces` (rules core) reads them from the Moves up to the
Ply on screen, en passant included, a promoted pawn taken later as the piece it became; `CapturedRow`
draws them and `CapturedRowTest` checks the layout:

- One row at the action row's left (E4). Left end: what the Side at the bottom has taken, from the
  board's left edge; right end: the other Side's, inwards from the room's right end, 4 dp before
  the widest button set the board can show (E6, at most the board's right edge); both reading pawn, knight, bishop, rook, queen from
  left to right (pawns at the left edge, queens at the right). One kind fans out, 5.5 dp a piece; the
  next kind starts 16.5 dp on; drawings are 17 dp. The widest row (fifteen a side, "+103") fits the
  board's width as it is; beside buttons, `CapturedRowLayout.fit` shrinks every step by one factor
  until the ends are 4 dp apart, so every piece is still drawn.
- The room is the board's, not the moment's (E6): the computer's board keeps the room beside Move
  now (207 dp in the stand-in), a replay beside Latest (245.5 dp), a Correspondence Game's board
  beside Accept and Decline (148 dp). So neither end nor the steps move as Move now, Hint, Next,
  Send and Undo or Latest come and go. The Puzzle board has no Captured Pieces and no room.
- The Material Lead, "+7", in Superfine and the secondary content colour just inside the leading
  Side's end; nothing when even. From the material on the board, so a promotion counts.
- A captured white piece is its board drawing; a captured black one the white drawing with a gray
  body (`CAPTURED_BLACK_BODY`), generated for both Piece Sets by `scripts/build-pieces.py`.
- The buttons stay at the action row's right whether or not the row holds pieces yet, so nothing
  moves when the first piece is taken or when Review steps across it, and the pieces stay put
  whichever buttons are shown (E6). (P3's band in D's strip, with
  the text lowered below it, is superseded by E4.)
- Its label: "Captured by White: two pawns, a queen. Captured by Black: a knight. White is ahead by
  7.", the bottom Side first; "Material is even." when neither leads.

Every game status holds one line of the top bar's title in the stand-in, Results included (E3).
R4.16 set the copy when the status shared D's strip with the buttons: in Akkurat, "Your move"
wrapped next to Takeback, Hint and Menu, and "Computer thinking" next to Move now and Menu. So Takeback is in the Menu only, the computer's think reads "Thinking", and
Review keeps Latest alone, which leaves room for "Review · 9999 of 9999".

"Game Hint" and "New game" don't fit as labels: next to Takeback and Menu, "Game Hint" leaves no
room for any status, and "New game" leaves too little for a Result. The labels are "Hint" and "Next";
their semantics labels are "Game Hint: show the computer's best Move" and "Start a new game". The
others: "Move now: the computer plays at once". Next opens the new-game page. A replayed Game's
arrow returns to the Games (N3, in the top bar since E1: it replaces the old "Back" button).

Results, from the user's view: "You won by checkmate", "You lost by checkmate", "You resigned", "You
won by resignation" (never, since the computer never resigns, F8, but a hand-edited file can say so),
"Draw agreed", "Draw by stalemate", "Draw by repetition", "Draw by the 50-move rule" and "Draw:
insufficient material" ("Draw by insufficient material" needs three lines next to Next and Menu).
A Game replaced before its end shows "Unfinished".

The Menu while a Game shows, top to bottom (`GameMenu`; only this board's actions, N5):

- "Offer draw" on the user's Move. The answer comes at once, in place: "Draw declined", or back to the
  board with "Draw agreed". While the computer thinks it reads "Offer draw on your move"; after an
  offer, "Offer draw again at move 41" (G1's 10 more Moves), both lightened.
- "Resign", then "Tap again to resign"; the second tap returns to the board with the Result.
- "Takeback" (both while the computer thinks and on the user's Move, once the user has moved), then
  back to the board.
- "Flip board", then back to the board.
- "Moves": the Game's SAN in two columns, "12." then White's Move then Black's (… for a Game whose
  first Move is Black's), one row per Move number, scrolled by the wheel (F3, F11). "No Moves yet"
  before the first.
- "Think Time · 3 s" at Level 8: each tap goes to the next of 3, 10 and 30 s, for the computer's next
  Move and the next Games.
- "New game": the new-game page, its own screen over the Menu; Start returns to the board. The page
  scrolls by the wheel, one row per detent; it takes every wheel event there, a click included,
  which does nothing (F3).

Games, on Home, lists the finished Games, newest first, at most 50 (B7): "2026.09.28 · Level 3 · Won"
(Won, Lost, Draw or Unfinished); "No finished Games yet" when empty. A tap opens the Game at its
Result; the wheel reviews its Moves, and Back (the top bar's arrow or the system's) returns to the list.

## Play a friend (v3 PR 2)

Rulings: decision log "v3 PR 2 (expert rulings)" (W1-W10), "v3 PR 2 (implementation)" and W12-W13.
The pure parts are `FriendStrip` (the board's and the invite's strips), `FriendRows` (the page's order)
and `FinishedGames` (the Games page's merge), each checked by StripFitTest or FriendPagesTest.
`FriendOwner` is the process-wide owner over the sync engine; it exists only once the Relay URL is
set (`RelayConfig`, W8).

- Home's row: "Play a friend", or "Play a friend · Your move: 2". It sets the friend mode: the Tool
  then opens on the Play a friend page, over Home (mode.txt `FRIEND`, N2).
- The Play a friend page (`FriendListScreen`, N7): LightOS's top bar with the back arrow (to Home) and
  the title "Play a friend", and no Menu (Home has everything it offered); the rows in E7's order ("ABCD · Your
  move · 2d", "ABCD · Their move · 2d", "ABCD-EFGH · Expires in 47h", "ABCD · Rematch sent", "ABCD ·
  Won", "ABCD · Out of sync", a Seat being taken as "ABCD-EFGH · Not sent"), and "No games yet.
  Create a code for a friend, or enter theirs." when empty. At the cap both buttons are lightened
  above "Finish a game first". A row opens the Game's board, or its invite; an "ABCD · Game deleted"
  row opens the Game's Menu, which starts "This game was deleted. Forget it to free its place." and
  has "Forget game" (W13).
- Time Left and an invite's expiry show one unit: hours to the nearest while under 48 are left, days
  to the nearest above (from 47h 30m), the last hour in minutes rounded up. A fresh 1-day Game reads
  "24h" and a fresh 3-day Game "3d" on both phones, a fresh invite "Expires in 48h", and "0m" comes
  with "Time is up" (W12).
- New game: "Play as" (White, Black, Random), "Days per move" (1, 3, 7), "Create code". The invite
  page shows the code in LightOS Subtitle, alone on its line, then "Tell your friend this code. It
  works once, for 48 hours." Once the code is made the invite replaces New game, so back from it goes
  to the list (N6). Its top bar has the back arrow; its strip: "Expires in 48h" with Cancel and the
  Menu mark; the first Cancel shows "Tap again to cancel" next to Cancel alone; an unconfirmed cancel
  shows "Not sent" with Retry and the mark. The invite's Menu: the halt line, "Rename", "Forget game"
  when stopped.
- Enter code: LightOS's text editor with the LP3 keyboard, "Join" to submit; the title reads "Enter
  code", then "Sending", then the answer: "Not a code", "No such code", "Code already used", "Try
  again in a minute", "No connection".
- The board: the user's Side at the bottom, no flip. The top bar's title and the action row's
  buttons (E1, E2); "the mark" is the Menu mark at the top bar's right (N4):

| When | Status | Buttons |
|---|---|---|
| The user's Move | "Your move · 2d" | the mark |
| A Move chosen, not sent (F11) | its SAN, such as "Nf3" | Send, Undo, the mark |
| Sending | "Sending" | the mark |
| Saved, not sent | "Not sent" | Retry, the mark |
| Their move | "Their move · 2d" | the mark |
| Their Deadline passed | "Time is up" | "Claim win" ("Claim win on time"), the mark |
| Their draw offer | "Draw?" (N9) | Accept, Decline, the mark |
| Stopped | "Out of sync", "Update Chess", "Game deleted" or "Seat lost" | the mark |
| The Result | R4.13's copy, "You won on time", "You lost on time", "Draw: dead position" | Rematch, the mark |
| Our rematch offer | "Rematch sent" | the mark |
| Theirs | "Rematch?" | Accept, Decline, the mark |
| For 5 s after a rollback or refusal | "Not yet", "Offer not sent", or the Refusal's copy | the mark |
| Review | "Review · 12 of 40" | Latest |

  With a Move chosen, the Menu holds "Send and offer draw". "Draw: dead position" replaces "Draw:
  insufficient material", which needs three lines next to Rematch and Menu. The list's row for a draw
  offer keeps "Draw offered".
- The Refusals' copy: "Not allowed now", "Finish a game first", "Game deleted", "This game stopped",
  "Not a code", "No such code", "Code already used", "Your friend joined", "Try again in a minute",
  "Update Chess", "No connection", "The game moved on", "Not yet", "Couldn't save".
- The game's Menu (`FriendMenu`, N5: this Game's actions only): "This game stopped: the two phones
  disagree." or "Update Chess to continue this game" first when stopped, then "Send and offer draw"
  (a Move chosen), "Offer draw" (after one's own Move) or "Offer draw after your move", "Resign" /
  "Tap again to resign", "Moves", "Rename", "Forget game" / "Tap again to forget" (over or stopped).
  Moves and Rename are screens of their own over the Menu (N6), so system back returns to it.
- Button labels: "Send" ("Send this Move"), "Undo" ("Take this Move back before it is sent"),
  "Retry" ("Send it again"), "Accept" ("Accept the draw", "Accept the rematch"), "Decline" ("Decline
  the draw", "Decline the rematch"), "Rematch" ("Offer a rematch, Sides swapped"), "Cancel" ("Cancel
  this invite"), "Create code", "Save".
- Games lists finished Correspondence Games with the others: "2026.09.28 · ABCD · Won".
- About, once the Relay URL is set: "Chess uses the network only for Games with a friend: it sends
  their Moves to its Relay, with no name or account, and the Relay deletes each Game within 30 days of
  the last thing either phone sent it. Puzzles and Games against the computer never leave this
  phone."
- Keep the screen on while the board shows and the last touch or wheel event was under 5 minutes
  ago (contradiction 4). While it also waits on the opponent, the board syncs once a minute (until v3
  PR 3's live socket).
