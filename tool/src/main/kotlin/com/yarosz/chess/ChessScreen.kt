package com.yarosz.chess

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.yarosz.chess.board.MoveInput
import com.yarosz.chess.board.POSITION_VIEW_SIZE
import com.yarosz.chess.board.PositionView
import com.yarosz.chess.board.Review
import com.yarosz.chess.board.Strip
import com.yarosz.chess.board.StripButton
import com.yarosz.chess.board.Touch
import com.yarosz.chess.board.Wheel
import com.yarosz.chess.puzzles.Pack
import com.yarosz.chess.rules.Game
import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.Side

/**
 * Free play for v1 PR 3: one Game from the start Position, both Sides moved by the user, so every
 * input path (tap-tap, drag, castling, captures, promotion, Review) can be tried on a device. The
 * puzzle flow replaces it in v1 PR 4.
 */
class ChessViewModel : LightViewModel<Unit>() {
    var game by mutableStateOf(Game.of())
        private set
    var input by mutableStateOf(MoveInput(Position.START))
        private set
    var review by mutableStateOf(Review())
        private set

    fun touch(touch: Touch) {
        if (review.active) {
            // A tap on the board leaves Review for the latest Position (R1.9).
            if (touch is Touch.Tap) review = Review()
            return
        }
        if (game.isOver) return
        val step = input.touch(touch)
        val move = step.move
        if (move == null) {
            input = step.input
        } else {
            game += move
            input = step.input.after(game.position)
        }
    }

    fun restart() {
        game = Game.of()
        input = MoveInput(game.position)
        review = Review()
    }

    fun leaveReview() {
        review = Review()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val key = Wheel.of(keyCode) ?: return false
        val next = review.wheel(key, game.ply) ?: return false
        review = next
        input = input.after(game.position)
        return true
    }
}

@InitialScreen
class ChessScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, ChessViewModel>(sealedActivity) {

    override val viewModelClass: Class<ChessViewModel>
        get() = ChessViewModel::class.java

    override fun createViewModel() = ChessViewModel()

    /** The Puzzles in the Tool's assets, read a Band at a time. The puzzle flow uses it (v1 PR 4). */
    val pack: Pack by lazy { Pack(lightContext::readAsset) }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val vm = viewModel
        val game = vm.game
        val reviewPly = vm.review.ply
        val shownPly = reviewPly ?: game.ply
        val status = when {
            reviewPly != null -> UiCopy.review(reviewPly, game.ply)
            else -> game.result?.let(UiCopy::result) ?: UiCopy.toMove(game.position.sideToMove)
        }
        val buttons = listOfNotNull(
            if (reviewPly != null) StripButton(UiCopy.LATEST, UiCopy.LATEST_DESCRIPTION, vm::leaveReview) else null,
            StripButton(UiCopy.RESTART, UiCopy.RESTART_DESCRIPTION, vm::restart),
        )
        LightTheme(colors = themeColors) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                PositionView(
                    position = game.positions[shownPly],
                    lastMove = game.moves.getOrNull(shownPly - 1),
                    input = if (reviewPly == null && !game.isOver) vm.input else null,
                    bottom = Side.WHITE,
                    onTouch = vm::touch,
                    description = UiCopy.BOARD_DESCRIPTION,
                )
                Strip(status, buttons, Modifier.width(POSITION_VIEW_SIZE))
            }
        }
    }
}
