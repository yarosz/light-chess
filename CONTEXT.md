# Chess

The language of the Chess tool: a Light Phone III Tool for solving chess puzzles, playing the
computer, and playing a friend by correspondence, built for any LP3 owner who wants a quiet,
serious game.

## Platform

**Tool**:
An installable capability on LightOS, built with Light's SDK.
_Avoid_: app

## Play

**Side**:
White or Black: the pieces one player moves. A Position says which Side is to move.
_Avoid_: colour, color

**Position**:
Where every piece stands, whose move it is, and the castling, en passant and move-count facts that
decide which moves are legal. Two Positions are the same when all of these match.
_Avoid_: board (the board is the drawing on screen that shows a Position), FEN (a FEN is one way of
writing a Position down)

**Move**:
One player moving one piece (castling counts as one Move). Always legal in the Position it is
played from.
_Avoid_: turn

**Ply**:
One Move by one side, counted from the start of a Game or Puzzle. White's first Move is ply 1.
_Avoid_: half-move, turn

**Piece Set**:
The player's chosen look for the pieces: geometric or rounded. It changes how the pieces are drawn
and nothing about play.
_Avoid_: theme (a theme is a kind of Puzzle), skin, style

**Review**:
Stepping backward and forward through the Moves already played, without changing the Game or
Attempt. Leaving Review returns to the latest Position.
_Avoid_: history mode, replay (a Missed Puzzle is replayed; a finished Game is reviewed)

## Puzzles

**Pack**:
The set of Puzzles that ships inside the Tool. A new version of the Tool may ship a new Pack.
_Avoid_: database, puzzle set

**Puzzle**:
A Position and its Solution, taken from the Lichess puzzle database. The first Move of a Puzzle is
the opponent's; the user plays from the Position after it.
_Avoid_: problem, exercise, tactic

**Solution**:
The stored line of Moves that solves a Puzzle. Any Move that gives checkmate also solves the step
it is played on.
_Avoid_: answer, line, key

**Puzzle Rating**:
How hard a Puzzle is, on the same scale as the Player Rating.
_Avoid_: difficulty, level

**Band**:
The Puzzles in the Pack whose Puzzle Rating falls in one 100-point slice, such as 1500 to 1599.
_Avoid_: bucket, tier, level

**Player Rating**:
The user's own puzzle-solving strength. It changes after each rated Attempt and decides which
Puzzles come next. Shown in parentheses until it has settled.
_Avoid_: Elo, score, rating (alone)

**Attempt**:
One try at one Puzzle, from its first Move to its end. An Attempt is Open while it is under way,
then Solved, Failed or Hinted. Only a first Attempt at a Puzzle can change the Player Rating.
_Avoid_: try, round, session

**Failed**:
An Attempt in which the user played a Move that is not in the Solution and does not give
checkmate, or gave up and looked at the Solution. It counts against the Player Rating once.
_Avoid_: wrong, lost

**Hinted**:
An Attempt in which the user took a Puzzle Hint before going wrong. It ends unrated: the Player
Rating does not change.
_Avoid_: assisted, skipped

**Try Mode**:
What an Attempt becomes after a wrong Move: the Move is taken back and the user may keep trying.
Nothing done in Try Mode changes the Player Rating.
_Avoid_: retry, practice

**Puzzle Hint**:
A mark on the piece that should move next in a Puzzle.
_Avoid_: tip, clue, hint (alone)

**Missed**:
The most recent Puzzles the user Failed or Hinted, kept so they can be replayed unrated. A Puzzle
leaves Missed once it is replayed cleanly.
_Avoid_: mistakes, review list

## Games

**Game**:
A contest between two sides from a start Position, made of its Game Events in order. Everything
else about a Game, including the current Position and its Result, follows from replaying them.
_Avoid_: match, session

**Game Event**:
One thing that happens in a Game: a Move, a resignation, a draw offer, acceptance or refusal, or a
Timeout Claim.
_Avoid_: action, message

**Result**:
How a Game ended and why: a win for one side, or a draw, by checkmate, resignation, stalemate,
agreement, repetition, the 50-move rule, insufficient material or time. In a Correspondence Game each
phone derives it from the Game Events; the Relay never states one.
_Avoid_: outcome, score

**Level**:
How strongly the computer plays, from 1 to 8. Level 8 is the computer's full strength.
_Avoid_: difficulty, Elo, strength

**Book**:
The opening Moves the computer may play without thinking, in the first plies of a Game at Level 2
and above, taken from strong players' Games on Lichess. Once a Game leaves the Book, the computer
thinks for every Move after.
_Avoid_: opening book (alone), repertoire, library

**Think Time**:
How long the computer may think about one Move at Level 8: 3 seconds (the default), 10 or 30. Below
Level 8 the computer's Moves are quick and Think Time changes nothing.
_Avoid_: clock, time control, thinking time

**Move Now**:
Telling the computer to stop thinking and play at once. It plays the best Move it has found so far.
_Avoid_: force move, hurry

**Takeback**:
Undoing the user's last Move and the computer's reply in a Game against the computer. Allowed at
every Level and recorded with the Game.
_Avoid_: undo

**Captured Pieces**:
The pieces a Side has taken from the other in a Game, up to the Position shown. A piece taken is the
one its capture removed from the board: a pawn that was promoted and then taken counts as the piece
it became.
_Avoid_: taken pieces, graveyard, losses

**Material Lead**:
How far one Side is ahead in material in a Position, counting a pawn as 1, a knight or bishop as 3, a
rook as 5 and a queen as 9. Nothing when the material is even.
_Avoid_: advantage, points

**Game Hint**:
The computer's best Move in the current Position of a Game, shown briefly. Recorded with the Game.
_Avoid_: tip, suggestion, hint (alone)

## Playing a friend

**Correspondence Game**:
A Game between two Light Phone users, played at any pace, with days per Move. It is Live while both
players have it on screen, but that is a state of the Game, not a different kind of Game.
_Avoid_: online game, match, live game

**Live**:
The state of a Correspondence Game while both players have its board open: each phone holds a live
socket to the Relay, and the Relay has heard from both in the last 10 seconds. Game Events then
arrive at once, and the board says "Live" in place of the Time Left. Nothing else about the Game
changes: the same Days per Move and Deadline (decision log G3, U1-U6).
_Avoid_: online, real-time

**Seat**:
One side's place in a Correspondence Game, held by one phone. Losing the phone, or removing the
Tool, loses the Seat.
_Avoid_: player, account, slot

**Invite Code**:
A short code that one player passes to another, outside the Tool, to let them take the other Seat
of a new Correspondence Game. It works once and expires.
_Avoid_: game code, link, token

**Relay**:
The server that keeps each Correspondence Game's Game Events in order and passes them between the
two phones. It knows nothing about chess: each phone checks every Game Event itself.
_Avoid_: server (alone), backend, lobby

**Deadline**:
When the side to move's time for its Move runs out in a Correspondence Game: its days per Move after
the latest Move (or after the Game started), in the Relay's time. A Move made after it still counts
until the other side makes a Timeout Claim.
_Avoid_: clock, flag, time control

**Timeout Claim**:
The Game Event by which the side not to move, once the Deadline has passed, wins the Game on time. It
is one tap, never automatic. If the claimant has only its king left, the Game is drawn instead.
_Avoid_: flag, timeout (alone), claim (alone)

**Rematch**:
A new Correspondence Game offered through the log of one that is over, with the Sides swapped and the
same days per Move. Each Game gets one offer, which the other Seat accepts or declines.
_Avoid_: return game, revenge

**Out of Sync**:
The state of a Correspondence Game whose phone found a Game Event its rules core refuses, or whose
Relay disagrees with its own record. Nothing more is sent or accepted for that Game.
_Avoid_: desync, corrupted, conflict

**Days per Move**:
How long each side has for each of its Moves in a Correspondence Game: 1, 3 or 7 days, chosen by the
player who creates the Invite Code (3 by default). The Deadline follows from it.
_Avoid_: time control, clock

**Time Left**:
How long the side to move has until the Deadline, in the Relay's time as this phone estimates it,
shown in one unit: hours to the nearest while under 48 are left, days to the nearest above, the
last hour in minutes rounded up ("3d", "47h", "5h", "40m"; decision log W12).
_Avoid_: clock, remaining time

**Opponent Label**:
This phone's own name for the other player of a Correspondence Game: the first four characters of the
Invite Code until the user renames it. It is never sent.
_Avoid_: opponent name, nickname, player

**Pending Entry**:
The user's Game Event in a Correspondence Game, saved on the phone and not yet stored by the Relay.
A Game has at most one; it is sent again until the Relay stores it, or rolled back when the Game has
moved on so that it no longer makes sense.
_Avoid_: queued move, outbox

**Stopped**:
A Correspondence Game that nothing more is sent or accepted for: Out of Sync, waiting for the user to
update Chess, deleted by the Relay, or its Seat lost. It counts toward the five Games until the user
forgets it.
_Avoid_: frozen, broken
