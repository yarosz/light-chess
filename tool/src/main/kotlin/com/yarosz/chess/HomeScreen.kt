package com.yarosz.chess

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.yarosz.chess.board.Wheel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Home's wheel (N10, F3): one row per detent while the list scrolls, else the wheel stays with LightOS.
 * With N11's five rows it fits on the LP3, so the wheel stays with LightOS; it still measures.
 */
class HomeViewModel(
    private val owner: PuzzleOwner,
    private val game: GameOwner,
    /** Play a friend's owner, or null while the Relay URL is empty (W8). */
    private val friends: () -> FriendOwner?,
) : WheelViewModel<Unit>() {
    private val steps = MutableSharedFlow<Int>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** One row per wheel detent: -1 up, +1 down. */
    val scroll: SharedFlow<Int> = steps

    /** Whether the list is longer than the screen, from its layout: only then does it take the wheel. */
    var scrolls = false

    override fun onWheel(key: Wheel): Boolean {
        if (!scrolls) return false
        owner.touched()
        when (key) {
            Wheel.BACK -> steps.tryEmit(-1)
            Wheel.FORWARD -> steps.tryEmit(1)
            Wheel.CLICK -> {}
        }
        return true
    }

    /** The Tool opening, or Home showing again: Play a friend syncs (W7). */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        friends()?.sync()
    }

    override fun onAppPause() {
        owner.flush()
        game.pause()
    }
}

/**
 * [place]'s screen over the top one (N2, N16), opened by Home or the Puzzles page. Its pages sit over
 * no board, so none lets the computer think (overGame false, New game included). [onDone] runs once
 * its page goes back with a result (Start, Reset rating, a Missed replay), after the page is popped.
 */
fun SimpleLightScreen<*>.pushPlace(place: Place, onDone: (() -> Unit)?) {
    val done: ((Unit) -> Unit)? = onDone?.let { run -> { run() } }
    fun page(page: MenuPage) = navigateTo({ MenuScreen(it, page, overGame = false) }, done)
    when (place) {
        Place.HOME -> {}
        Place.PUZZLES -> page(MenuPage.PUZZLES)
        Place.PUZZLE -> navigateTo({ PuzzleScreen(it) }, done)
        Place.COMPUTER -> navigateTo({ GameScreen(it) }, done)
        Place.NEW_GAME -> page(MenuPage.NEW_GAME)
        Place.PLAY_FRIEND -> navigateTo({ FriendListScreen(it) }, done)
        Place.PLAYER_RATING -> page(MenuPage.PLAYER_RATING)
        Place.MISSED -> page(MenuPage.MISSED)
        Place.PAST_PUZZLES -> page(MenuPage.PAST_PUZZLES)
        Place.GAMES -> page(MenuPage.GAMES)
        Place.ABOUT -> page(MenuPage.ABOUT)
    }
}

/**
 * Home (N1, N11): the Tool's root, a list titled "Chess" with no back arrow, so system back from it closes
 * Chess. It opens on the place last used, pushed over it (N2), and each row opens a place or a page
 * one step up from it.
 */
@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, HomeViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeViewModel>
        get() = HomeViewModel::class.java

    private val owner: PuzzleOwner by lazy { PuzzleOwner.of(lightContext.filesDir, lightContext::readAsset) }
    private val game: GameOwner by lazy { GameOwner.of(lightContext.filesDir, lightContext::readAsset) }
    private val modes: ModeOwner by lazy { ModeOwner.of(lightContext.filesDir) }

    /** Read each time: null while the Relay URL is empty (W8). */
    private val friends: FriendOwner? get() = FriendOwner.of(lightContext)

    override fun createViewModel() = HomeViewModel(owner, game) { friends }

    /** The place last used has been pushed over Home, once per activity (N2). */
    private var launched = false

    /**
     * The activity's first show (its first onResume): the place last used goes on top of Home before
     * Home is ever drawn, so a cold start in Puzzles opens straight into the Puzzle (A10).
     */
    override fun willShow() {
        if (launched) return
        launched = true
        navigator.launch(modes.mode.value, friendsOn = friends != null)
    }

    /** Which place opens over which, and what replaces a page once done (N2, N16): `HomeTest`. */
    private val navigator by lazy { HomeNavigator(modes::set, this::pushPlace) }

    private fun tap(entry: HomeEntry) {
        owner.touched()
        navigator.open(Navigation.open(entry, gameInProgress = game.state.value?.inProgress == true))
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val friendState = friends?.state?.collectAsState()?.value
        val vm = viewModel
        val rows = HomeRows.of(friendState?.yourMove)
        LightTheme(colors = themeColors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(center = LightTopBarCenter.Text(UiCopy.HOME_TITLE))
                val scrollState = rememberScrollState()
                val rowPx = with(LocalDensity.current) { Rows.HEIGHT.toPx() }
                LaunchedEffect(scrollState) {
                    snapshotFlow { scrollState.maxValue > 0 }.collect { vm.scrolls = it }
                }
                LaunchedEffect(Unit) {
                    vm.scroll.collect { scrollState.animateScrollBy(it * rowPx) }
                }
                LightScrollView(Modifier.weight(1f).fillMaxWidth(), scrollState = scrollState) {
                    for (row in rows) MenuRow(row.text) { tap(row.entry) }
                }
            }
        }
    }
}
