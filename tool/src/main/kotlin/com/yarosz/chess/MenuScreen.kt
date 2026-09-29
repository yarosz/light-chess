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
import com.yarosz.chess.board.PieceSet
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

/**
 * The Menu's pages. The Menu is a visible button because back leaves the Tool (PLATFORM.md). Each page
 * is its own [MenuScreen] on the SDK's back stack: system Back never reaches a screen (LightActivity
 * pops the stack itself), so a sub-page that were only state inside one screen would skip the Menu.
 */
enum class MenuPage(val title: String, val scrolls: Boolean) {
    MENU(UiCopy.MENU_TITLE, false),
    RATING(UiCopy.PLAYER_RATING, true),
    MISSED(UiCopy.MISSED, true),
    ABOUT(UiCopy.ABOUT, true),
    NEW_GAME(UiCopy.NEW_GAME, true),
    GAMES(UiCopy.GAMES, true),
    MOVES(UiCopy.MOVES, true),
}

/** The second taps, the new-game page's choices, and the wheel's scroll steps (F3) on one page. */
class MenuViewModel(
    private val owner: PuzzleOwner,
    private val game: GameOwner,
    private val modes: ModeOwner,
    private val page: MenuPage,
) : WheelViewModel() {
    /** "Reset rating" was tapped once; the next tap resets (F5). */
    var confirmingReset by mutableStateOf(false)
        private set

    /** The new-game page's choices, from the last Game's (remembered in `games.json`). */
    var choices by mutableStateOf(game.state.value?.data?.choices ?: GameChoices())
        private set

    private val steps = MutableSharedFlow<Int>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** One row per wheel detent: -1 up, +1 down. */
    val scroll: SharedFlow<Int> = steps

    init {
        // The Missed page reads its Puzzles ahead, so a tap on a row reads no file on the main thread.
        if (page == MenuPage.MISSED) owner.prefetchMissed()
    }

    /** Before another page opens over this one: a pending second tap is dropped. */
    fun leaving() {
        owner.touched()
        game.touched()
        game.cancelConfirm()
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

    /**
     * "Play the computer" from the puzzle Menu: true when a Game is in progress to return to (the
     * mode is then the game's); false when the new-game page should open instead (R4.11).
     */
    fun playComputer(): Boolean {
        val inProgress = game.state.value?.inProgress == true
        if (inProgress) modes.set(Mode.GAME)
        return inProgress
    }

    fun puzzles() {
        game.pause()
        modes.set(Mode.PUZZLES)
    }

    /** Level 8's Think Time row cycles 3, 10, 30 s. */
    fun nextThinkTime(current: Int) {
        val all = ThinkTime.entries.map { (it.ms / 1000).toInt() }
        game.setThinkTime(all[(all.indexOf(current) + 1).mod(all.size)])
    }

    private val scrolls: Boolean get() = page.scrolls || (page == MenuPage.MENU && modes.mode.value == Mode.GAME)

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

/**
 * One Menu page. Back, the arrow or the system's, goes one page up: a sub-page to the Menu, the Menu
 * to the board. A sub-page that has done its job (a reset, a Missed replay, a Start) leaves the Menu
 * as well: it goes back with a result, and the Menu, handed that result, goes back too. System Back
 * never carries one, so it always stops at the Menu.
 */
class MenuScreen(
    sealedActivity: SealedLightActivity,
    private val page: MenuPage = MenuPage.MENU,
) : LightScreen<Unit, MenuViewModel>(sealedActivity) {

    override val viewModelClass: Class<MenuViewModel>
        get() = MenuViewModel::class.java

    private val owner: PuzzleOwner by lazy { PuzzleOwner.of(lightContext.filesDir, lightContext::readAsset) }
    private val game: GameOwner by lazy { GameOwner.of(lightContext.filesDir, lightContext::readAsset) }
    private val modes: ModeOwner by lazy { ModeOwner.of(lightContext.filesDir) }

    override fun createViewModel() = MenuViewModel(owner, game, modes, page)

    private fun open(next: MenuPage) {
        viewModel.leaving()
        navigateTo({ MenuScreen(it, next) }) { goBack() }
    }

    /** Back to the board from a sub-page, past the Menu. */
    private fun leaveMenu() = goBack(Unit)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val session by owner.session.collectAsState()
        val gameState by game.state.collectAsState()
        val history by game.history.collectAsState()
        val mode by modes.mode.collectAsState()
        val gameAwake by game.awake.collectAsState()
        val vm = viewModel
        val data = session?.data
        val pieceSet by owner.pieceSet.collectAsState()
        LightTheme(colors = themeColors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                // The computer may be thinking behind the Menu: keep the screen on for it too (contradiction 4).
                AndroidView(factory = { View(it) }, modifier = Modifier.size(0.dp), update = { it.keepScreenOn = mode == Mode.GAME && gameAwake })
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(page.title),
                )
                val scrollState = rememberScrollState()
                val rowPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
                LaunchedEffect(Unit) {
                    vm.scroll.collect { scrollState.animateScrollBy(it * rowPx) }
                }
                LightScrollView(Modifier.weight(1f).fillMaxWidth(), scrollState = scrollState) {
                    when (page) {
                        MenuPage.MENU -> {
                            val shown = gameState?.takeIf { mode == Mode.GAME && it.record != null }
                            if (shown != null) GameMenu(shown, pieceSet) else if (data != null) {
                                Row(UiCopy.ratingRow(data.player.text)) { open(MenuPage.RATING) }
                                Row(UiCopy.missedCount(data.missed.size)) { open(MenuPage.MISSED) }
                                Row(UiCopy.PLAY_COMPUTER) { if (vm.playComputer()) goBack() else open(MenuPage.NEW_GAME) }
                                // P2, M1: a tap moves to the next Piece Set, here on the Menu; every board draws it.
                                Row(UiCopy.piecesRow(data.pieceSet)) { owner.nextPieceSet() }
                                Row(UiCopy.ABOUT) { open(MenuPage.ABOUT) }
                                // A9 with D7: the Puzzle on screen, by its Lichess id, as text (v1 smoke fixes).
                                session?.attempt?.let { Line(UiCopy.puzzleRow(it.puzzle.id), lighten = true) }
                            }
                        }
                        MenuPage.RATING -> if (data != null) {
                            Line(data.player.text)
                            Row(if (vm.confirmingReset) UiCopy.RESET_CONFIRM else UiCopy.RESET_RATING) {
                                if (vm.tapReset()) leaveMenu()
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
                                    leaveMenu()
                                }
                            }
                        }
                        MenuPage.ABOUT -> for ((i, paragraph) in UiCopy.about(owner.packDate, owner.notices).withIndex()) {
                            Line(paragraph, lighten = i > 0)
                        }
                        MenuPage.NEW_GAME -> NewGame(gameState)
                        MenuPage.GAMES -> {
                            if (history.isEmpty()) Line(UiCopy.NO_GAMES, lighten = true)
                            for (record in history) {
                                Row(UiCopy.gamesRow(record.date, record.level, record.game.result, record.userSide)) {
                                    game.touched()
                                    navigateTo({ GameReviewScreen(it, record) })
                                }
                            }
                        }
                        MenuPage.MOVES -> gameState?.record?.let { MoveList(it) }
                    }
                }
            }
        }
    }

    /** The Menu while a Game shows (B5, D10, contradiction 2): the Game's actions, then the pages. */
    @Composable
    private fun GameMenu(state: GameState, pieceSet: PieceSet?) {
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
        Row(UiCopy.MOVES) { open(MenuPage.MOVES) }
        if (record.level == 8) {
            val seconds = record.thinkTimeSeconds ?: state.data.choices.thinkTimeSeconds
            Row(UiCopy.thinkTimeRow(seconds)) { vm.nextThinkTime(seconds) }
        }
        Row(UiCopy.NEW_GAME) { open(MenuPage.NEW_GAME) }
        Row(UiCopy.GAMES) { open(MenuPage.GAMES) }
        Row(UiCopy.PUZZLES) {
            vm.puzzles()
            goBack()
        }
        // P2, M1: the one Piece Set, the puzzle Menu's row again, just above About.
        // It is read before the first Puzzle (M4), so the row is there as soon as the Menu is.
        if (pieceSet != null) Row(UiCopy.piecesRow(pieceSet)) { owner.nextPieceSet() }
        Row(UiCopy.ABOUT) { open(MenuPage.ABOUT) }
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
            if (vm.start()) leaveMenu()
        }
        if (state?.inProgress == true) Line(UiCopy.REPLACE_NOTE, lighten = true)
    }

    /** One line of tappable options; the chosen one in the content colour, the others lightened. */
    @Composable
    private fun Choices(labels: List<String>, chosen: Int, onChoose: (Int) -> Unit) {
        HorizontalRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((i, label) in labels.withIndex()) {
                LightText(
                    text = label,
                    variant = LightTextVariant.Copy,
                    lighten = i != chosen,
                    underline = i == chosen,
                    align = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = ROW_HEIGHT)
                        .lightClickable(onClickLabel = label, role = Role.Button) { onChoose(i) }
                        .semantics {
                            contentDescription = label
                            selected = i == chosen
                        }
                        .padding(vertical = 12.dp),
                )
            }
        }
    }

    /** The Moves page (F11): SAN in two columns, one row per Move number, scrolled by the wheel. */
    @Composable
    private fun MoveList(record: GameRecord) {
        val game = record.game
        if (game.moves.isEmpty()) {
            Line(UiCopy.NO_MOVES, lighten = true)
            return
        }
        val rows = ArrayList<Triple<Int, String, String>>()
        for ((ply, move) in game.moves.withIndex()) {
            val position = game.positions[ply]
            val san = position.san(move)
            if (position.sideToMove == Side.WHITE || rows.isEmpty()) {
                rows += Triple(position.fullmoveNumber, if (position.sideToMove == Side.WHITE) san else "…", if (position.sideToMove == Side.WHITE) "" else san)
            } else {
                val last = rows.removeAt(rows.lastIndex)
                rows += last.copy(third = san)
            }
        }
        for ((number, white, black) in rows) {
            HorizontalRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
                LightText(text = UiCopy.moveNumber(number), variant = LightTextVariant.Copy, lighten = true, modifier = Modifier.width(56.dp))
                LightText(text = white, variant = LightTextVariant.Copy, modifier = Modifier.width(120.dp))
                LightText(text = black, variant = LightTextVariant.Copy, modifier = Modifier.width(120.dp))
            }
        }
    }

    @Composable
    private fun Row(label: String, onClick: () -> Unit) {
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_HEIGHT)
                .lightClickable(onClickLabel = label, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label }
                .padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }

    @Composable
    private fun Line(text: String, lighten: Boolean = false) {
        LightText(
            text = text,
            variant = LightTextVariant.Copy,
            lighten = lighten,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }

    private companion object {
        /** One wheel detent scrolls one row (F3). */
        val ROW_HEIGHT = 48.dp
    }
}
