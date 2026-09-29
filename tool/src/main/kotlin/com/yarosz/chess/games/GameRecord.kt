package com.yarosz.chess.games

import com.yarosz.chess.rules.Game
import com.yarosz.chess.rules.GameEvent
import com.yarosz.chess.rules.Move
import com.yarosz.chess.rules.Side

/**
 * A [game] against the computer and what is recorded with it (B5, B7): the user's Side, the Level,
 * the Level-8 think time, and how many Takebacks and Game Hints the user took. [Pgn] writes it down
 * and reads it back.
 *
 * [date] is PGN's own form, `2026.09.28`, with `??` for anything unknown. [seed] is the Game's seed:
 * the computer's picks, from the Book and among its near-best Moves, draw from it (with the Ply), so a
 * resume or a Takeback replays the same picks. [evals] holds the true evaluation the computer found
 * before each Move it searched for (G1 judges draw offers on it), keyed by that Move's Ply, in
 * centipawns from White's view.
 */
data class GameRecord(
    val game: Game,
    val userSide: Side = Side.WHITE,
    /** 1 to 8; null when no computer plays (a hand-written game). */
    val level: Int? = null,
    /** Level 8's think time in seconds (E2); null when not set. */
    val thinkTimeSeconds: Int? = null,
    val takebacks: Int = 0,
    val gameHints: Int = 0,
    val date: String = UNKNOWN_DATE,
    val seed: Long? = null,
    val evals: Map<Int, Int> = emptyMap(),
) {
    init {
        require(level == null || level in 1..8) { "level must be 1-8, got $level" }
        require(thinkTimeSeconds == null || thinkTimeSeconds > 0) { "think time must be positive, got $thinkTimeSeconds" }
        require(takebacks >= 0 && gameHints >= 0) { "counters can't be negative" }
        require(DATE.matches(date)) { "date must be YYYY.MM.DD with ? for unknown digits, got $date" }
        require(evals.keys.all { it in 1..game.ply }) { "an eval for a Ply the Game doesn't have: ${evals.keys}" }
    }

    /** This record with [event] appended to its Game. */
    operator fun plus(event: GameEvent): GameRecord = copy(game = game + event)

    /** This record with one more Game Hint counted. */
    fun withGameHint(): GameRecord = copy(gameHints = gameHints + 1)

    /**
     * The index in [Game.events] of the user's latest Move, or null when there is none or the Game is
     * over: a finished Game goes to the history and takes no Takeback.
     */
    private val takebackIndex: Int?
        get() {
            if (game.isOver) return null
            var ply = 0
            var found: Int? = null
            for ((i, event) in game.events.withIndex()) {
                if (event !is Move) continue
                if (game.positions[ply].sideToMove == userSide) found = i
                ply++
            }
            return found
        }

    val canTakeBack: Boolean get() = takebackIndex != null

    /**
     * A Takeback (contradiction 6): the event list is cut just before the user's latest Move, which
     * drops it, the computer's reply if there is one yet, any draw offer after it, and their evals;
     * the counter goes up by one. The user is to move afterwards. Throws when [canTakeBack] is false.
     */
    fun takeback(): GameRecord {
        val index = checkNotNull(takebackIndex) { "no Move of the user's to take back" }
        val cut = Game.of(game.start, game.events.subList(0, index))
        return copy(game = cut, takebacks = takebacks + 1, evals = evals.filterKeys { it <= cut.ply })
    }

    /** This record with the computer's [move] appended, and the true [eval] (White's view) it found, if it searched. */
    fun withComputerMove(move: Move, eval: Int?): GameRecord {
        val next = game + move
        return copy(game = next, evals = if (eval == null) evals else evals + (next.ply to eval))
    }

    companion object {
        const val UNKNOWN_DATE = "????.??.??"
        private val DATE = Regex("""[0-9?]{4}\.[0-9?]{2}\.[0-9?]{2}""")
    }
}
