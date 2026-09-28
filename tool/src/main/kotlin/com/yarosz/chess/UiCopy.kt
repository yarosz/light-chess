package com.yarosz.chess

import com.yarosz.chess.rules.Result
import com.yarosz.chess.rules.Side

/** Every piece of UI copy, in one place (F11: English only). DESIGN.md quotes each string. */
object UiCopy {
    const val BOARD_DESCRIPTION = "Chess board"
    const val WHITE_TO_MOVE = "White to move"
    const val BLACK_TO_MOVE = "Black to move"
    const val WHITE_WINS = "White wins"
    const val BLACK_WINS = "Black wins"
    const val DRAW = "Draw"
    const val RESTART = "Restart"
    const val RESTART_DESCRIPTION = "Restart from the start position"
    const val LATEST = "Latest"
    const val LATEST_DESCRIPTION = "Back to the latest position"

    fun toMove(side: Side) = if (side == Side.WHITE) WHITE_TO_MOVE else BLACK_TO_MOVE

    fun result(result: Result) = when (result) {
        is Result.Win -> if (result.winner == Side.WHITE) WHITE_WINS else BLACK_WINS
        is Result.Draw -> DRAW
    }

    /** The strip's status in Review: the Ply shown and the latest one. */
    fun review(ply: Int, latest: Int) = "Review · $ply of $latest"
}
