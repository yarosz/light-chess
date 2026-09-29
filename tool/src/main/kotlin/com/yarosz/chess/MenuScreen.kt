package com.yarosz.chess

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The Menu's pages. The Menu is a visible button because back leaves the Tool (PLATFORM.md). Each page
 * is its own [MenuScreen] on the SDK's back stack: system Back never reaches a screen (LightActivity
 * pops the stack itself), so a sub-page that were only state inside one screen would skip the Menu.
 */
enum class MenuPage(val scrolls: Boolean) { MENU(false), RATING(true), MISSED(true), ABOUT(true) }

/** The reset's second tap and the wheel's scroll steps (F3) on one page. */
class MenuViewModel(private val owner: PuzzleOwner, private val page: MenuPage) : WheelViewModel() {
    /** "Reset rating" was tapped once; the next tap resets (F5). */
    var confirmingReset by mutableStateOf(false)
        private set

    private val steps = MutableSharedFlow<Int>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** One row per wheel detent: -1 up, +1 down. */
    val scroll: SharedFlow<Int> = steps

    init {
        // The Missed page reads its Puzzles ahead, so a tap on a row reads no file on the main thread.
        if (page == MenuPage.MISSED) owner.prefetchMissed()
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

    /** F3: on a page that scrolls, the wheel moves one row per detent and takes every event, even at the ends. */
    override fun onWheel(key: Wheel): Boolean {
        if (!page.scrolls) return false
        owner.touched()
        when (key) {
            Wheel.BACK -> steps.tryEmit(-1)
            Wheel.FORWARD -> steps.tryEmit(1)
            Wheel.CLICK -> {}
        }
        return true
    }

    override fun onAppPause() = owner.flush()
}

/**
 * One Menu page. Back, the arrow or the system's, goes one page up: a sub-page to the Menu, the Menu
 * to the puzzle. A sub-page that has done its job (a reset, a Missed replay) leaves the Menu as well:
 * it goes back with a result, and the Menu, handed that result, goes back too. System Back never
 * carries one, so it always stops at the Menu.
 */
class MenuScreen(
    sealedActivity: SealedLightActivity,
    private val page: MenuPage = MenuPage.MENU,
) : LightScreen<Unit, MenuViewModel>(sealedActivity) {

    override val viewModelClass: Class<MenuViewModel>
        get() = MenuViewModel::class.java

    private val owner: PuzzleOwner by lazy { PuzzleOwner.of(lightContext.filesDir, lightContext::readAsset) }

    override fun createViewModel() = MenuViewModel(owner, page)

    private fun open(next: MenuPage) {
        owner.touched()
        navigateTo({ MenuScreen(it, next) }) { goBack() }
    }

    /** Back to the puzzle from a sub-page, past the Menu. */
    private fun leaveMenu() = goBack(Unit)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val session by owner.session.collectAsState()
        val vm = viewModel
        val title = when (page) {
            MenuPage.MENU -> UiCopy.MENU_TITLE
            MenuPage.RATING -> UiCopy.PLAYER_RATING
            MenuPage.MISSED -> UiCopy.MISSED
            MenuPage.ABOUT -> UiCopy.ABOUT
        }
        val data = session?.data
        LightTheme(colors = themeColors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(title),
                )
                val scrollState = rememberScrollState()
                val rowPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
                LaunchedEffect(Unit) {
                    vm.scroll.collect { scrollState.animateScrollBy(it * rowPx) }
                }
                LightScrollView(Modifier.weight(1f).fillMaxWidth(), scrollState = scrollState) {
                    if (data != null) when (page) {
                        MenuPage.MENU -> {
                            Row(UiCopy.ratingRow(data.player.text)) { open(MenuPage.RATING) }
                            Row(UiCopy.missedCount(data.missed.size)) { open(MenuPage.MISSED) }
                            // P2: a tap moves to the next Piece Set, here on the Menu; the board draws it.
                            Row(UiCopy.piecesRow(data.pieceSet)) { owner.nextPieceSet() }
                            Row(UiCopy.ABOUT) { open(MenuPage.ABOUT) }
                            // A9 with D7: the Puzzle on screen, by its Lichess id, as text (v1 smoke fixes).
                            session?.attempt?.let { Line(UiCopy.puzzleRow(it.puzzle.id), lighten = true) }
                        }
                        MenuPage.RATING -> {
                            Line(data.player.text)
                            Row(if (vm.confirmingReset) UiCopy.RESET_CONFIRM else UiCopy.RESET_RATING) {
                                if (vm.tapReset()) leaveMenu()
                            }
                            if (data.history.isEmpty()) Line(UiCopy.NO_HISTORY, lighten = true)
                            for (entry in data.history) {
                                Line(UiCopy.historyRow(entry.puzzleRating, entry.state, entry.delta, entry.solutionShown), lighten = true)
                            }
                        }
                        MenuPage.MISSED -> {
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
                    }
                }
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
