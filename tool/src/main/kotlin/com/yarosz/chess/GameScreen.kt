package com.yarosz.chess

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.yarosz.chess.board.MoveInput
import com.yarosz.chess.board.PieceSet
import com.yarosz.chess.board.PositionView
import com.yarosz.chess.board.Review
import com.yarosz.chess.board.ActionRow
import com.yarosz.chess.board.BoardTopBar
import com.yarosz.chess.board.StripButton
import com.yarosz.chess.board.Touch
import com.yarosz.chess.board.Wheel
import com.yarosz.chess.games.GameState
import com.yarosz.chess.games.Phase

/**
 * The computer's board: the touches in progress ([MoveInput]) and Review over the process's
 * [GameOwner], which runs the computer. The computer thinks while the board or its Menu shows, and
 * stops when the board leaves for Home (N2, R4.11) or the Tool pauses (B6).
 */
class GameViewModel(private val game: GameOwner) : WheelViewModel<Unit>() {
    private var input by mutableStateOf<MoveInput?>(null)
    private var review by mutableStateOf(Review())

    /** The Game [review] belongs to, by its seed: a new Game starts at its latest Position. */
    private var reviewFor by mutableStateOf<Long?>(null)

    /** The input for the user's Move in [state]; null while the computer thinks or the Game is over (contradiction 3). */
    fun input(state: GameState): MoveInput? {
        val record = state.record ?: return null
        if (state.phase != Phase.USER) return null
        val position = record.game.position
        val current = input
        if (current != null && current.position == position) return current
        return MoveInput(position, setOf(record.userSide))
    }

    fun review(state: GameState): Review {
        val ply = review.ply ?: return review
        val record = state.record ?: return Review()
        return if (reviewFor != record.seed || ply >= record.game.ply) Review() else review
    }

    fun touch(touch: Touch) {
        game.touched()
        val state = game.state.value ?: return
        if (review(state).active) {
            if (touch is Touch.Tap) review = Review()
            return
        }
        val step = input(state)?.touch(touch) ?: return
        input = step.input
        step.move?.let(game::play)
    }

    fun leaveReview() {
        game.touched()
        review = Review()
    }

    override fun onWheel(key: Wheel): Boolean {
        val state = game.state.value ?: return false
        val record = state.record ?: return false
        val next = review(state).wheel(key, record.game.ply) ?: return false
        game.touched()
        review = next
        reviewFor = record.seed
        return true
    }

    /** The board showing, or the Tool opening on it: the computer resumes if it was thinking (B6). */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) = game.resume()

    override fun onAppPause() = game.pause()
}

/**
 * The computer's board (v2 PR 4; N2: a place, one step from Home): the user's Side at the bottom, the
 * top bar's arrow, status and Menu mark and the action row's buttons by context (layout E, E1-E2). With no Game at all (a lost file)
 * it goes back to Home.
 */
class GameScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, GameViewModel>(sealedActivity) {

    override val viewModelClass: Class<GameViewModel>
        get() = GameViewModel::class.java

    private val game: GameOwner by lazy { GameOwner.of(lightContext.filesDir, lightContext::readAsset) }

    /** For the Piece Set only: it is kept in `puzzles.json` and every board draws it (P2, M1). */
    private val owner: PuzzleOwner by lazy { PuzzleOwner.of(lightContext.filesDir, lightContext::readAsset) }

    override fun createViewModel() = GameViewModel(game)

    /** Leaving the board for Home stops the computer's search until the board shows again (N2, R4.11). */
    override fun onScreenDestroy() = game.pause()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val gameState by game.state.collectAsState()
        val pieceSet by owner.pieceSet.collectAsState()
        val awake by game.awake.collectAsState()
        LightTheme(colors = themeColors) {
            Box(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                AndroidView(factory = { View(it) }, modifier = Modifier.size(0.dp), update = { it.keepScreenOn = awake })
                // The board waits for its file and for the Piece Set (read before any Band, M4), so the
                // first frame of a Game draws the chosen set.
                val state = gameState ?: return@Box
                val set = pieceSet ?: return@Box
                if (state.record == null) {
                    LaunchedEffect(Unit) { goBack() }
                    return@Box
                }
                GameView(state, set)
            }
        }
    }

    @Composable
    private fun GameView(state: GameState, pieceSet: PieceSet) {
        val vm = viewModel
        val record = state.record ?: return
        val motion by game.motion.collectAsState()
        val review = vm.review(state)
        val positions = record.game.positions
        val shown = review.ply ?: positions.lastIndex
        val strip = GameStrip.of(state, review.ply)
        val buttons = strip.buttons.map { button ->
            StripButton(button.label, button.description) {
                when (button) {
                    GameButton.HINT -> game.hint()
                    GameButton.MOVE_NOW -> game.moveNow()
                    GameButton.NEXT -> { game.touched(); navigateTo({ MenuScreen(it, MenuPage.NEW_GAME, overGame = true) }) }
                    GameButton.LATEST -> vm.leaveReview()
                }
            }
        }
        val menu = StripButton(UiCopy.MENU_DESCRIPTION) { game.touched(); navigateTo({ MenuScreen(it, MenuPage.MENU, overGame = true) }) }
        val live = review.ply == null
        // E1, E2: the top bar (the arrow, the status, the Menu mark), the board, then the action row.
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            BoardTopBar(strip.status, StripButton(UiCopy.BACK_DESCRIPTION) { goBack() }, menu.takeIf { strip.menu })
            PositionView(
                position = positions[shown],
                lastMove = record.game.moves.getOrNull(shown - 1),
                input = if (live) vm.input(state) else null,
                bottom = state.bottom,
                onTouch = vm::touch,
                description = UiCopy.BOARD_DESCRIPTION,
                hint = state.hint?.from.takeIf { live },
                hintTarget = state.hint?.to.takeIf { live },
                motion = motion.takeIf { live },
                pieceSet = pieceSet,
            )
            ActionRow(
                buttons, Modifier.weight(1f),
                captured = rememberCapturedRow(record.game, shown, state.bottom, pieceSet),
                buttonSets = GameStrip.BOARD_BUTTONS.map { set -> set.map { it.label } },
            )
        }
    }
}
