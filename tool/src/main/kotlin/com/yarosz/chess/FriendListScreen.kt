package com.yarosz.chess

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.yarosz.chess.board.Strip
import com.yarosz.chess.board.StripButton
import com.yarosz.chess.board.Wheel
import com.yarosz.chess.correspondence.Refusal
import com.yarosz.chess.correspondence.Stage
import kotlinx.coroutines.delay

/** The Play a friend list's view model: it syncs when it shows (W7) and leaves the wheel with LightOS. */
class FriendListViewModel(private val friends: FriendOwner) : WheelViewModel<Unit>() {
    override fun onWheel(key: Wheel): Boolean = false

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) = friends.sync()

    /** A board just left keeps its socket 10 s (U1); a pause here closes it now. */
    override fun onAppPause() = friends.pause()
}

/**
 * The Play a friend list (W6, N7): a place one step from Home, with LightOS's top bar (the back arrow
 * to Home and the title) and no Menu. A row opens its Game's board or invite, or for a Game the Relay
 * deleted its Menu (W13); the strip has New game and Enter code.
 */
class FriendListScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, FriendListViewModel>(sealedActivity) {

    override val viewModelClass: Class<FriendListViewModel>
        get() = FriendListViewModel::class.java

    private val friends: FriendOwner by lazy { checkNotNull(FriendOwner.of(lightContext)) { "Play a friend is off" } }

    override fun createViewModel() = FriendListViewModel(friends)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by friends.state.collectAsState()
        val now by produceState(friends.serverNow()) {
            while (true) {
                delay(30_000L)
                value = friends.serverNow()
            }
        }
        LightTheme(colors = themeColors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                FriendList(
                    state = state,
                    now = now,
                    onBack = { goBack() },
                    onRow = { row -> open(row, state) },
                    onNew = { navigateTo({ FriendScreen(it, FriendPage.NEW) }, ::openNext) },
                    onEnterCode = { navigateTo({ FriendScreen(it, FriendPage.ENTER_CODE) }, ::openNext) },
                )
            }
        }
    }

    private fun open(row: FriendRow, state: FriendState) {
        val seat = row.seat
        val gameId = row.gameId
        when {
            seat != null -> {
                // A Seat the Relay hasn't confirmed: a tap sends it again (W9).
                val code = seat.code
                if (code != null) friends.redeem(code) {} else seat.rematchOf?.let { friends.acceptRematch(it) }
            }
            gameId == null -> {}
            // A Game the Relay deleted (W13): nothing more can happen to it, so its Menu, with Forget game.
            row.menu -> navigateTo({ FriendScreen(it, FriendPage.MENU, gameId) }, ::openNext)
            state.game(gameId)?.stage == Stage.WAITING -> navigateTo({ FriendScreen(it, FriendPage.INVITE, gameId) }, ::openNext)
            else -> openGame(gameId)
        }
    }

    private fun openGame(gameId: String) = navigateTo({ FriendGameScreen(it, gameId) }, ::openNext)

    /** A Play a friend screen closed, maybe into a Game's board (a code just joined, a rematch accepted). */
    private fun openNext(exit: FriendExit?) {
        exit?.open?.let(::openGame)
    }
}

/**
 * The Play a friend list's content (W6, N7): the top bar, the Games in E7's order, then New game and
 * Enter code, lightened with "Finish a game first" at the cap of five.
 */
@Composable
fun FriendList(state: FriendState, now: Long, onBack: () -> Unit, onRow: (FriendRow) -> Unit, onNew: () -> Unit, onEnterCode: () -> Unit) {
    val rows = FriendRows.of(state.games, state.seats, now)
    Column(Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack, contentDescription = UiCopy.BACK_DESCRIPTION),
            center = LightTopBarCenter.Text(UiCopy.PLAY_FRIEND),
        )
        LightScrollView(Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty()) MenuLine(UiCopy.NO_FRIEND_GAMES, lighten = true)
            for (row in rows) MenuRow(row.text) { onRow(row) }
        }
        if (state.full) MenuLine(UiCopy.refusal(Refusal.CAP_REACHED), lighten = true)
        Strip(
            "",
            listOf(
                StripButton(UiCopy.NEW_GAME, UiCopy.NEW_GAME_DESCRIPTION, enabled = !state.full, onClick = onNew),
                StripButton(UiCopy.ENTER_CODE, UiCopy.ENTER_CODE, enabled = !state.full, onClick = onEnterCode),
            ),
        )
    }
}
