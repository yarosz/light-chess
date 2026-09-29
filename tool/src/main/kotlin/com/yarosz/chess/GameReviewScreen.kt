package com.yarosz.chess

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
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.yarosz.chess.board.POSITION_VIEW_SIZE
import com.yarosz.chess.board.PositionView
import com.yarosz.chess.board.Review
import com.yarosz.chess.board.Strip
import com.yarosz.chess.board.StripButton
import com.yarosz.chess.board.Touch
import com.yarosz.chess.board.Wheel
import com.yarosz.chess.games.GameRecord

/** Review of one finished Game from the Games page, by the wheel (R1.9, F2). It opens at the Result. */
class GameReviewViewModel(private val game: GameOwner, val record: GameRecord) : WheelViewModel<Unit>() {
    var review by mutableStateOf(Review())
        private set

    fun latest() {
        game.touched()
        review = Review()
    }

    fun touch(touch: Touch) {
        if (touch is Touch.Tap && review.active) latest()
    }

    override fun onWheel(key: Wheel): Boolean {
        val next = review.wheel(key, record.game.ply) ?: return false
        game.touched()
        review = next
        return true
    }

    override fun onAppPause() = game.pause()
}

class GameReviewScreen(
    sealedActivity: SealedLightActivity,
    private val record: GameRecord,
) : LightScreen<Unit, GameReviewViewModel>(sealedActivity) {

    override val viewModelClass: Class<GameReviewViewModel>
        get() = GameReviewViewModel::class.java

    private val game: GameOwner by lazy { GameOwner.of(lightContext.filesDir, lightContext::readAsset) }

    override fun createViewModel() = GameReviewViewModel(game, record)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val vm = viewModel
        val positions = record.game.positions
        val shown = vm.review.ply ?: positions.lastIndex
        val strip = GameStrip.replay(record, vm.review.ply)
        val buttons = strip.buttons.map { button ->
            StripButton(button.label, button.description) {
                if (button == GameButton.LATEST) vm.latest() else goBack()
            }
        }
        LightTheme(colors = themeColors) {
            Column(
                Modifier.fillMaxSize().background(LightThemeTokens.colors.background).padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                PositionView(
                    position = positions[shown],
                    lastMove = record.game.moves.getOrNull(shown - 1),
                    input = null,
                    bottom = record.userSide,
                    onTouch = vm::touch,
                    description = UiCopy.BOARD_DESCRIPTION,
                )
                Strip(strip.status, buttons, Modifier.width(POSITION_VIEW_SIZE))
            }
        }
    }
}
