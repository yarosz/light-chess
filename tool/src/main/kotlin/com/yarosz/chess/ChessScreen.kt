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
import com.yarosz.chess.puzzles.Attempt
import com.yarosz.chess.puzzles.PuzzleState
import com.yarosz.chess.puzzles.Stage

/**
 * The puzzle screen's view state over the process's [PuzzleOwner]: the touches in progress
 * ([MoveInput]) and Review. Everything that outlives a touch lives in the owner.
 */
class ChessViewModel(private val owner: PuzzleOwner) : WheelViewModel() {
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

    override fun onWheel(key: Wheel): Boolean {
        val session = owner.session.value ?: return false
        val attempt = session.attempt ?: return false
        if (session.needsSeed || attempt.stage == Stage.HOLD) return false
        val next = review(attempt).wheel(key, attempt.positions.lastIndex) ?: return false
        owner.touched()
        review = next
        reviewFor = attempt.puzzle.id
        return true
    }

    override fun onAppPause() = owner.flush()
}

@InitialScreen
class ChessScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, ChessViewModel>(sealedActivity) {

    override val viewModelClass: Class<ChessViewModel>
        get() = ChessViewModel::class.java

    private val owner: PuzzleOwner by lazy { PuzzleOwner.of(lightContext.filesDir, lightContext::readAsset) }

    override fun createViewModel() = ChessViewModel(owner)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val session by owner.session.collectAsState()
        val awake by owner.awake.collectAsState()
        LightTheme(colors = themeColors) {
            Box(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                // D3: an attached View's keepScreenOn sets the window's flag (PLATFORM.md).
                AndroidView(factory = { View(it) }, modifier = Modifier.size(0.dp), update = { it.keepScreenOn = awake })
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
