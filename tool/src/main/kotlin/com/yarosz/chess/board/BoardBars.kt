package com.yarosz.chess.board

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightGrid
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.lightClickable

/**
 * Layout E's board bars (decision log "Layout E", E1-E5): LightOS's top bar over the board, with the
 * back arrow, the status as its title and the Menu mark; and the action row under the board, with the
 * buttons at the right and, on a Game's board, the Captured Pieces at the left. `StripFitTest` reads
 * these values to check that every status and every row fits the LP3.
 */
object BarLayout {
    /**
     * The top bar's geometry, light-sdk `LightTopBar`'s own (`StripFitTest` reads them from its source):
     * 3 grid units tall (40 dp on the LP3), 1 unit of padding either side, 2-unit icons, a title at
     * most 18 units wide (240 dp), in `Fine`.
     */
    const val TOP_BAR_UNITS = 3f
    const val TOP_BAR_PADDING_UNITS = 1f
    const val TITLE_MAX_WIDTH_UNITS = 18f

    /** LightOS `Fine`: 25 design px, a line height of 1.15 and 0.03 em between letters (light-sdk `LightTheme.kt`). */
    const val TITLE_DESIGN_PX = 25f
    const val TITLE_LINE_HEIGHT = 1.15f
    const val TITLE_LETTER_SPACING = 0.03f

    /**
     * E3: a status too long for one title line takes a second, at the same size. Two `Fine` lines are
     * 57.5 design px, 37 dp on the LP3, inside the bar's 40 dp.
     */
    const val TITLE_MAX_LINES = 2

    /**
     * The Menu mark's target in the top bar (E1): the bar's full height, square, the SDK's right
     * button box. The mark's ink still ends [StripLayout.MENU_INK_END] from the screen's edge.
     */
    const val MARK_TARGET_UNITS = 3f

    /** The last button's label ends this far from the screen's right edge: its ink 16 dp in, under the mark's (E2). */
    val BUTTONS_END: Dp = StripLayout.buttonsEnd(menu = false)

    /** Space between the Captured Pieces' room and the first button (E4). */
    val CAPTURED_GAP: Dp = StripLayout.STATUS_GAP

    fun unit(screenWidth: Dp): Dp = screenWidth / LightGrid.WIDTH

    fun topBarHeight(screenWidth: Dp): Dp = unit(screenWidth) * TOP_BAR_UNITS

    fun titleMaxWidth(screenWidth: Dp): Dp = unit(screenWidth) * TITLE_MAX_WIDTH_UNITS

    /** The board's left edge, where the Captured Pieces start (P3 keeps the row aligned with the board). */
    fun boardSide(screenWidth: Dp): Dp = (screenWidth - POSITION_VIEW_SIZE) / 2
}

/**
 * E1: the board's top bar, light-sdk's `LightTopBar` itself for the back arrow ([back], which does
 * what system back does) and the Menu mark at its right ([menu], where the board has a Menu), and the
 * status as its title: `Fine`, centred, at most 18 grid units wide as the SDK's title is, but on up to
 * [BarLayout.TITLE_MAX_LINES] lines (E3), which the SDK's one-line title can't.
 */
@Composable
fun BoardTopBar(status: String, back: StripButton, menu: StripButton? = null, modifier: Modifier = Modifier) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val unit = BarLayout.unit(screenWidth)
    val color = LightThemeTokens.colors.content
    // The mark's box ends one grid unit in (the bar's padding); its ink ends 16 dp in, as on D's strip.
    val inset = StripLayout.MENU_INK_END - unit * BarLayout.TOP_BAR_PADDING_UNITS
    val mark = remember(color, inset) { MenuMarkPainter(color, inset) }
    Box(modifier.fillMaxWidth().height(BarLayout.topBarHeight(screenWidth))) {
        Box(
            Modifier.fillMaxSize().padding(horizontal = unit * BarLayout.TOP_BAR_PADDING_UNITS),
            contentAlignment = Alignment.Center,
        ) {
            LightText(
                text = status,
                variant = LightTextVariant.Fine,
                align = TextAlign.Center,
                maxLines = BarLayout.TITLE_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = BarLayout.titleMaxWidth(screenWidth)),
            )
        }
        LightTopBar(
            leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = back.onClick, contentDescription = back.description),
            rightButton = menu?.let {
                LightBarButton.Icon(painter = mark, onClick = it.onClick, contentDescription = it.description, sizeUnits = BarLayout.MARK_TARGET_UNITS)
            },
        )
    }
}

/**
 * N4's mark as a top-bar icon: three solid squares, 8 by 7 px on the LP3, 28 px apart, centred on the
 * bar's middle line as the SDK centres the back arrow, the last one's ink ending [inset] inside the
 * box's right edge.
 */
private class MenuMarkPainter(private val color: Color, private val inset: Dp) : Painter() {
    override val intrinsicSize: Size get() = Size.Unspecified

    override fun DrawScope.onDraw() {
        val w = StripLayout.DOT_WIDTH.toPx()
        val h = StripLayout.DOT_HEIGHT.toPx()
        val pitch = StripLayout.DOT_PITCH.toPx()
        val lastLeft = size.width - inset.toPx() - w
        for (i in 0 until 3) {
            drawRect(color, topLeft = Offset(lastLeft - (2 - i) * pitch, size.height / 2 - h / 2), size = Size(w, h))
        }
    }
}

/**
 * E2 and E4: the row under the board. This moment's [buttons] (at most three, contradiction 2) at the
 * right, in LightOS `Copy`, each a target the row's full height, the last label's ink ending 16 dp
 * from the edge. On a Game's board, [captured] (P3) at the left, from the board's left edge to just
 * before the first button, tightened to fit that room ([CapturedRowLayout.fit]).
 */
@Composable
fun ActionRow(buttons: List<StripButton>, modifier: Modifier = Modifier, captured: CapturedRowState? = null) {
    require(buttons.size <= 3) { "the action row holds at most 3 buttons" }
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = BarLayout.boardSide(screenWidth), end = BarLayout.BUTTONS_END),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxHeight().padding(end = BarLayout.CAPTURED_GAP),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (captured != null && captured.shown) {
                // The drawings (not the canvas, which has room above them) centre on the row's middle.
                // The row never runs past the board's right edge (no buttons: the board's width, as P3).
                CapturedRow(captured, minOf(maxWidth, POSITION_VIEW_SIZE), Modifier.offset(y = -(CapturedRowLayout.TOP / 2).dp))
            }
        }
        for (button in buttons) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .lightClickable(onClickLabel = button.description, role = Role.Button) { if (button.enabled) button.onClick() }
                    .semantics { contentDescription = button.description }
                    .padding(horizontal = StripLayout.BUTTON_PADDING),
                contentAlignment = Alignment.Center,
            ) {
                LightText(text = button.label, variant = LightTextVariant.Copy, lighten = !button.enabled, maxLines = 1)
            }
        }
    }
}
