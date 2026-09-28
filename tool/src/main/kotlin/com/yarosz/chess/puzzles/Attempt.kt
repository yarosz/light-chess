package com.yarosz.chess.puzzles

import com.yarosz.chess.rules.Move
import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.Square
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where an Attempt stands (CONTEXT.md; the save file writes these names, contradiction 5). */
@Serializable
enum class AttemptState {
    /** Under way, no mistake and no Puzzle Hint yet. */
    @SerialName("Open") OPEN,

    /** A wrong Move, or the Solution asked for, while Open. Scored once, at that moment (A3). */
    @SerialName("Failed") FAILED,

    /** A Puzzle Hint taken while Open: the Attempt ends unrated (A6). */
    @SerialName("Hinted") HINTED,

    /** Every Move of the Solution played while Open: the only win. */
    @SerialName("Solved") SOLVED,
}

/**
 * The stage of an Attempt in time (A5). [Attempt.delayMs] says how long a stage waits before
 * [Attempt.advance] moves it on; the owner keeps the clock, so the Attempt stays pure.
 */
enum class Stage {
    /** The Position before the setup Move, held [Attempt.HOLD_MS]. */
    HOLD,

    /** The opponent's setup Move, animated for [Attempt.SETUP_MS]. */
    SETUP,

    /** The user's Move. */
    PLAY,

    /** A correct Move landed; the opponent replies after [Attempt.REPLY_MS]. */
    REPLY,

    /** The Solution plays itself, one Move each [Attempt.SOLUTION_STEP_MS]. */
    SOLUTION,

    /** The line is over; the strip shows the result and Next (D1). */
    DONE,
}

/**
 * One try at one Puzzle, from its setup Move to its end: a pure, immutable state machine (A3-A6).
 * [played] holds the Moves after the setup Move, the user's and the opponent's replies, in order.
 * Every Move in it follows the Solution, except that the last may be another checkmate (A4).
 *
 * - A correct Move is the Solution's next Move, or any Move that checkmates. The rules core has
 *   already turned king-onto-own-rook into castling, and a promotion must match the stored piece
 *   unless it mates, which plain Move equality gives.
 * - A wrong Move is not played: the Attempt becomes Failed when Open, and Try Mode goes on.
 * - [hint] marks the piece the Solution moves next; an Open Attempt becomes Hinted, a Failed one
 *   stays Failed (the hint is free).
 * - [showSolution] plays the rest of the line; an Open Attempt becomes Failed.
 */
data class Attempt(
    val puzzle: Puzzle,
    val state: AttemptState = AttemptState.OPEN,
    val played: List<Move> = emptyList(),
    val stage: Stage = Stage.HOLD,
    /** The square of the Puzzle Hint ring, until the next Move. */
    val hintSquare: Square? = null,
    /** The last Move tried was wrong and taken back; cleared by the next correct Move. */
    val justWrong: Boolean = false,
    val wrongMoves: Int = 0,
    val hintsTaken: Int = 0,
    val solutionShown: Boolean = false,
) {
    /**
     * Every Position of the Attempt so far, for Review (R1.9): the Puzzle's start, then after the
     * setup Move, then after each played Move. Only the start while [Stage.HOLD].
     */
    val positions: List<Position> by lazy {
        if (stage == Stage.HOLD) return@lazy listOf(puzzle.start)
        val out = ArrayList<Position>(played.size + 2)
        out += puzzle.start
        var p = puzzle.position
        out += p
        for (m in played) {
            p = p.play(m)
            out += p
        }
        out
    }

    /** The latest Position: the one the user plays in. */
    val position: Position get() = positions.last()

    /** The Moves of [positions], the setup Move first. */
    val moves: List<Move> get() = if (stage == Stage.HOLD) emptyList() else listOf(puzzle.setupMove) + played

    /** The user may move now. */
    val userToMove: Boolean get() = stage == Stage.PLAY

    /** Under way: not yet at its result (D3's screen-on rule, the save file's in-progress puzzle). */
    val underWay: Boolean get() = stage != Stage.DONE

    /** Solved with no wrong Move, no Puzzle Hint and no Solution: what takes a Puzzle out of Missed (D2). */
    val clean: Boolean get() = state == AttemptState.SOLVED

    /** The user has played at least one Move of their own in this Attempt. */
    val userHasMoved: Boolean get() = played.isNotEmpty() || wrongMoves > 0

    /** How long [stage] waits before [advance], or null when it waits for the user. */
    val delayMs: Long?
        get() = when (stage) {
            Stage.HOLD -> HOLD_MS
            Stage.SETUP -> SETUP_MS
            Stage.REPLY -> REPLY_MS
            Stage.SOLUTION -> SOLUTION_STEP_MS
            Stage.PLAY, Stage.DONE -> null
        }

    /** The stage's wait is over: play the setup Move, finish its animation, reply, or step the Solution. */
    fun advance(): Attempt = when (stage) {
        Stage.HOLD -> copy(stage = Stage.SETUP)
        Stage.SETUP -> copy(stage = Stage.PLAY)
        Stage.REPLY -> playNext().let { if (it.lineOver) it.copy(stage = Stage.DONE) else it.copy(stage = Stage.PLAY) }
        Stage.SOLUTION -> playNext().let { if (it.lineOver) it.copy(stage = Stage.DONE) else it }
        Stage.PLAY, Stage.DONE -> this
    }

    /** The user played [move] (legal in [position]). A no-op unless it is the user's Move. */
    fun play(move: Move): Attempt {
        if (stage != Stage.PLAY) return this
        val now = position
        val expected = puzzle.solution[played.size]
        val after = now.play(move)
        if (move != expected && !after.isCheckmate) {
            return copy(
                state = if (state == AttemptState.OPEN) AttemptState.FAILED else state,
                hintSquare = null,
                justWrong = true,
                wrongMoves = wrongMoves + 1,
            )
        }
        val next = copy(played = played + move, hintSquare = null, justWrong = false)
        return if (after.isCheckmate || next.played.size == puzzle.solution.size) {
            next.copy(stage = Stage.DONE, state = if (state == AttemptState.OPEN) AttemptState.SOLVED else state)
        } else {
            next.copy(stage = Stage.REPLY)
        }
    }

    /** A Puzzle Hint: a ring on the piece to move (A6). Only while the user is to move. */
    fun hint(): Attempt {
        if (stage != Stage.PLAY) return this
        return copy(
            hintSquare = puzzle.solution[played.size].from,
            hintsTaken = hintsTaken + 1,
            state = if (state == AttemptState.OPEN) AttemptState.HINTED else state,
        )
    }

    /** Plays the rest of the Solution. Open becomes Failed; Hinted and Failed stay. */
    fun showSolution(): Attempt {
        if (stage != Stage.PLAY && stage != Stage.REPLY) return this
        return copy(
            stage = Stage.SOLUTION,
            solutionShown = true,
            hintSquare = null,
            justWrong = false,
            state = if (state == AttemptState.OPEN) AttemptState.FAILED else state,
        )
    }

    private val lineOver: Boolean
        get() = played.size == puzzle.solution.size || (played.isNotEmpty() && position.isCheckmate)

    private fun playNext(): Attempt = copy(played = played + puzzle.solution[played.size])

    companion object {
        /** A5's timings. */
        const val HOLD_MS = 500L
        const val SETUP_MS = 250L
        const val REPLY_MS = 300L
        const val SOLUTION_STEP_MS = 600L

        /**
         * An Attempt carried over from the save file: [moves] (UCI, after the setup Move) replayed
         * against the Solution. Null when they don't follow it, as after a new Pack changed the line
         * (the caller then restarts from the setup Move, F1). [done] and [solutionShown] come back as
         * saved; with no Moves played the Attempt starts again from [Stage.HOLD].
         */
        fun resume(
            puzzle: Puzzle,
            state: AttemptState,
            moves: List<String>,
            solutionShown: Boolean,
            done: Boolean,
        ): Attempt? {
            var p = puzzle.position
            val played = ArrayList<Move>(moves.size)
            for ((i, text) in moves.withIndex()) {
                val move = p.moveFromUci(text) ?: return null
                val expected = puzzle.solution.getOrNull(i) ?: return null
                val after = p.play(move)
                // Only the last Move may leave the Solution, and only by checkmate (A4).
                if (move != expected && !(i == moves.lastIndex && after.isCheckmate)) return null
                played += move
                p = after
            }
            val over = played.size == puzzle.solution.size || (played.isNotEmpty() && p.isCheckmate)
            val stage = when {
                done || over -> Stage.DONE
                solutionShown -> Stage.SOLUTION
                played.isEmpty() -> Stage.HOLD
                played.size % 2 == 1 -> Stage.REPLY
                else -> Stage.PLAY
            }
            return Attempt(puzzle, state, played, stage, solutionShown = solutionShown)
        }
    }
}
