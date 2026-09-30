package com.yarosz.chess

import com.yarosz.chess.puzzles.Attempt
import com.yarosz.chess.puzzles.PuzzleState
import com.yarosz.chess.puzzles.Stage
import com.yarosz.chess.rules.Side

/** The Puzzle board's strip buttons. The back arrow is not among them (N3), and there is no Menu (N5). */
enum class PuzzleButton(val label: String, val description: String) {
    HINT(UiCopy.HINT, UiCopy.HINT_DESCRIPTION),
    SOLUTION(UiCopy.SOLUTION, UiCopy.SOLUTION_DESCRIPTION),
    NEXT(UiCopy.NEXT, UiCopy.NEXT_DESCRIPTION),
    LATEST(UiCopy.LATEST, UiCopy.LATEST_DESCRIPTION),
}

/**
 * What the Puzzle board's strip shows (R1.8, D1, F6), as pure data so `StripFitTest` checks every
 * case: the back arrow, the status, and Hint and Solution, Next, or Latest (then Next at the result).
 * DESIGN.md "The strip" holds the table.
 */
data class PuzzleStrip(val status: String, val buttons: List<PuzzleButton>) {
    companion object {
        /** Every Puzzle in the Pack is finished: the arrow and the status only. */
        val FINISHED = PuzzleStrip(UiCopy.PACK_FINISHED, emptyList())

        /** The strip for [attempt] in [session], with Review showing Ply [reviewPly] (null: the latest Position). */
        fun of(session: PuzzleState, attempt: Attempt, reviewPly: Int?): PuzzleStrip = of(
            stage = attempt.stage,
            review = reviewPly?.let { UiCopy.review(it, attempt.positions.lastIndex) },
            result = UiCopy.result(attempt.state, session.rated, session.currentDelta, attempt.solutionShown),
            justWrong = attempt.justWrong,
            first = session.rated && session.firstPuzzle && !attempt.userHasMoved,
            solver = attempt.puzzle.solver,
        )

        /**
         * The strip from what decides it: the Attempt's [stage], the Review line [review] (null
         * outside Review), the [result]'s copy (shown once the stage is done), "Try again"
         * ([justWrong]), and the very first Puzzle before its first Move ([first], F6).
         */
        fun of(stage: Stage, review: String?, result: String, justWrong: Boolean, first: Boolean, solver: Side): PuzzleStrip {
            val status = when {
                review != null -> review
                stage == Stage.DONE -> result
                stage == Stage.SOLUTION -> UiCopy.SOLUTION_PLAYING
                stage == Stage.REPLY -> UiCopy.CORRECT
                justWrong -> UiCopy.TRY_AGAIN
                first -> UiCopy.FIRST_PUZZLE
                else -> UiCopy.toMove(solver)
            }
            val buttons = buildList {
                if (review != null) add(PuzzleButton.LATEST)
                when {
                    stage == Stage.DONE -> add(PuzzleButton.NEXT)
                    stage == Stage.SOLUTION || first -> {}
                    review == null -> {
                        add(PuzzleButton.HINT)
                        add(PuzzleButton.SOLUTION)
                    }
                }
            }
            return PuzzleStrip(status, buttons)
        }
    }
}
