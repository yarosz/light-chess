package com.yarosz.chess

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row as HorizontalRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.lightClickable
import com.yarosz.chess.board.Wheel
import com.yarosz.chess.engine.ThinkTime
import com.yarosz.chess.games.Confirm
import com.yarosz.chess.games.DrawResponse
import com.yarosz.chess.games.GameChoices
import com.yarosz.chess.games.GameRecord
import com.yarosz.chess.games.GameState
import com.yarosz.chess.games.Phase
import com.yarosz.chess.games.SideChoice
import com.yarosz.chess.rules.Side
import com.yarosz.chess.rules.san
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** The Menu's pages. The Menu is a visible button because back leaves the Tool (PLATFORM.md). */
enum class MenuPage(val title: String, val scrolls: Boolean) {
    MENU(UiCopy.MENU_TITLE, false),
    RATING(UiCopy.PLAYER_RATING, true),
    MISSED(UiCopy.MISSED, true),
    ABOUT(UiCopy.ABOUT, true),
    NEW_GAME(UiCopy.NEW_GAME, true),
    GAMES(UiCopy.GAMES, true),
    MOVES(UiCopy.MOVES, true),
}

/** Which page shows, the second taps, the new-game page's choices, and the wheel's scroll steps (F3). */
class MenuViewModel(
    private val owner: PuzzleOwner,
    private val game: GameOwner,
    private val modes: ModeOwner,
    /** The page the Menu opened on: back from it leaves the Menu. */
    val startPage: MenuPage,
) : WheelViewModel<Unit>() {
    var page by mutableStateOf(startPage)
        private set

    /** "Reset rating" was tapped once; the next tap resets (F5). */
    var confirmingReset by mutableStateOf(false)
        private set

    /** The new-game page's choices, from the last Game's (remembered in `games.json`). */
    var choices by mutableStateOf(game.state.value?.data?.choices ?: GameChoices())
        private set

    private val steps = MutableSharedFlow<Int>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** One row per wheel detent: -1 up, +1 down. */
    val scroll: SharedFlow<Int> = steps

    fun open(next: MenuPage) {
        owner.touched()
        game.touched()
        game.cancelConfirm()
        if (next == MenuPage.NEW_GAME) game.state.value?.data?.choices?.let { choices = it }
        page = next
        confirmingReset = false
    }

    fun tapReset(): Boolean {
        owner.touched()
        if (!confirmingReset) {
            confirmingReset = true
            return false
        }
        confirmingReset = false
        owner.resetRating()
        return true
    }

    fun choose(next: GameChoices) {
        game.touched()
        game.cancelConfirm()
        choices = next
    }

    /** Start (or its second tap while a Game is in progress, F7); true when the Game started. */
    fun start(): Boolean {
        val started = game.start(choices)
        if (started) modes.set(Mode.GAME)
        return started
    }

    /** "Play the computer" from the puzzle Menu: true when a Game is in progress to return to. */
    fun playComputer(): Boolean {
        val inProgress = game.state.value?.inProgress == true
        if (inProgress) modes.set(Mode.GAME) else open(MenuPage.NEW_GAME)
        return inProgress
    }

    fun puzzles() {
        game.pause()
        modes.set(Mode.PUZZLES)
    }

    /** "Play a friend": the Tool shows the Play a friend page (mode.txt "FRIEND", D6). The computer stops thinking meanwhile. */
    fun playFriend() {
        game.pause()
        modes.set(Mode.FRIEND)
    }

    /** Level 8's Think Time row cycles 3, 10, 30 s. */
    fun nextThinkTime(current: Int) {
        val all = ThinkTime.entries.map { (it.ms / 1000).toInt() }
        game.setThinkTime(all[(all.indexOf(current) + 1).mod(all.size)])
    }

    private val scrolls: Boolean get() = page.scrolls || (page == MenuPage.MENU && modes.mode.value != Mode.PUZZLES)

    /** F3: on a page that scrolls, the wheel moves one row per detent and takes every event, even at the ends. */
    override fun onWheel(key: Wheel): Boolean {
        if (!scrolls) return false
        owner.touched()
        game.touched()
        when (key) {
            Wheel.BACK -> steps.tryEmit(-1)
            Wheel.FORWARD -> steps.tryEmit(1)
            Wheel.CLICK -> {}
        }
        return true
    }

    /** The computer keeps thinking while the Menu shows (contradiction 3: the Game goes on). */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        if (modes.mode.value == Mode.GAME) game.resume()
    }

    override fun onAppPause() {
        owner.flush()
        game.pause()
    }
}

class MenuScreen(
    sealedActivity: SealedLightActivity,
    private val startPage: MenuPage = MenuPage.MENU,
) : LightScreen<Unit, MenuViewModel>(sealedActivity) {

    override val viewModelClass: Class<MenuViewModel>
        get() = MenuViewModel::class.java

    private val owner: PuzzleOwner by lazy { PuzzleOwner.of(lightContext.filesDir, lightContext::readAsset) }
    private val game: GameOwner by lazy { GameOwner.of(lightContext.filesDir, lightContext::readAsset) }
    private val modes: ModeOwner by lazy { ModeOwner.of(lightContext.filesDir) }
    private val friends: FriendOwner? get() = FriendOwner.of(lightContext)

    override fun createViewModel() = MenuViewModel(owner, game, modes, startPage)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val session by owner.session.collectAsState()
        val gameState by game.state.collectAsState()
        val history by game.history.collectAsState()
        val mode by modes.mode.collectAsState()
        val gameAwake by game.awake.collectAsState()
        // Play a friend exists only once the Relay URL is set (W8).
        val friendState = friends?.state?.collectAsState()?.value
        val vm = viewModel
        val page = vm.page
        val back = { if (page == vm.startPage) goBack() else vm.open(vm.startPage) }
        val data = session?.data
        LightTheme(colors = themeColors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                // The computer may be thinking behind the Menu: keep the screen on for it too (contradiction 4).
                AndroidView(factory = { View(it) }, modifier = Modifier.size(0.dp), update = { it.keepScreenOn = mode == Mode.GAME && gameAwake })
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = back),
                    center = LightTopBarCenter.Text(page.title),
                )
                val scrollState = rememberScrollState()
                val rowPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
                LaunchedEffect(page) {
                    scrollState.scrollTo(0)
                    vm.scroll.collect { scrollState.animateScrollBy(it * rowPx) }
                }
                LightScrollView(Modifier.weight(1f).fillMaxWidth(), scrollState = scrollState) {
                    when (page) {
                        MenuPage.MENU -> {
                            val shown = gameState?.takeIf { mode == Mode.GAME && it.record != null }
                            when {
                                mode == Mode.FRIEND && friendState != null -> {
                                    // The Play a friend page's Menu: the other modes and the shared pages (W6).
                                    Row(UiCopy.PUZZLES) {
                                        vm.puzzles()
                                        goBack()
                                    }
                                    Row(UiCopy.PLAY_COMPUTER) { if (vm.playComputer()) goBack() }
                                    Row(UiCopy.GAMES) { vm.open(MenuPage.GAMES) }
                                    Row(UiCopy.ABOUT) { vm.open(MenuPage.ABOUT) }
                                }
                                shown != null -> GameMenu(shown, friendState)
                                data != null -> {
                                    Row(UiCopy.ratingRow(data.player.text)) { vm.open(MenuPage.RATING) }
                                    Row(UiCopy.missedCount(data.missed.size)) { vm.open(MenuPage.MISSED) }
                                    Row(UiCopy.PLAY_COMPUTER) { if (vm.playComputer()) goBack() }
                                    friendState?.let { friends ->
                                        Row(UiCopy.playFriend(friends.yourMove)) {
                                            vm.playFriend()
                                            goBack()
                                        }
                                    }
                                    Row(UiCopy.ABOUT) { vm.open(MenuPage.ABOUT) }
                                }
                            }
                        }
                        MenuPage.RATING -> if (data != null) {
                            Line(data.player.text)
                            Row(if (vm.confirmingReset) UiCopy.RESET_CONFIRM else UiCopy.RESET_RATING) {
                                if (vm.tapReset()) goBack()
                            }
                            if (data.history.isEmpty()) Line(UiCopy.NO_HISTORY, lighten = true)
                            for (entry in data.history) {
                                Line(UiCopy.historyRow(entry.puzzleRating, entry.state, entry.delta, entry.solutionShown), lighten = true)
                            }
                        }
                        MenuPage.MISSED -> if (data != null) {
                            if (data.missed.isEmpty()) Line(UiCopy.NO_MISSED, lighten = true)
                            for (entry in data.missed) {
                                Row(UiCopy.missedRow(entry.puzzleRating, entry.state)) {
                                    owner.replayMissed(entry.id)
                                    goBack()
                                }
                            }
                        }
                        MenuPage.ABOUT -> for ((i, paragraph) in UiCopy.about(owner.packDate, owner.notices, friends = friendState != null).withIndex()) {
                            Line(paragraph, lighten = i > 0)
                        }
                        MenuPage.NEW_GAME -> NewGame(gameState)
                        MenuPage.GAMES -> {
                            // W5: Games against the computer and finished Correspondence Games, newest first.
                            val rows = FinishedGames.merge(history, friendState?.games.orEmpty())
                            if (rows.isEmpty()) Line(UiCopy.NO_GAMES, lighten = true)
                            for (row in rows) {
                                Row(row.text) {
                                    game.touched()
                                    navigateTo({ GameReviewScreen(it, row.record) })
                                }
                            }
                        }
                        MenuPage.MOVES -> gameState?.record?.let { MoveList(it.game) }
                    }
                }
            }
        }
    }

    /** The Menu while a Game shows (B5, D10, contradiction 2): the Game's actions, then the pages. */
    @Composable
    private fun GameMenu(state: GameState, friendState: FriendState?) {
        val vm = viewModel
        val record = state.record ?: return
        if (state.phase != Phase.OVER) {
            when {
                state.canOfferDraw -> Row(UiCopy.OFFER_DRAW) {
                    game.offerDraw()
                    if (game.state.value?.phase == Phase.OVER) goBack()
                }
                state.drawResponse == DrawResponse.DECLINED -> Line(UiCopy.DRAW_DECLINED)
                state.phase == Phase.COMPUTER -> Line(UiCopy.OFFER_DRAW_ON_YOUR_MOVE, lighten = true)
                else -> Line(UiCopy.offerDrawFrom(state.drawOfferFromMove), lighten = true)
            }
            Row(if (state.confirming == Confirm.RESIGN) UiCopy.RESIGN_CONFIRM else UiCopy.RESIGN) {
                if (game.resign()) goBack()
            }
            if (state.canTakeBack) Row(UiCopy.TAKEBACK) {
                game.takeback()
                goBack()
            }
        }
        Row(UiCopy.FLIP_BOARD) {
            game.flip()
            goBack()
        }
        Row(UiCopy.MOVES) { vm.open(MenuPage.MOVES) }
        if (record.level == 8) {
            val seconds = record.thinkTimeSeconds ?: state.data.choices.thinkTimeSeconds
            Row(UiCopy.thinkTimeRow(seconds)) { vm.nextThinkTime(seconds) }
        }
        Row(UiCopy.NEW_GAME) { vm.open(MenuPage.NEW_GAME) }
        Row(UiCopy.GAMES) { vm.open(MenuPage.GAMES) }
        Row(UiCopy.PUZZLES) {
            vm.puzzles()
            goBack()
        }
        friendState?.let { friends ->
            Row(UiCopy.playFriend(friends.yourMove)) {
                vm.playFriend()
                goBack()
            }
        }
        Row(UiCopy.ABOUT) { vm.open(MenuPage.ABOUT) }
    }

    /** The new-game page: Level 1-8, the Side to play, Think Time at Level 8, and Start (F7, F11). */
    @Composable
    private fun NewGame(state: GameState?) {
        val vm = viewModel
        val choices = vm.choices
        Line(UiCopy.LEVEL)
        Choices((1..8).map { "$it" }, choices.level - 1) { vm.choose(choices.copy(level = it + 1)) }
        Line(UiCopy.PLAY_AS)
        Choices(listOf(UiCopy.WHITE, UiCopy.BLACK, UiCopy.RANDOM), choices.side.ordinal) {
            vm.choose(choices.copy(side = SideChoice.entries[it]))
        }
        if (choices.level == 8) {
            val all = ThinkTime.entries.map { (it.ms / 1000).toInt() }
            Line(UiCopy.THINK_TIME)
            Choices(all.map(UiCopy::seconds), all.indexOf(choices.thinkTimeSeconds)) {
                vm.choose(choices.copy(thinkTimeSeconds = all[it]))
            }
        }
        val replacing = state?.confirming == Confirm.REPLACE
        Row(if (replacing) UiCopy.REPLACE_CONFIRM else UiCopy.START) {
            if (vm.start()) goBack()
        }
        if (state?.inProgress == true) Line(UiCopy.REPLACE_NOTE, lighten = true)
    }

    @Composable
    private fun Choices(labels: List<String>, chosen: Int, onChoose: (Int) -> Unit) = MenuChoices(labels, chosen, onChoose)

    @Composable
    private fun Row(label: String, onClick: () -> Unit) = MenuRow(label, onClick = onClick)

    @Composable
    private fun Line(text: String, lighten: Boolean = false) = MenuLine(text, lighten)

    private companion object {
        val ROW_HEIGHT = Rows.HEIGHT
    }
}
