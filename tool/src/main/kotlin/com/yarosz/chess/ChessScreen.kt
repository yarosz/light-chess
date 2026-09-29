package com.yarosz.chess

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import com.yarosz.chess.board.MoveInput
import com.yarosz.chess.board.POSITION_VIEW_SIZE
import com.yarosz.chess.board.PositionView
import com.yarosz.chess.board.Review
import com.yarosz.chess.board.Strip
import com.yarosz.chess.board.StripButton
import com.yarosz.chess.board.Touch
import com.yarosz.chess.board.Wheel
import com.yarosz.chess.games.GameState
import com.yarosz.chess.games.Phase
import com.yarosz.chess.puzzles.Attempt
import com.yarosz.chess.puzzles.PuzzleState
import com.yarosz.chess.puzzles.Stage

/**
 * The Tool's first screen, in the mode last used (D6): the puzzle screen over the process's
 * [PuzzleOwner], or the game screen over its [GameOwner]. This holds only the touches in progress
 * ([MoveInput]) and Review; everything that outlives a touch lives in the owners.
 */
class ChessViewModel(
    private val owner: PuzzleOwner,
    private val game: GameOwner,
    private val modes: ModeOwner,
) : WheelViewModel() {
    private var input by mutableStateOf<MoveInput?>(null)
    private var review by mutableStateOf(Review())

    /** The Puzzle [review] belongs to: a new Puzzle starts at its latest Position. */
    private var reviewFor by mutableStateOf<String?>(null)

    /** The input for [attempt]'s latest Position, fresh whenever that Position changes. */
    fun input(attempt: Attempt): MoveInput? {
        if (!attempt.userToMove) return null
        val current = input
        if (current != null && current.position == attempt.position) return current
        return MoveInput(attempt.position, setOf(attempt.puzzle.solver))
    }

    /** Review over [attempt]'s Moves, or none once the Ply it shows is the latest. */
    fun review(attempt: Attempt): Review {
        val ply = review.ply ?: return review
        return if (reviewFor != attempt.puzzle.id || ply >= attempt.positions.lastIndex) Review() else review
    }

    fun touch(touch: Touch) {
        owner.touched()
        val attempt = owner.session.value?.attempt ?: return
        if (review(attempt).active) {
            // A tap on the board leaves Review for the latest Position (R1.9).
            if (touch is Touch.Tap) review = Review()
            return
        }
        val step = input(attempt)?.touch(touch) ?: return
        input = step.input
        step.move?.let(owner::play)
    }

    fun leaveReview() {
        owner.touched()
        review = Review()
    }

    // --- The game screen ---

    private var gameInput by mutableStateOf<MoveInput?>(null)
    private var gameReview by mutableStateOf(Review())

    /** The Game [gameReview] belongs to, by its seed: a new Game starts at its latest Position. */
    private var gameReviewFor by mutableStateOf<Long?>(null)

    /** The input for the user's Move in [state]; null while the computer thinks or the Game is over (contradiction 3). */
    fun gameInput(state: GameState): MoveInput? {
        val record = state.record ?: return null
        if (state.phase != Phase.USER) return null
        val position = record.game.position
        val current = gameInput
        if (current != null && current.position == position) return current
        return MoveInput(position, setOf(record.userSide))
    }

    fun gameReview(state: GameState): Review {
        val ply = gameReview.ply ?: return gameReview
        val record = state.record ?: return Review()
        return if (gameReviewFor != record.seed || ply >= record.game.ply) Review() else gameReview
    }

    fun gameTouch(touch: Touch) {
        game.touched()
        val state = game.state.value ?: return
        if (gameReview(state).active) {
            if (touch is Touch.Tap) gameReview = Review()
            return
        }
        val step = gameInput(state)?.touch(touch) ?: return
        gameInput = step.input
        step.move?.let(game::play)
    }

    fun leaveGameReview() {
        game.touched()
        gameReview = Review()
    }

    private fun gameKey(key: Wheel): Boolean {
        val state = game.state.value ?: return false
        val record = state.record ?: return false
        val next = gameReview(state).wheel(key, record.game.ply) ?: return false
        game.touched()
        gameReview = next
        gameReviewFor = record.seed
        return true
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        if (modes.mode.value == Mode.GAME) game.resume()
    }

    override fun onWheel(key: Wheel): Boolean {
        if (modes.mode.value == Mode.GAME && game.state.value?.record != null) return gameKey(key)
        val session = owner.session.value ?: return false
        val attempt = session.attempt ?: return false
        if (session.needsSeed || attempt.stage == Stage.HOLD) return false
        val next = review(attempt).wheel(key, attempt.positions.lastIndex) ?: return false
        owner.touched()
        review = next
        reviewFor = attempt.puzzle.id
        return true
    }

    override fun onAppPause() {
        owner.flush()
        game.pause()
    }
}

@InitialScreen
class ChessScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, ChessViewModel>(sealedActivity) {

    override val viewModelClass: Class<ChessViewModel>
        get() = ChessViewModel::class.java

    private val owner: PuzzleOwner by lazy { PuzzleOwner.of(lightContext.filesDir, lightContext::readAsset) }
    private val game: GameOwner by lazy { GameOwner.of(lightContext.filesDir, lightContext::readAsset) }
    private val modes: ModeOwner by lazy { ModeOwner.of(lightContext.filesDir) }

    override fun createViewModel() = ChessViewModel(owner, game, modes)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val session by owner.session.collectAsState()
        val gameState by game.state.collectAsState()
        val mode by modes.mode.collectAsState()
        val puzzleAwake by owner.awake.collectAsState()
        val gameAwake by game.awake.collectAsState()
        // Game mode shows the Game on screen; with none (a lost file), the puzzles show instead.
        val gameShown = gameState?.takeIf { mode == Mode.GAME && it.record != null }
        val awake = if (gameShown != null) gameAwake else puzzleAwake
        LightTheme(colors = themeColors) {
            Box(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                // D3: an attached View's keepScreenOn sets the window's flag (PLATFORM.md).
                AndroidView(factory = { View(it) }, modifier = Modifier.size(0.dp), update = { it.keepScreenOn = awake })
                if (mode == Mode.GAME && gameState == null) return@Box
                if (gameShown != null) {
                    GameView(gameShown)
                    return@Box
                }
                val s = session ?: return@Box
                when {
                    s.needsSeed -> SeedView()
                    s.attempt == null -> Finished()
                    else -> PuzzleView(s, s.attempt!!)
                }
            }
        }
    }

    @Composable
    private fun PuzzleView(session: PuzzleState, attempt: Attempt) {
        val vm = viewModel
        val motion by owner.motion.collectAsState()
        val review = vm.review(attempt)
        val positions = attempt.positions
        val latest = positions.lastIndex
        val shown = review.ply ?: latest
        val first = session.rated && session.firstPuzzle && !attempt.userHasMoved
        val status = when {
            review.ply != null -> UiCopy.review(shown, latest)
            attempt.stage == Stage.DONE -> UiCopy.result(attempt.state, session.rated, session.currentDelta, attempt.solutionShown)
            attempt.stage == Stage.SOLUTION -> UiCopy.SOLUTION_PLAYING
            attempt.stage == Stage.REPLY -> UiCopy.CORRECT
            attempt.justWrong -> UiCopy.TRY_AGAIN
            first -> UiCopy.FIRST_PUZZLE
            else -> UiCopy.toMove(attempt.puzzle.solver)
        }
        val menu = StripButton(UiCopy.MENU, UiCopy.MENU_DESCRIPTION) { owner.touched(); navigateTo({ MenuScreen(it) }) }
        val buttons = buildList {
            if (review.ply != null) add(StripButton(UiCopy.LATEST, UiCopy.LATEST_DESCRIPTION, vm::leaveReview))
            when {
                attempt.stage == Stage.DONE -> add(StripButton(UiCopy.NEXT, UiCopy.NEXT_DESCRIPTION, owner::next))
                attempt.stage == Stage.SOLUTION || first -> {}
                review.ply == null -> {
                    add(StripButton(UiCopy.HINT, UiCopy.HINT_DESCRIPTION, owner::hint))
                    add(StripButton(UiCopy.SOLUTION, UiCopy.SOLUTION_DESCRIPTION, owner::showSolution))
                }
            }
            add(menu)
        }
        Column(
            Modifier.fillMaxSize().padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            PositionView(
                position = positions[shown],
                lastMove = attempt.moves.getOrNull(shown - 1),
                input = if (review.ply == null) vm.input(attempt) else null,
                bottom = attempt.puzzle.solver,
                onTouch = vm::touch,
                description = UiCopy.BOARD_DESCRIPTION,
                hint = attempt.hintSquare.takeIf { review.ply == null },
                motion = motion.takeIf { review.ply == null },
            )
            Strip(status, buttons, Modifier.width(POSITION_VIEW_SIZE))
        }
        LaunchedEffect(Unit) {
            withFrameNanos {}
            withFrameNanos {}
            owner.firstPuzzleDrawn(attempt.puzzle.id)
        }
    }

    /** The game screen (v2 PR 4): the board with the user's Side at the bottom, and the strip by context. */
    @Composable
    private fun GameView(state: GameState) {
        val vm = viewModel
        val record = state.record ?: return
        val motion by game.motion.collectAsState()
        val review = vm.gameReview(state)
        val positions = record.game.positions
        val shown = review.ply ?: positions.lastIndex
        val strip = GameStrip.of(state, review.ply)
        val buttons = strip.buttons.map { button ->
            StripButton(button.label, button.description) {
                when (button) {
                    GameButton.HINT -> game.hint()
                    GameButton.MOVE_NOW -> game.moveNow()
                    GameButton.NEXT -> { game.touched(); navigateTo({ MenuScreen(it, MenuPage.NEW_GAME) }) }
                    GameButton.LATEST -> vm.leaveGameReview()
                    GameButton.MENU -> { game.touched(); navigateTo({ MenuScreen(it) }) }
                    GameButton.BACK -> {}
                }
            }
        }
        val live = review.ply == null
        Column(
            Modifier.fillMaxSize().padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            PositionView(
                position = positions[shown],
                lastMove = record.game.moves.getOrNull(shown - 1),
                input = if (live) vm.gameInput(state) else null,
                bottom = state.bottom,
                onTouch = vm::gameTouch,
                description = UiCopy.BOARD_DESCRIPTION,
                hint = state.hint?.from.takeIf { live },
                hintTarget = state.hint?.to.takeIf { live },
                motion = motion.takeIf { live },
            )
            Strip(strip.status, buttons, Modifier.width(POSITION_VIEW_SIZE))
        }
    }

    /** First launch only, and after Reset rating (D4, F5, F6): four plain rows and Skip. */
    @Composable
    private fun SeedView() {
        LaunchedEffect(Unit) { owner.seedScreenShown() }
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp)) {
            LightText(text = UiCopy.SEED_QUESTION, variant = LightTextVariant.Copy, modifier = Modifier.padding(bottom = 16.dp))
            for ((label, rating) in UiCopy.SEED_ROWS) SeedRow(label) { owner.seed(rating) }
            SeedRow(UiCopy.SEED_SKIP, lighten = true) { owner.seed(UiCopy.SEED_SKIP_RATING) }
        }
    }

    @Composable
    private fun SeedRow(label: String, lighten: Boolean = false, onClick: () -> Unit) {
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            lighten = lighten,
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(onClickLabel = label, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label }
                .padding(vertical = 12.dp),
        )
    }

    /** Every Puzzle in the Pack is finished: only the Menu is left. */
    @Composable
    private fun Finished() {
        Column(Modifier.fillMaxSize().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Strip(
                UiCopy.PACK_FINISHED,
                listOf(StripButton(UiCopy.MENU, UiCopy.MENU_DESCRIPTION) { navigateTo({ MenuScreen(it) }) }),
                Modifier.width(POSITION_VIEW_SIZE),
            )
        }
    }
}
