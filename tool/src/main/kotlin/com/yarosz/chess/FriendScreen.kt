package com.yarosz.chess

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.yarosz.chess.board.POSITION_VIEW_SIZE
import com.yarosz.chess.board.Strip
import com.yarosz.chess.board.StripButton
import com.yarosz.chess.board.Wheel
import com.yarosz.chess.correspondence.CorrespondenceGame
import com.yarosz.chess.correspondence.Delivery
import com.yarosz.chess.correspondence.HaltReason
import com.yarosz.chess.correspondence.Refusal
import com.yarosz.chess.correspondence.Stage
import com.yarosz.chess.games.SideChoice
import com.yarosz.chess.relay.EntryKind
import com.yarosz.chess.relay.InviteCodes
import com.yarosz.chess.relay.Protocol
import com.yarosz.chess.rules.Side
import androidx.compose.foundation.layout.width
import kotlin.random.Random
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** The Play a friend pages that aren't the list or a board (W6). */
enum class FriendPage(val title: String, val scrolls: Boolean) {
    NEW(UiCopy.NEW_GAME, true),
    INVITE(UiCopy.INVITE, false),
    ENTER_CODE(UiCopy.ENTER_CODE, false),
    MENU(UiCopy.MENU_TITLE, true),
    MOVES(UiCopy.MOVES, true),
    RENAME(UiCopy.RENAME, false),
}

/** A second tap a page waits for (G2, W6, B5): cancel the invite, forget the Game, resign. */
enum class FriendConfirm { CANCEL, FORGET, RESIGN }

class FriendViewModel(private val friends: FriendOwner, startPage: FriendPage, startGame: String?) : WheelViewModel<FriendExit?>() {
    /** The page Back leaves from: New game becomes its invite once the code is made. */
    var home by mutableStateOf(startPage)
        private set
    var page by mutableStateOf(startPage)
        private set
    var gameId by mutableStateOf(startGame)
        private set

    var side by mutableStateOf(SideChoice.WHITE)
    var days by mutableStateOf(Protocol.DEFAULT_DAYS_PER_MOVE)

    /** A create or redeem in flight: the page waits for the Relay's answer. */
    var busy by mutableStateOf(false)
        private set

    /** What the last create or redeem came to, when it wasn't the Game: W6's error copy. */
    var error by mutableStateOf<String?>(null)
        private set

    var confirming by mutableStateOf<FriendConfirm?>(null)
        private set

    private val steps = MutableSharedFlow<Int>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val scroll: SharedFlow<Int> = steps

    fun open(next: FriendPage) {
        confirming = null
        error = null
        page = next
    }

    /**
     * A first tap asks for a second (true on the second, which acts). As with Resign against the
     * computer, the ask stands until the second tap or another page.
     */
    fun confirm(what: FriendConfirm): Boolean {
        if (confirming == what) {
            confirming = null
            return true
        }
        confirming = what
        return false
    }

    fun create() {
        if (busy) return
        busy = true
        error = null
        val side = when (side) {
            SideChoice.WHITE -> Side.WHITE
            SideChoice.BLACK -> Side.BLACK
            SideChoice.RANDOM -> if (Random.nextBoolean()) Side.WHITE else Side.BLACK
        }
        friends.create(side, days) { delivery ->
            busy = false
            val game = (delivery as? Delivery.Done)?.game
            if (game != null) {
                gameId = game.gameId
                home = FriendPage.INVITE
                open(FriendPage.INVITE)
            } else {
                error = (delivery as? Delivery.Refused)?.let { UiCopy.refusal(it.reason) }
            }
        }
    }

    /** Join: [done] gets the Game it opened; errors stay on the page (W6). */
    fun redeem(typed: String, done: (String) -> Unit) {
        if (busy) return
        if (InviteCodes.normalize(typed) == null) {
            error = UiCopy.refusal(Refusal.BAD_CODE)
            return
        }
        busy = true
        error = null
        friends.redeem(typed) { delivery ->
            busy = false
            val game = (delivery as? Delivery.Done)?.game
            if (game != null) done(game.gameId) else error = (delivery as? Delivery.Refused)?.let { UiCopy.refusal(it.reason) }
        }
    }

    override fun onWheel(key: Wheel): Boolean {
        if (!page.scrolls) return false
        when (key) {
            Wheel.BACK -> steps.tryEmit(-1)
            Wheel.FORWARD -> steps.tryEmit(1)
            Wheel.CLICK -> {}
        }
        return true
    }

    /** W7: Play a friend on screen syncs. */
    override fun onScreenShow(screen: SimpleLightScreen<FriendExit?>) = friends.sync()
}

class FriendScreen(
    sealedActivity: SealedLightActivity,
    private val startPage: FriendPage,
    private val startGame: String? = null,
) : LightScreen<FriendExit?, FriendViewModel>(sealedActivity) {

    override val viewModelClass: Class<FriendViewModel>
        get() = FriendViewModel::class.java

    private val friends: FriendOwner by lazy { checkNotNull(FriendOwner.of(lightContext)) { "Play a friend is off" } }

    /** For the Piece Set's row only: it is kept in `puzzles.json` (P2, M1). */
    private val owner: PuzzleOwner by lazy { PuzzleOwner.of(lightContext.filesDir, lightContext::readAsset) }

    override fun createViewModel() = FriendViewModel(friends, startPage, startGame)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by friends.state.collectAsState()
        val vm = viewModel
        val page = vm.page
        val game = vm.gameId?.let(state::game)
        val back = { if (page == vm.home) goBack() else vm.open(vm.home) }
        LightTheme(colors = themeColors) {
            when (page) {
                FriendPage.ENTER_CODE -> EnterCode(back)
                FriendPage.RENAME -> if (game != null) Rename(game) else LaunchedEffect(Unit) { back() }
                else -> Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = back),
                        center = LightTopBarCenter.Text(page.title),
                    )
                    when (page) {
                        FriendPage.INVITE -> if (game != null) Invite(game, state) else LaunchedEffect(Unit) { goBack(FriendExit()) }
                        else -> {
                            val scrollState = rememberScrollState()
                            val rowPx = with(LocalDensity.current) { Rows.HEIGHT.toPx() }
                            LaunchedEffect(page) {
                                scrollState.scrollTo(0)
                                vm.scroll.collect { scrollState.animateScrollBy(it * rowPx) }
                            }
                            LightScrollView(Modifier.weight(1f).fillMaxWidth(), scrollState = scrollState) {
                                when (page) {
                                    FriendPage.NEW -> NewGame(state)
                                    FriendPage.MENU -> if (game != null) GameMenu(game, state)
                                    FriendPage.MOVES -> game?.log?.let { MoveList(it.game) }
                                    else -> {}
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** New game (W6): Play as, Days per move (1 / 3 / 7, default 3, C2), then Create code. */
    @Composable
    private fun NewGame(state: FriendState) {
        val vm = viewModel
        MenuLine(UiCopy.PLAY_AS)
        MenuChoices(listOf(UiCopy.WHITE, UiCopy.BLACK, UiCopy.RANDOM), vm.side.ordinal) { vm.side = SideChoice.entries[it] }
        MenuLine(UiCopy.DAYS_PER_MOVE)
        MenuChoices(Protocol.DAYS_PER_MOVE.map { "$it" }, Protocol.DAYS_PER_MOVE.indexOf(vm.days)) { vm.days = Protocol.DAYS_PER_MOVE[it] }
        MenuRow(if (vm.busy) UiCopy.SENDING else UiCopy.CREATE_CODE, lighten = vm.busy || state.full) {
            if (state.full) return@MenuRow
            vm.create()
        }
        val error = vm.error ?: UiCopy.refusal(Refusal.CAP_REACHED).takeIf { state.full }
        if (error != null) MenuLine(error, lighten = true)
    }

    /**
     * The invite (W6, G2): the code as ABCD-EFGH, the largest text on the page and alone on its line (in
     * LightOS Subtitle: Title is too wide for nine characters on the LP3),
     * the note below it, and the strip. Once the friend takes the Seat, the board opens.
     */
    @Composable
    private fun Invite(game: CorrespondenceGame, state: FriendState) {
        val vm = viewModel
        val now by produceState(friends.serverNow()) {
            while (true) {
                delay(30_000L)
                value = friends.serverNow()
            }
        }
        LaunchedEffect(game.stage) { if (game.stage != Stage.WAITING) goBack(FriendExit(game.gameId)) }
        val code = game.invite?.code
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(top = 24.dp)) {
                if (code != null) {
                    LightText(
                        text = InviteCodes.display(code),
                        variant = LightTextVariant.Subtitle,
                        align = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    )
                    LightText(text = UiCopy.CODE_NOTE, variant = LightTextVariant.Copy, lighten = true, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                } else {
                    LightText(text = UiCopy.friendRow(game.label, UiCopy.REMATCH_SENT), variant = LightTextVariant.Copy, modifier = Modifier.fillMaxWidth())
                }
            }
            val strip = FriendStrip.invite(game, now, vm.confirming == FriendConfirm.CANCEL, state.notices[game.gameId]?.text)
            val buttons = strip.buttons.map { button ->
                StripButton(button.label, button.description) {
                    when (button) {
                        FriendButton.CANCEL -> if (vm.confirm(FriendConfirm.CANCEL)) friends.cancelInvite(game.gameId)
                        FriendButton.RETRY -> friends.cancelInvite(game.gameId)
                        FriendButton.MENU -> vm.open(FriendPage.MENU)
                        else -> {}
                    }
                }
            }
            Strip(strip.status, buttons, Modifier.width(POSITION_VIEW_SIZE).padding(bottom = 8.dp))
        }
    }

    /** Enter code (W6): one field on the LP3 keyboard, then Join; the title carries the answer. */
    @Composable
    private fun EnterCode(back: () -> Unit) {
        val vm = viewModel
        val text = rememberTextFieldState()
        LightTextInputEditor(
            title = if (vm.busy) UiCopy.SENDING else vm.error ?: UiCopy.ENTER_CODE,
            state = text,
            onSubmit = { typed -> vm.redeem(typed.toString()) { id -> goBack(FriendExit(id)) } },
            onBack = back,
            keyboardOptionsFlow = rememberKeyboardOptions(),
            modifier = Modifier.background(LightThemeTokens.colors.background),
            submitLabel = UiCopy.JOIN,
            singleLine = true,
            initialCaps = true,
        )
    }

    /** Rename (W6, F11): the Opponent Label, on this phone only. */
    @Composable
    private fun Rename(game: CorrespondenceGame) {
        val vm = viewModel
        val text = rememberTextFieldState(game.label)
        LightTextInputEditor(
            title = UiCopy.RENAME,
            state = text,
            onSubmit = { typed ->
                friends.rename(game.gameId, typed.toString())
                vm.open(FriendPage.MENU)
            },
            onBack = { vm.open(FriendPage.MENU) },
            keyboardOptionsFlow = rememberKeyboardOptions(),
            modifier = Modifier.background(LightThemeTokens.colors.background),
            submitLabel = UiCopy.SAVE,
            singleLine = true,
        )
    }

    /**
     * The Correspondence Game's Menu (W2, W4, W6), top to bottom: why it stopped, the Game's actions,
     * Moves, Rename, Forget game, then back to Play a friend, Pieces and About. No Game Hint, no Takeback, no
     * flip (W10, G3).
     */
    @Composable
    private fun GameMenu(game: CorrespondenceGame, state: FriendState) {
        val vm = viewModel
        val id = game.gameId
        val log = game.log
        when (game.halt?.reason) {
            HaltReason.OUT_OF_SYNC -> MenuLine(UiCopy.OUT_OF_SYNC_ROW)
            HaltReason.NEEDS_UPDATE -> MenuLine(UiCopy.UPDATE_CHESS_ROW)
            HaltReason.GONE -> MenuLine(UiCopy.GAME_DELETED_ROW)
            HaltReason.SEAT_LOST -> MenuLine(UiCopy.SEAT_LOST)
            null -> {}
        }
        val idle = game.halt == null && game.pending == null && id !in state.sending
        if (log != null && game.stage == Stage.ACTIVE && idle) {
            when {
                id in state.chosen -> MenuRow(UiCopy.SEND_AND_OFFER_DRAW) {
                    friends.send(id, offerDraw = true)
                    goBack()
                }
                log.draft(game.seat.side, EntryKind.DRAW_OFFER) != null -> MenuRow(UiCopy.OFFER_DRAW) {
                    friends.offerDraw(id)
                    goBack()
                }
                game.yourMove -> MenuLine(UiCopy.OFFER_DRAW_AFTER_MOVE, lighten = true)
            }
            MenuRow(if (vm.confirming == FriendConfirm.RESIGN) UiCopy.RESIGN_CONFIRM else UiCopy.RESIGN) {
                if (vm.confirm(FriendConfirm.RESIGN)) {
                    friends.resign(id)
                    goBack()
                }
            }
        }
        if (log != null) MenuRow(UiCopy.MOVES) { vm.open(FriendPage.MOVES) }
        MenuRow(UiCopy.RENAME) { vm.open(FriendPage.RENAME) }
        if (game.stage == Stage.OVER || game.halt != null) {
            MenuRow(if (vm.confirming == FriendConfirm.FORGET) UiCopy.FORGET_CONFIRM else UiCopy.FORGET_GAME) {
                if (vm.confirm(FriendConfirm.FORGET)) friends.forget(id) { goBack(FriendExit()) }
            }
        }
        MenuRow(UiCopy.PLAY_FRIEND) { goBack(FriendExit()) }
        // P2, M1: the one Piece Set, just above About as in every Menu.
        val pieceSet = owner.pieceSet.collectAsState().value
        if (pieceSet != null) MenuRow(UiCopy.piecesRow(pieceSet)) { owner.nextPieceSet() }
        MenuRow(UiCopy.ABOUT) { navigateTo({ MenuScreen(it, MenuPage.ABOUT) }) }
    }
}

/**
 * The Play a friend page (W6), the Tool's first screen in the friend mode: the Games in E7's order,
 * then New game and Enter code, lightened with "Finish a game first" at the cap of five. The Menu is
 * in the top bar: next to New game and Enter code the strip has no room for it.
 */
@Composable
fun FriendList(state: FriendState, now: Long, onRow: (FriendRow) -> Unit, onNew: () -> Unit, onEnterCode: () -> Unit, onMenu: () -> Unit) {
    val rows = FriendRows.of(state.games, state.seats, now)
    Column(Modifier.fillMaxSize()) {
        LightTopBar(
            center = LightTopBarCenter.Text(UiCopy.PLAY_FRIEND),
            rightButton = LightBarButton.Text(UiCopy.MENU, contentDescription = UiCopy.MENU_DESCRIPTION, onClick = onMenu),
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
            Modifier.padding(horizontal = 24.dp).width(POSITION_VIEW_SIZE),
        )
    }
}
