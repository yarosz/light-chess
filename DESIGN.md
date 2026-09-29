# Design notes

Tunable values and the rules behind them. Rulings live in `docs/design/decision-log.md` (cited by id);
this file holds the constants we expect to adjust from measurements on the Light Phone III, and the
small decisions v1 PRs 3 and 4 made that the log doesn't cover.

## Layout (R1.8)

The LP3's app area is 1080 × 1168 px at 480 dpi: 360 dp wide, about 389 dp tall. The board is 312 dp
square (8 × 39 dp, 117 px squares), centred, 12 dp below the top of the app area, with 24 dp either
side. The strip is exactly as wide as the board, directly under it, and two `Copy` lines tall (90
design px, 58 dp on the LP3; never under 48 dp). About 7 dp stay free at the bottom.

The Side at the bottom is a parameter of the board view (`PositionView(bottom = ...)`). The puzzle
flow puts the side to move after the setup Move at the bottom (A5). The game screen puts the user's
Side at the bottom; "Flip board" in the Menu turns it (D10, v2), and the flip is kept in `games.json`.

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

`StripFitTest` checks every strip in the table below and in the game screen's: each button on one
line, each status in the room its buttons leave, in at most two lines (one for a game status but a
Result, R4.16), with the widest numbers ("Failed −999", "Review · 99 of 99", "Review · 9999 of
9999"). Akkurat can't ship with the repo, so the test measures with a stand-in: Helvetica's advance
widths with the narrow letters widened (i and j to 280/1000 em, t to 325), scaled by 1.14, at `Copy`
size for a 389 dp tall screen (19.45 sp). It is calibrated on LP3 screencaps (1080 px wide, 3 px per
dp, the strip from x 72 to 1008), three ways: twelve strings measured there (ink plus 3 dp of side
bearings) are each at least 1 dp narrower than the stand-in; the room before the first button,
from where its ink starts, is wider than the stand-in's for five button sets; and the stand-in wraps
"Your move" next to Takeback, Hint and Menu and "Computer thinking" next to Move now and Menu, as the
LP3 did, and keeps "Your move" next to Hint and Menu and "Review · 4 of 6" next to Latest and Menu on
one line, as the LP3 did. The v1 stand-in (Helvetica × 1.15) fell short on "Hint" and "Latest"
(Akkurat's i and t are wider); a wider scale would put "White to move" on three lines next to Hint,
Solution and Menu, where v1 has it on two (not yet seen on the LP3). A new strip string or state goes into the test.

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
  - "Opening book: games from the Lichess database (lichess.org), CC0, January 2018." (v2 PR 6,
    docs/book.md; the Book's dump is fixed, so its month is copy, not read from its manifest).
  - Then `tool/src/main/assets/about/notices.txt`, verbatim legal text kept out of code: the cburnett
    licence in full (BSD-3 asks a binary to reproduce it), Light's SDK (MIT) and the libraries of the
    release APK's runtime classpath by licence, with the Apache-2.0 notice. NOTICE names the same
    libraries.
  - "Engine: Pirarucu by Raoni Campos (ratosh), GPL-3.0." (D7, v2 PR 4), before the Book's line.
    NOTICE carries the longer credit.

## The game screen (v2 PR 4)

The rules are pure Kotlin in `games/GameFlow.kt`: `GameState` (the file, the Game on screen, a Game
Hint, the draw response, a pending second tap) and `GameFlow` (start, the turn flow, Takeback, Game
Hints, draw offers, Resign, what the computer does next), with `GameFlowTest`. `GameOwner` is the
process-wide owner that runs the computer on `EngineHost.shared`, the clock and `games.json`;
`GameStrip` picks the strip. Rulings: decision log "v2 PR 4".

- The Tool opens on the mode last used (D6), kept in `mode.txt`. "Play the computer" in the puzzle
  Menu returns to the Game in progress, or opens the new-game page; "Puzzles" in the game Menu goes
  back and stops the computer's search until the game mode shows again.
- The new-game page: "Level" with 1 to 8 on one line, "Play as" with "White", "Black" and "Random",
  and "Think Time" with "3 s", "10 s" and "30 s" at Level 8 only. The chosen option is in the content
  colour, the others lightened. "Start" starts; with a Game in progress it reads "Tap again to
  replace" first, above "The Game in progress is saved as unfinished." The choices are remembered
  (Level 1, White, 3 s the first time).
- The computer's Move lands no sooner than 300 ms after the user's (A5's reply delay), so a book Move
  or a quick Level doesn't land with the user's own, and slides in over 200 ms (F11). The user's Moves
  are instant. The board takes no Move while the computer thinks (contradiction 3); the wheel still
  reviews.
- Game Hint: a Level 8 search at the default Think Time (3 s); the strip reads "Finding a Game Hint"
  with Menu while it runs, and the board stays live (a Move stops the search, uncounted). Then the
  Puzzle Hint's ring on the piece and a target mark on its square (the dot, or the capture ring over a
  piece) show for 5 s or until the user's Move. It counts once shown.
- Keep the screen on while the computer thinks, and on the user's Move while the last touch or wheel
  event was under 5 minutes ago (contradiction 4), in the Menu too.

Buttons by context (contradiction 2):

| When | Status | Buttons |
|---|---|---|
| The user's Move | "Your move" | Hint, Menu |
| A Game Hint being found | "Finding a Game Hint" | Menu |
| The computer thinking | "Thinking" | Move now, Menu |
| The Result | the Result | Next, Menu |
| Review | "Review · 12 of 40" | Latest |
| A finished Game from Games | the Result, or Review | Back at the Result, Latest in Review |

Every game status holds one line on the LP3, but a Result, which may take the strip's second
(R4.16): in Akkurat, "Your move" wrapped next to Takeback, Hint and Menu, and "Computer thinking" next
to Move now and Menu. So Takeback is in the Menu only, the computer's think reads "Thinking", and
Review keeps Latest alone, which leaves room for "Review · 9999 of 9999".

"Game Hint" and "New game" don't fit as labels: next to Takeback and Menu, "Game Hint" leaves no
room for any status, and "New game" leaves too little for a Result. The labels are "Hint" and "Next";
their semantics labels are "Game Hint: show the computer's best Move" and "Start a new game". The
others: "Move now: the computer plays at once", "Back to the Games". Next opens the new-game page.

Results, from the user's view: "You won by checkmate", "You lost by checkmate", "You resigned", "You
won by resignation" (never, since the computer never resigns, F8, but a hand-edited file can say so),
"Draw agreed", "Draw by stalemate", "Draw by repetition", "Draw by the 50-move rule" and "Draw:
insufficient material" ("Draw by insufficient material" needs three lines next to Next and Menu).
A Game replaced before its end shows "Unfinished".

The Menu while a Game shows, top to bottom:

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
- "New game", "Games", "Puzzles", "About". The page scrolls by the wheel.

Games lists the finished Games, newest first, at most 50 (B7): "2026.09.28 · Level 3 · Won" (Won, Lost,
Draw or Unfinished); "No finished Games yet" when empty. A tap opens the Game at its Result; the
wheel reviews its Moves, and Back returns to the list.

## Play a friend (v3 PR 2)

Rulings: decision log "v3 PR 2 (expert rulings)" (W1-W10) and "v3 PR 2 (implementation)". The pure
parts are `FriendStrip` (the board's and the invite's strips), `FriendRows` (the page's order) and
`FinishedGames` (the Games page's merge), each checked by StripFitTest or FriendPagesTest.
`FriendOwner` is the process-wide owner over the sync engine; it exists only once the Relay URL is
set (`RelayConfig`, W8).

- The Menu entry: "Play a friend", or "Play a friend · Your move: 2". It sets the friend mode: the
  Tool's first screen becomes the Play a friend page (mode.txt `FRIEND`).
- The Play a friend page: the title "Play a friend" with "Menu" at the right of the top bar (next to
  "New game" and "Enter code" the strip has no room for it), the rows in E7's order ("ABCD · Your
  move · 2d", "ABCD · Their move · 2d", "ABCD-EFGH · Expires in 47h", "ABCD · Rematch sent", "ABCD ·
  Won", "ABCD · Out of sync", a Seat being taken as "ABCD-EFGH · Not sent"), and "No games yet.
  Create a code for a friend, or enter theirs." when empty. At the cap both buttons are lightened
  above "Finish a game first".
- New game: "Play as" (White, Black, Random), "Days per move" (1, 3, 7), "Create code". The invite
  page shows the code in LightOS Subtitle, alone on its line, then "Tell your friend this code. It
  works once, for 48 hours." Its strip: "Expires in 47h" with Cancel and Menu; the first Cancel
  shows "Tap again to cancel" next to Cancel alone; an unconfirmed cancel shows "Not sent" with Retry.
- Enter code: LightOS's text editor with the LP3 keyboard, "Join" to submit; the title reads "Enter
  code", then "Sending", then the answer: "Not a code", "No such code", "Code already used", "Try
  again in a minute", "No connection".
- The board: the user's Side at the bottom, no flip. The strip:

| When | Status | Buttons |
|---|---|---|
| The user's Move | "Your move · 2d" | Menu |
| A Move chosen, not sent (F11) | its SAN, such as "Nf3" | Send, Undo, Menu |
| Sending | "Sending" | Menu |
| Saved, not sent | "Not sent" | Retry, Menu |
| Their move | "Their move · 2d" | Menu |
| Their Deadline passed | "Time is up" | "Claim win" ("Claim win on time"), Menu |
| Their draw offer | "Draw offered" | Accept, Decline |
| Stopped | "Out of sync", "Update Chess", "Game deleted" or "Seat lost" | Menu |
| The Result | R4.13's copy, "You won on time", "You lost on time", "Draw: dead position" | Rematch, Menu |
| Our rematch offer | "Rematch sent" | Menu |
| Theirs | "Rematch?" | Accept, Decline |
| For 5 s after a rollback or refusal | "Not yet", "Offer not sent", or the Refusal's copy | Menu |
| Review | "Review · 12 of 40" | Latest |

  The chosen Move's strip is the one with four buttons: Menu there holds "Send and offer draw".
  "Draw: dead position" replaces "Draw: insufficient material", which needs three lines next to
  Rematch and Menu.
- The Refusals' copy: "Not allowed now", "Finish a game first", "Game deleted", "This game stopped",
  "Not a code", "No such code", "Code already used", "Your friend joined", "Try again in a minute",
  "Update Chess", "No connection", "The game moved on", "Not yet", "Couldn't save".
- The game's Menu: "This game stopped: the two phones disagree." or "Update Chess to continue this
  game" first when stopped, then "Send and offer draw" (a Move chosen), "Offer draw" (after one's own
  Move) or "Offer draw after your move", "Resign" / "Tap again to resign", "Moves", "Rename", "Forget
  game" / "Tap again to forget" (over or stopped), "Play a friend", "About".
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
