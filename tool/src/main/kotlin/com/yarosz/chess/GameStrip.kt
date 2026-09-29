package com.yarosz.chess

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.yarosz.chess.board.CapturedRowState
import com.yarosz.chess.board.PieceSet
import com.yarosz.chess.board.StripLayout
import com.yarosz.chess.games.GameRecord
import com.yarosz.chess.games.GameState
import com.yarosz.chess.games.Phase
import com.yarosz.chess.rules.CapturedPieces
import com.yarosz.chess.rules.Game
import com.yarosz.chess.rules.Side

/** The game screen's strip buttons. */
enum class GameButton(val label: String, val description: String) {
    HINT(UiCopy.HINT, UiCopy.GAME_HINT_DESCRIPTION),
    MOVE_NOW(UiCopy.MOVE_NOW, UiCopy.MOVE_NOW_DESCRIPTION),
    NEXT(UiCopy.NEXT, UiCopy.NEW_GAME_DESCRIPTION),
    LATEST(UiCopy.LATEST, UiCopy.LATEST_DESCRIPTION),
    MENU(UiCopy.MENU, UiCopy.MENU_DESCRIPTION),
    BACK(UiCopy.BACK, UiCopy.GAMES_BACK_DESCRIPTION),
}

/**
 * What the game screen's strip shows (contradiction 2: at most three buttons, by context), as pure
 * data so `StripFitTest` checks every case. DESIGN.md "The game screen" holds the table.
 *
 * Every status but a Result reads on one line on the LP3 (R4.16): Takeback is in the Menu, and in
 * Review the strip holds Latest alone, so the widest Ply numbers fit next to it.
 */
data class GameStrip(
    val status: String,
    val buttons: List<GameButton>,
    /** The lines [status] may take: one, but a Result is a sentence and may use the strip's second (R4.13). */
    val statusLines: Int = 1,
) {
    companion object {
        /** The strip for [state], with Review showing Ply [reviewPly] (null: the latest Position). */
        fun of(state: GameState, reviewPly: Int?): GameStrip {
            val record = state.record ?: return GameStrip(UiCopy.NEW_GAME, listOf(GameButton.MENU))
            val phase = state.phase
            if (reviewPly != null) return review(record, reviewPly)
            return when (phase) {
                Phase.OVER -> result(record, listOf(GameButton.NEXT, GameButton.MENU))
                Phase.COMPUTER -> GameStrip(UiCopy.THINKING, listOf(GameButton.MOVE_NOW, GameButton.MENU))
                else -> if (state.hintPending) {
                    GameStrip(UiCopy.FINDING_HINT, listOf(GameButton.MENU))
                } else {
                    GameStrip(UiCopy.YOUR_MOVE, listOf(GameButton.HINT, GameButton.MENU))
                }
            }
        }

        /** The strip of a finished Game replayed from the Games page, showing Ply [reviewPly] (null: its end). */
        fun replay(record: GameRecord, reviewPly: Int?): GameStrip =
            if (reviewPly == null) result(record, listOf(GameButton.BACK))
            else review(record, reviewPly)

        private fun result(record: GameRecord, buttons: List<GameButton>) =
            GameStrip(UiCopy.gameResult(record.game.result, record.userSide), buttons, StripLayout.STATUS_MAX_LINES)

        /** Review, on the game screen and in a replay alike: the rest of the strip is back at the latest Position. */
        private fun review(record: GameRecord, reviewPly: Int) =
            GameStrip(UiCopy.review(reviewPly, record.game.ply), listOf(GameButton.LATEST))
    }
}

/**
 * The captured-pieces row for a Game's board (P3): [game]'s Captured Pieces after [ply] Moves, the Ply
 * on screen, with [bottom] at the bottom of the board, drawn in [pieceSet]. Every board in a Game has
 * it (the game screen, a Correspondence Game and Games Review); a Puzzle's never does.
 */
fun capturedRow(game: Game, ply: Int, bottom: Side, pieceSet: PieceSet): CapturedRowState {
    val captured = CapturedPieces.of(game, ply)
    return CapturedRowState(captured, bottom, pieceSet, UiCopy.capturedPieces(captured, bottom))
}

/** [capturedRow], computed again only when the Game, the Ply shown, the bottom Side or the Piece Set changes. */
@Composable
fun rememberCapturedRow(game: Game, ply: Int, bottom: Side, pieceSet: PieceSet): CapturedRowState =
    remember(game, ply, bottom, pieceSet) { capturedRow(game, ply, bottom, pieceSet) }
