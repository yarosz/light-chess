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

/** Home's wheel (N10, F3): one row per detent while the list scrolls, else the wheel stays with LightOS. */
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
 * Home (N1): the Tool's root, a list titled "Chess" with no back arrow, so system back from it closes
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
        open(Navigation.launch(modes.mode.value, friendsOn = friends != null).last(), writeMode = false)
    }

    /** Opens [place] over Home, writing its mode (N2) unless it is the launch's. */
    private fun open(place: Place, writeMode: Boolean = true) {
        if (writeMode) Navigation.mode(place)?.let(modes::set)
        when (place) {
            Place.HOME -> {}
            Place.PUZZLE -> navigateTo({ PuzzleScreen(it) })
            Place.COMPUTER -> navigateTo({ GameScreen(it) })
            // Start replaces the new-game page with the board, so back from the board comes here.
            Place.NEW_GAME -> navigateTo({ MenuScreen(it, MenuPage.NEW_GAME) }) { open(Place.COMPUTER) }
            Place.PLAY_FRIEND -> navigateTo({ FriendListScreen(it) })
            // Reset rating and a Missed replay replace their page with the Puzzle board.
            Place.PLAYER_RATING -> navigateTo({ MenuScreen(it, MenuPage.RATING) }) { open(Place.PUZZLE) }
            Place.MISSED -> navigateTo({ MenuScreen(it, MenuPage.MISSED) }) { open(Place.PUZZLE) }
            Place.GAMES -> navigateTo({ MenuScreen(it, MenuPage.GAMES) })
            Place.ABOUT -> navigateTo({ MenuScreen(it, MenuPage.ABOUT) })
        }
    }

    private fun tap(entry: HomeEntry) {
        owner.touched()
        if (entry == HomeEntry.PIECES) {
            owner.nextPieceSet()
            return
        }
        Navigation.open(entry, gameInProgress = game.state.value?.inProgress == true)?.let(::open)
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val session by owner.session.collectAsState()
        val pieceSet by owner.pieceSet.collectAsState()
        val friendState = friends?.state?.collectAsState()?.value
        val vm = viewModel
        val data = session?.data
        val rows = HomeRows.of(data?.player?.text, data?.missed?.size, friendState?.yourMove, pieceSet)
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
