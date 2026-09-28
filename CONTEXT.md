# Chess

The language of the Chess tool: a Light Phone III Tool for solving chess puzzles, playing the
computer, and playing a friend by correspondence, built for any LP3 owner who wants a quiet,
serious game.

## Platform

**Tool**:
An installable capability on LightOS, built with Light's SDK.
_Avoid_: app

## Board

**Position**:
Where every piece stands, whose move it is, and the castling, en passant and move-count facts that
decide which moves are legal. Two Positions are the same when all of these match.
_Avoid_: board, FEN (a FEN is one way of writing a Position down)

**Move**:
One player moving one piece (castling counts as one Move). Always legal in the Position it is
played from.
_Avoid_: turn

**Ply**:
One Move by one side, counted from the start of a Game or Puzzle. White's first Move is ply 1.
_Avoid_: half-move, turn

**Review**:
Stepping backward and forward through the Moves already played, without changing the Game or
Attempt. Leaving Review returns to the latest Position.
_Avoid_: history mode, replay

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
Puzzles come next. Shown with a question mark until it has settled.
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
claim of a draw.
_Avoid_: action, message

**Result**:
How a Game ended and why: a win for one side, or a draw, by checkmate, resignation, stalemate,
agreement, repetition, the 50-move rule, insufficient material or time.
_Avoid_: outcome, score

**Level**:
How strongly the computer plays, from 1 to 8. Level 8 is the computer's full strength.
_Avoid_: difficulty, Elo, strength

**Takeback**:
Undoing the user's last Move and the computer's reply in a Game against the computer. Allowed at
every Level and recorded with the Game.
_Avoid_: undo

**Game Hint**:
The computer's best Move in the current Position of a Game, shown briefly. Recorded with the Game.
_Avoid_: tip, suggestion, hint (alone)

## Playing a friend

**Correspondence Game**:
A Game between two Light Phone users, played at any pace, with days per Move. It is Live while both
players have it on screen, but that is a state of the Game, not a different kind of Game.
_Avoid_: online game, match, live game

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
