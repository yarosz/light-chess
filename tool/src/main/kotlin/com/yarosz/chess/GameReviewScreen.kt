package com.yarosz.chess

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.yarosz.chess.board.ActionRow
import com.yarosz.chess.board.BoardTopBar
import com.yarosz.chess.board.PieceSet
import com.yarosz.chess.board.PositionView
import com.yarosz.chess.board.Review
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

    /** For the Piece Set only: it is kept in `puzzles.json` and every board draws it (P2, M1). */
    private val owner: PuzzleOwner by lazy { PuzzleOwner.of(lightContext.filesDir, lightContext::readAsset) }

    override fun createViewModel() = GameReviewViewModel(game, record)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val pieceSet by owner.pieceSet.collectAsState()
        val vm = viewModel
        val positions = record.game.positions
        val shown = vm.review.ply ?: positions.lastIndex
        val strip = GameStrip.replay(record, vm.review.ply)
        val buttons = strip.buttons.map { button ->
            StripButton(button.label, button.description) {
                if (button == GameButton.LATEST) vm.latest()
            }
        }
        LightTheme(colors = themeColors) {
            Column(
                Modifier.fillMaxSize().background(LightThemeTokens.colors.background),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                // E1: the arrow returns to the Games, as system back does; a replay has no Menu.
                BoardTopBar(strip.status, StripButton(UiCopy.BACK_DESCRIPTION) { goBack() })
                PositionView(
                    position = positions[shown],
                    lastMove = record.game.moves.getOrNull(shown - 1),
                    input = null,
                    bottom = record.userSide,
                    onTouch = vm::touch,
                    description = UiCopy.BOARD_DESCRIPTION,
                    pieceSet = pieceSet ?: PieceSet.DEFAULT,
                )
                ActionRow(
                    buttons, Modifier.weight(1f),
                    captured = rememberCapturedRow(record.game, shown, record.userSide, pieceSet ?: PieceSet.DEFAULT),
                    buttonSets = GameStrip.REPLAY_BUTTONS.map { set -> set.map { it.label } },
                )
            }
        }
    }
}
