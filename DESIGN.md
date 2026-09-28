# Design notes

Tunable values and the rules behind them. Rulings live in `docs/design/decision-log.md` (cited by id);
this file holds the constants we expect to adjust from measurements on the Light Phone III, and the
small decisions v1 PR 3 made that the log doesn't cover.

## Layout (R1.8)

The LP3's app area is 1080 × 1168 px at 480 dpi: 360 dp wide, about 389 dp tall. The board is 312 dp
square (8 × 39 dp, 117 px squares), centred, 12 dp below the top of the app area, with 24 dp either
side. The strip is 48 dp tall and exactly as wide as the board, directly under it. About 17 dp stay
free at the bottom.

The Side at the bottom is a parameter of the board view (`PositionView(bottom = ...)`). v1 has no
board flip (D10); the puzzle flow puts the side to move after the setup Move at the bottom (A5), and
free play keeps White there.

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
- A tap on the board during Review returns to the latest Position (the "live" tap of R1.9); the strip
  shows a Latest button while Review is on.
- The emulator's Android build doesn't know Light's wheel keycodes: an injected 317-319 arrives as
  keycode 0. The wheel is covered by `ReviewTest` and must be checked on the LP3.

## The strip (R1.8, F11, contradiction 2)

A status line on the left and up to three text buttons on the right, as in Reader's footer: LightOS
`Copy` text, the status in the secondary content colour, buttons in the content colour with LightOS's
press without a ripple, 8 dp padding around each button. Every button carries a semantics label and
the Button role (F11). PR 4 chooses the buttons by context.

## Copy (F11: English, one strings object)

All copy lives in `UiCopy`. The board's accessibility label is "Chess board"; "board" is fine in UI
copy for the object on screen, while code names the chess state a Position. The status line reads
"White to move" or "Black to move" (A5), "White wins", "Black wins" or "Draw" once the Game is over,
and "Review · $ply of $latest" in Review. Free play's buttons are "Restart" (label "Restart from the
start position") and, in Review, "Latest" (label "Back to the latest position").
