package com.yarosz.chess

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
import com.yarosz.chess.board.PieceSet
import com.yarosz.chess.board.Wheel
import com.yarosz.chess.engine.ThinkTime
import com.yarosz.chess.games.Confirm
import com.yarosz.chess.games.GameChoices
import com.yarosz.chess.games.GameState
import com.yarosz.chess.games.Phase
import com.yarosz.chess.games.SideChoice
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The pages opened from Home (N1, N11), from the Puzzles page (N12) and from a board's Menu mark (N5,
 * N17). Each page is its own [MenuScreen] on the SDK's back stack: system Back never reaches a screen
 * (LightActivity pops the stack itself), so a sub-page that were only state inside one screen would
 * skip a level (S3, N6). Whether a page sits over the computer's board depends on who opened it, not
 * on the page: New game opens from Home and from the board (see [MenuScreen]'s overGame). [scrolls]:
 * the page can be longer than the screen, so it takes the wheel (F3); one that always fits leaves it
 * with LightOS (N20).
 */
enum class MenuPage(val title: String, val scrolls: Boolean) {
    /** The computer's Menu: this board's actions, then Pieces (N5, N17). */
    MENU(UiCopy.MENU_TITLE, true),

    /** The Puzzle board's Menu: Pieces and the Puzzle's id (N17, N18). */
    PUZZLE_MENU(UiCopy.MENU_TITLE, false),

    /** A replayed Game's Menu: Pieces (N17). */
    GAME_REVIEW_MENU(UiCopy.MENU_TITLE, false),

    /** The Puzzles page (N12): a place under Home. */
    PUZZLES(UiCopy.PUZZLES, false),
    RATING(UiCopy.PLAYER_RATING, false),
    MISSED(UiCopy.MISSED, true),
    PAST_PUZZLES(UiCopy.PAST_PUZZLES, true),
    ABOUT(UiCopy.ABOUT, true),
    NEW_GAME(UiCopy.NEW_GAME, true),
    GAMES(UiCopy.GAMES, true),
    MOVES(UiCopy.MOVES, true),
}

/**
 * The second taps, the new-game page's choices, and the wheel's scroll steps (F3) on one page.
 * [overGame]: the page sits over the computer's board, which keeps thinking behind it (contradiction
 * 3); a page opened from Home sits over none, so it never starts a search (N2).
 */
class MenuViewModel(
    private val owner: PuzzleOwner,
    private val game: GameOwner,
    private val modes: ModeOwner,
    private val page: MenuPage,
    private val overGame: Boolean,
) : WheelViewModel<Unit>() {
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
        // The Missed Puzzles are read ahead, so a tap on a row reads no file on the main thread, and the
        // ones the Pack lost are found: the Puzzles page's count and Missed's rows leave them out (N13).
        if (page == MenuPage.MISSED || page == MenuPage.PUZZLES) owner.prefetchMissed()
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
        modes.set(Mode.PUZZLES)
        return true
    }

    /**
     * A Missed replay: true when it started, and the Puzzle board opens on it (N2); false for a Puzzle
     * the Pack lost (N13), and for one the Missed check hasn't read yet: that tap replays when the
     * check lands, unless the page was left first, and [replayed] turns true for the page to go (N23).
     */
    fun replay(id: String): Boolean {
        owner.touched()
        if (!owner.replayMissed(id, landed)) return false
        modes.set(Mode.PUZZLES)
        return true
    }

    /** A tap kept while the Missed check ran has replayed: the page goes to the board. */
    var replayed by mutableStateOf(false)
        private set

    private val landed: () -> Unit = {
        modes.set(Mode.PUZZLES)
        replayed = true
    }

    /** The page is left (hidden or gone): a tap kept for it replays nothing. */
    fun hidden() = owner.dropPendingReplay(landed)

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) = hidden()

    override fun onCleared() = hidden()

    /** A Pieces row (N17): the next Piece Set, and the Menu stays. */
    fun nextPieceSet() {
        owner.touched()
        game.touched()
        owner.nextPieceSet()
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

    /** Level 8's Think Time row cycles 3, 10, 30 s. */
    fun nextThinkTime(current: Int) {
        val all = ThinkTime.entries.map { (it.ms / 1000).toInt() }
        game.setThinkTime(all[(all.indexOf(current) + 1).mod(all.size)])
    }

    /** F3: on a page that scrolls, the wheel moves one row per detent and takes every event, even at the ends. */
    override fun onWheel(key: Wheel): Boolean {
        if (!page.scrolls) return false
        owner.touched()
        game.touched()
        when (key) {
            Wheel.BACK -> steps.tryEmit(-1)
            Wheel.FORWARD -> steps.tryEmit(1)
            Wheel.CLICK -> {}
        }
        return true
    }

    /** The computer keeps thinking while a page over its board shows (contradiction 3: the Game goes on). */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) = shown()

    /** The page shows: over the board, the computer's turn runs on (a Home page leaves it paused). */
    fun shown() {
        if (overGame) game.resume()
    }

    override fun onAppPause() {
        owner.flush()
        game.pause()
    }
}

/**
 * One page. Back, the arrow or the system's, goes one level up: a sub-page to the Menu, the Menu to
 * the board, a page opened from Home to Home. A page that has done its job (a reset, a Missed replay,
 * a Start) goes back with a result: the Menu, handed it, goes back to the board too, and Home opens
 * the Puzzle board or the computer's in the page's place (N2). System Back never carries one.
 *
 * [overGame]: opened from the computer's board, or from a page over it, so the computer keeps
 * thinking behind it (contradiction 3). Home's pages pass false, New game included (N2).
 */
class MenuScreen(
    sealedActivity: SealedLightActivity,
    private val page: MenuPage,
    private val overGame: Boolean,
) : LightScreen<Unit, MenuViewModel>(sealedActivity) {

    override val viewModelClass: Class<MenuViewModel>
        get() = MenuViewModel::class.java

    private val owner: PuzzleOwner by lazy { PuzzleOwner.of(lightContext.filesDir, lightContext::readAsset) }
    private val game: GameOwner by lazy { GameOwner.of(lightContext.filesDir, lightContext::readAsset) }
    private val modes: ModeOwner by lazy { ModeOwner.of(lightContext.filesDir) }
    private val friends: FriendOwner? get() = FriendOwner.of(lightContext)

    override fun createViewModel() = MenuViewModel(owner, game, modes, page, overGame)

    /** The Puzzles page's places and pages (N16): over it, never over a board. */
    private val navigator by lazy { HomeNavigator(modes::set, this::pushPlace) }

    /** A page over this one: over the board when this one is. */
    private fun open(next: MenuPage) {
        viewModel.leaving()
        navigateTo({ MenuScreen(it, next, overGame) }) { goBack() }
    }

    /** Done: back past this page, with a result (see the class). */
    private fun done() = goBack(Unit)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val session by owner.session.collectAsState()
        val gameState by game.state.collectAsState()
        val history by game.history.collectAsState()
        val gameAwake by game.awake.collectAsState()
        val pieceSet by owner.pieceSet.collectAsState()
        val gone by owner.missedGone.collectAsState()
        // Play a friend exists only once the Relay URL is set (W8).
        val friendState = friends?.state?.collectAsState()?.value
        val vm = viewModel
        val data = session?.data
        LightTheme(colors = themeColors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                // The computer may be thinking behind a page over its board: keep the screen on for it too (contradiction 4).
                AndroidView(factory = { View(it) }, modifier = Modifier.size(0.dp), update = { it.keepScreenOn = overGame && gameAwake })
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }, contentDescription = UiCopy.BACK_DESCRIPTION),
                    center = LightTopBarCenter.Text(page.title),
                )
                val scrollState = rememberScrollState()
                val rowPx = with(LocalDensity.current) { Rows.HEIGHT.toPx() }
                LaunchedEffect(Unit) {
                    vm.scroll.collect { scrollState.animateScrollBy(it * rowPx) }
                }
                LightScrollView(Modifier.weight(1f).fillMaxWidth(), scrollState = scrollState) {
                    when (page) {
                        MenuPage.MENU -> gameState?.takeIf { it.record != null }?.let { GameMenuRows(it, pieceSet) }
                        // The Puzzle on screen, a Missed replay's own (N18).
                        MenuPage.PUZZLE_MENU -> MenuItems(PuzzleMenu.of(session?.attempt, pieceSet)) { vm.nextPieceSet() }
                        MenuPage.GAME_REVIEW_MENU -> MenuItems(GameReviewMenu.of(pieceSet)) { vm.nextPieceSet() }
                        MenuPage.PUZZLES -> for (row in PuzzlesRows.of(session, gone)) {
                            if (row.tappable) MenuRow(row.text) { tapPuzzles(row.entry) } else MenuLine(row.text, lighten = true)
                        }
                        MenuPage.RATING -> if (data != null) {
                            MenuItems(PuzzlesPages.rating(data.player, vm.confirmingReset)) { if (vm.tapReset()) done() }
                        }
                        // A row the Pack lost is a lightened line; a tap that finds it lost stays here (N13).
                        MenuPage.MISSED -> if (data != null) {
                            // A tap the Missed check hadn't reached replays when it lands (N23).
                            LaunchedEffect(vm.replayed) { if (vm.replayed) done() }
                            MenuItems(PuzzlesPages.missed(data.missed, gone)) { id -> if (vm.replay(id)) done() }
                        }
                        MenuPage.PAST_PUZZLES -> if (data != null) MenuItems(PuzzlesPages.past(data.history)) {}
                        MenuPage.ABOUT -> {
                            val about = UiCopy.about(owner.packDate, owner.notices, friends = friendState != null)
                            for ((i, paragraph) in about.withIndex()) MenuLine(paragraph, lighten = i > 0)
                        }
                        MenuPage.NEW_GAME -> NewGame(gameState)
                        MenuPage.GAMES -> {
                            // W5: Games against the computer and finished Correspondence Games, newest first.
                            val rows = FinishedGames.merge(history, friendState?.games.orEmpty())
                            if (rows.isEmpty()) MenuLine(UiCopy.NO_GAMES, lighten = true)
                            for (row in rows) {
                                MenuRow(row.text) {
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

    /**
     * The Puzzles page's rows (N12, N16): the first readies the rated Puzzle and opens its board over
     * this page; the others open their pages, Missed and Player Rating to be replaced by the board.
     */
    private fun tapPuzzles(entry: PuzzlesEntry) {
        owner.touched()
        if (entry == PuzzlesEntry.START) owner.toRated()
        navigator.open(PuzzlesRows.open(entry))
    }

    /** The computer's Menu (N5, N17): this board's actions, then Pieces, from [GameMenu]. */
    @Composable
    private fun GameMenuRows(state: GameState, pieceSet: PieceSet?) {
        val vm = viewModel
        MenuItems(GameMenu.of(state, pieceSet)) { entry ->
            when (entry) {
                GameMenuEntry.OFFER_DRAW -> {
                    game.offerDraw()
                    if (game.state.value?.phase == Phase.OVER) goBack()
                }
                GameMenuEntry.RESIGN -> if (game.resign()) goBack()
                GameMenuEntry.TAKEBACK -> {
                    game.takeback()
                    goBack()
                }
                GameMenuEntry.FLIP -> {
                    game.flip()
                    goBack()
                }
                GameMenuEntry.MOVES -> open(MenuPage.MOVES)
                GameMenuEntry.THINK_TIME -> vm.nextThinkTime(GameMenu.thinkTimeSeconds(state))
                GameMenuEntry.NEW_GAME -> open(MenuPage.NEW_GAME)
                GameMenuEntry.PIECES -> vm.nextPieceSet()
            }
        }
    }

    /** The new-game page: Level 1-8, the Side to play, Think Time at Level 8, and Start (F7, F11). */
    @Composable
    private fun NewGame(state: GameState?) {
        val vm = viewModel
        val choices = vm.choices
        MenuLine(UiCopy.LEVEL)
        MenuChoices((1..8).map { "$it" }, choices.level - 1) { vm.choose(choices.copy(level = it + 1)) }
        MenuLine(UiCopy.PLAY_AS)
        MenuChoices(listOf(UiCopy.WHITE, UiCopy.BLACK, UiCopy.RANDOM), choices.side.ordinal) {
            vm.choose(choices.copy(side = SideChoice.entries[it]))
        }
        if (choices.level == 8) {
            val all = ThinkTime.entries.map { (it.ms / 1000).toInt() }
            MenuLine(UiCopy.THINK_TIME)
            MenuChoices(all.map(UiCopy::seconds), all.indexOf(choices.thinkTimeSeconds)) {
                vm.choose(choices.copy(thinkTimeSeconds = all[it]))
            }
        }
        val replacing = state?.confirming == Confirm.REPLACE
        MenuRow(if (replacing) UiCopy.REPLACE_CONFIRM else UiCopy.START) {
            if (vm.start()) done()
        }
        if (state?.inProgress == true) MenuLine(UiCopy.REPLACE_NOTE, lighten = true)
    }
}
