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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.yarosz.chess.board.MoveInput
import com.yarosz.chess.board.PositionView
import com.yarosz.chess.board.Review
import com.yarosz.chess.board.ActionRow
import com.yarosz.chess.board.BoardTopBar
import com.yarosz.chess.board.StripButton
import com.yarosz.chess.board.Touch
import com.yarosz.chess.board.Wheel
import com.yarosz.chess.correspondence.CorrespondenceGame
import com.yarosz.chess.correspondence.Delivery
import com.yarosz.chess.rules.Game
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How a Play a friend screen hands back: to the Play a friend page, and maybe straight into [open]'s board. */
data class FriendExit(val open: String? = null)

/**
 * A Correspondence Game's board (W4, W10): the user's Side at the bottom, never flipped (G3). A Move
 * is chosen on the board and sent only on Send (F11); the strip says what the Game waits for. The
 * board takes a Move only on the user's Move with nothing chosen, waiting to send or in flight.
 * There is no Game Hint and no Takeback: the engine never touches a Correspondence Game (W10).
 */
class FriendGameViewModel(private val friends: FriendOwner, val gameId: String) : WheelViewModel<FriendExit?>() {
    var review by mutableStateOf(Review())
        private set

    private var input by mutableStateOf<MoveInput?>(null)

    /** Contradiction 4: the screen stays on for 5 minutes after the last touch or wheel event. */
    var awake by mutableStateOf(true)
        private set
    private var idle: Job? = null

    fun touched() {
        awake = true
        idle?.cancel()
        idle = viewModelScope.launch {
            delay(PuzzleOwner.AWAKE_MS)
            awake = false
        }
    }

    /** The input for the user's Move, or null while the board is locked. */
    fun input(game: CorrespondenceGame, state: FriendState): MoveInput? {
        val position = game.log?.game?.position ?: return null
        if (!game.yourMove || gameId in state.chosen || gameId in state.sending || review.active) return null
        val current = input
        if (current != null && current.position == position) return current
        return MoveInput(position, setOf(game.seat.side))
    }

    fun touch(touch: Touch, game: CorrespondenceGame, state: FriendState) {
        touched()
        if (review.active) {
            if (touch is Touch.Tap) review = Review()
            return
        }
        val step = input(game, state)?.touch(touch) ?: return
        input = step.input
        step.move?.let { friends.choose(gameId, it) }
    }

    fun leaveReview() {
        touched()
        review = Review()
    }

    override fun onWheel(key: Wheel): Boolean {
        val log = friends.state.value.game(gameId)?.log ?: return false
        val next = review.wheel(key, log.game.ply) ?: return false
        touched()
        review = next
        return true
    }

    /** Whether the board is on screen, the Tool in front: only then does [poll] read. */
    var shown by mutableStateOf(false)
        private set

    /** W7: a Correspondence Game coming on screen syncs, and opens its live socket (G3). */
    override fun onScreenShow(screen: SimpleLightScreen<FriendExit?>) {
        shown = true
        touched()
        friends.watch(gameId)
        friends.sync()
    }

    /** Another screen over the board (its Menu, say): the socket stays a few seconds (U1). */
    override fun onScreenHide(screen: SimpleLightScreen<FriendExit?>) {
        shown = false
        friends.unwatch(gameId, now = false)
    }

    /** W7: the socket closes on pause. */
    override fun onAppPause() {
        shown = false
        friends.unwatch(gameId, now = true)
    }

    /**
     * Y12's fallback while the live socket is down (U3): while the board is on screen, the screen awake
     * (contradiction 4) and the Game waiting on the opponent, it syncs once a minute, so a reply shows
     * without leaving it.
     */
    suspend fun poll() {
        while (true) {
            delay(POLL_MS)
            val state = friends.state.value
            if (shown && awake && state.linked != gameId && state.game(gameId)?.waitingOnOpponent == true) friends.sync()
        }
    }

    companion object {
        const val POLL_MS = 60_000L
    }
}

class FriendGameScreen(
    sealedActivity: SealedLightActivity,
    private val gameId: String,
) : LightScreen<FriendExit?, FriendGameViewModel>(sealedActivity) {

    override val viewModelClass: Class<FriendGameViewModel>
        get() = FriendGameViewModel::class.java

    private val friends: FriendOwner by lazy { checkNotNull(FriendOwner.of(lightContext)) { "Play a friend is off" } }

    /** For the Piece Set only: it is kept in `puzzles.json` and every board draws it (P2, M1). */
    private val owner: PuzzleOwner by lazy { PuzzleOwner.of(lightContext.filesDir, lightContext::readAsset) }

    override fun createViewModel() = FriendGameViewModel(friends, gameId)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by friends.state.collectAsState()
        val vm = viewModel
        val now by produceState(friends.serverNow()) {
            while (true) {
                delay(TICK_MS)
                value = friends.serverNow()
            }
        }
        val game = state.game(gameId)
        LaunchedEffect(game == null) { if (game == null) goBack(FriendExit()) }
        LaunchedEffect(Unit) { vm.poll() }
        LightTheme(colors = themeColors) {
            Box(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                AndroidView(factory = { View(it) }, modifier = Modifier.size(0.dp), update = { it.keepScreenOn = vm.awake })
                if (game != null) Board(game, state, now)
            }
        }
    }

    @Composable
    private fun Board(game: CorrespondenceGame, state: FriendState, now: Long) {
        val vm = viewModel
        // As in game mode, the board waits for the Piece Set (read before any Band, M4, M7).
        val pieceSet = owner.pieceSet.collectAsState().value ?: return
        val played = game.log?.game ?: Game.of()
        val review = vm.review.takeIf { it.ply == null || it.ply < played.ply } ?: Review()
        val chosen = state.chosen[gameId].takeIf { review.ply == null }
        val shownGame = chosen?.let { runCatching { played + it }.getOrNull() }
        val positions = played.positions
        val shown = review.ply ?: positions.lastIndex
        val strip = FriendStrip.of(game, now, chosen, gameId in state.sending, state.notices[gameId]?.text, review.ply, live = state.live == gameId)
        val buttons = strip.buttons.map { button ->
            StripButton(button.label, button.description) {
                vm.touched()
                when (button) {
                    FriendButton.SEND -> friends.send(gameId)
                    FriendButton.UNDO -> friends.undo(gameId)
                    FriendButton.RETRY -> friends.retry(gameId)
                    FriendButton.ACCEPT_DRAW -> friends.acceptDraw(gameId)
                    FriendButton.DECLINE_DRAW -> friends.declineDraw(gameId)
                    FriendButton.CLAIM -> friends.claim(gameId)
                    FriendButton.REMATCH -> friends.rematch(gameId)
                    FriendButton.ACCEPT_REMATCH -> friends.acceptRematch(gameId) { delivery ->
                        // The rematch has started: its board replaces this one.
                        val next = (delivery as? Delivery.Done)?.game
                        if (next != null && next.gameId != gameId) goBack(FriendExit(next.gameId))
                    }
                    FriendButton.DECLINE_REMATCH -> friends.declineRematch(gameId)
                    FriendButton.CANCEL -> {}
                    FriendButton.LATEST -> vm.leaveReview()
                }
            }
        }
        val menu = StripButton(UiCopy.MENU_DESCRIPTION) {
            vm.touched()
            navigateTo({ FriendScreen(it, FriendPage.MENU, gameId, overBoard = true) }) { exit -> if (exit != null) goBack(exit) }
        }
        // E1, E2: the top bar (the arrow, the status, the Menu mark), the board, then the action row.
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            BoardTopBar(strip.status, StripButton(UiCopy.BACK_DESCRIPTION) { goBack() }, menu.takeIf { strip.menu })
            PositionView(
                position = shownGame?.position ?: positions[shown],
                lastMove = chosen ?: played.moves.getOrNull(shown - 1),
                input = if (review.ply == null) vm.input(game, state) else null,
                bottom = game.seat.side,
                onTouch = { vm.touch(it, game, state) },
                description = UiCopy.BOARD_DESCRIPTION,
                pieceSet = pieceSet,
            )
            // A chosen Move not yet sent counts as shown (F11): its capture is in the row at once.
            val captured = rememberCapturedRow(shownGame ?: played, shownGame?.ply ?: shown, game.seat.side, pieceSet)
            ActionRow(buttons, Modifier.weight(1f), captured = captured)
        }
    }

    private companion object {
        /** Time Left is shown in whole minutes at the finest, so a 30 s tick is enough. */
        const val TICK_MS = 30_000L
    }
}
