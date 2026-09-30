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
import com.yarosz.chess.board.Strip
import com.yarosz.chess.board.StripButton
import com.yarosz.chess.board.Wheel
import com.yarosz.chess.correspondence.CorrespondenceGame
import com.yarosz.chess.correspondence.Delivery
import com.yarosz.chess.correspondence.Refusal
import com.yarosz.chess.correspondence.Stage
import com.yarosz.chess.games.SideChoice
import com.yarosz.chess.relay.InviteCodes
import com.yarosz.chess.relay.Protocol
import com.yarosz.chess.rules.Side
import kotlin.random.Random
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The Play a friend pages that aren't the list or a board (W6). Each is its own [FriendScreen] on the
 * back stack, so system back pops one level (S3, N6); only New game turns into its invite in place.
 */
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
    /** The page on screen: New game becomes its invite once the code is made, so back from the invite goes to the list (N6). */
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

    /** Another page opens over this one: a pending second tap is dropped. */
    fun leaving() {
        confirming = null
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
                confirming = null
                page = FriendPage.INVITE
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

    override fun createViewModel() = FriendViewModel(friends, startPage, startGame)

    /** Opens [page] for this page's Game over this one; an exit it hands back leaves this page too. */
    private fun open(page: FriendPage) {
        viewModel.leaving()
        val id = viewModel.gameId
        navigateTo({ FriendScreen(it, page, id) }) { exit -> if (exit != null) goBack(exit) }
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by friends.state.collectAsState()
        val vm = viewModel
        val page = vm.page
        val game = vm.gameId?.let(state::game)
        LightTheme(colors = themeColors) {
            when (page) {
                FriendPage.ENTER_CODE -> EnterCode()
                FriendPage.RENAME -> if (game != null) Rename(game) else LaunchedEffect(Unit) { goBack() }
                else -> Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }, contentDescription = UiCopy.BACK_DESCRIPTION),
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
     * the note below it, and the strip with the Menu mark (N4). Once the friend takes the Seat, the board opens.
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
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp).padding(top = 24.dp)) {
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
                        else -> {}
                    }
                }
            }
            val menu = StripButton(UiCopy.MENU_DESCRIPTION) { open(FriendPage.MENU) }
            Strip(strip.status, buttons, Modifier.padding(bottom = 8.dp), menu = menu.takeIf { strip.menu })
        }
    }

    /** Enter code (W6): one field on the LP3 keyboard, then Join; the title carries the answer. */
    @Composable
    private fun EnterCode() {
        val vm = viewModel
        val text = rememberTextFieldState()
        LightTextInputEditor(
            title = if (vm.busy) UiCopy.SENDING else vm.error ?: UiCopy.ENTER_CODE,
            state = text,
            onSubmit = { typed -> vm.redeem(typed.toString()) { id -> goBack(FriendExit(id)) } },
            onBack = { goBack() },
            keyboardOptionsFlow = rememberKeyboardOptions(),
            modifier = Modifier.background(LightThemeTokens.colors.background),
            submitLabel = UiCopy.JOIN,
            singleLine = true,
            initialCaps = true,
        )
    }

    /** Rename (W6, F11): the Opponent Label, on this phone only; its own screen over the Menu (N6). */
    @Composable
    private fun Rename(game: CorrespondenceGame) {
        val text = rememberTextFieldState(game.label)
        LightTextInputEditor(
            title = UiCopy.RENAME,
            state = text,
            onSubmit = { typed ->
                friends.rename(game.gameId, typed.toString())
                goBack()
            },
            onBack = { goBack() },
            keyboardOptionsFlow = rememberKeyboardOptions(),
            modifier = Modifier.background(LightThemeTokens.colors.background),
            submitLabel = UiCopy.SAVE,
            singleLine = true,
        )
    }

    /**
     * The Correspondence Game's Menu, or an invite's (N5): only this Game's actions, from [FriendMenu].
     * Moves and Rename open as their own screens (N6).
     */
    @Composable
    private fun GameMenu(game: CorrespondenceGame, state: FriendState) {
        val vm = viewModel
        val id = game.gameId
        for (item in FriendMenu.of(game, state, vm.confirming)) {
            val entry = item.entry
            if (entry == null) {
                MenuLine(item.text, item.lighten)
                continue
            }
            MenuRow(item.text) {
                when (entry) {
                    FriendMenuEntry.SEND_AND_OFFER_DRAW -> {
                        friends.send(id, offerDraw = true)
                        goBack()
                    }
                    FriendMenuEntry.OFFER_DRAW -> {
                        friends.offerDraw(id)
                        goBack()
                    }
                    FriendMenuEntry.RESIGN -> if (vm.confirm(FriendConfirm.RESIGN)) {
                        friends.resign(id)
                        goBack()
                    }
                    FriendMenuEntry.MOVES -> open(FriendPage.MOVES)
                    FriendMenuEntry.RENAME -> open(FriendPage.RENAME)
                    FriendMenuEntry.FORGET -> if (vm.confirm(FriendConfirm.FORGET)) friends.forget(id) { goBack(FriendExit()) }
                }
            }
        }
    }
}
