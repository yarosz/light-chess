package com.yarosz.chess.board

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightGrid
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.lightClickable

/**
 * Layout E's board bars (decision log "Layout E", E1-E3, E12-E14): LightOS's top bar over the board,
 * with the back arrow, the status as its title and the Menu mark; and the action row under the board:
 * on a Game's board, the Captured Pieces across the board's width at its top and the buttons' labels
 * centred below them; on the Puzzle board, the labels centred in the row. Every button's target is the
 * row's full height. `StripFitTest` reads
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

    /**
     * E12: the Captured Pieces' line at the top of the action row, from the board's bottom edge to the
     * drawings' bottom: P3's 3 dp, then the drawings ([CapturedRowLayout.BOTTOM], 3 + 15 dp). The
     * drawings' ink stops higher, at [CapturedRowLayout.INK_BOTTOM]; the labels' ink is centred below
     * that ([buttonLine]).
     */
    val PIECES_LINE: Dp = CapturedRowLayout.BOTTOM.dp

    /**
     * E13: LightOS `Copy`'s ink around the baseline, in em, for every label the action row can show:
     * the tallest letter's top ("l", "d", "h" in Solution, Undo, Rematch) [COPY_INK_ASCENT] above it,
     * the descender's bottom ("p", "y" in Accept, Retry) [COPY_INK_DESCENT] below. Measured on the
     * emulator's Roboto (0.750 and 0.214 em). Akkurat on the LP3 is unmeasured: its ink clears the
     * drawings' up to 0.819 em above the baseline and the app area's bottom to 0.283 em below (E13).
     */
    const val COPY_INK_ASCENT = 0.75f
    const val COPY_INK_DESCENT = 0.214f

    fun unit(screenWidth: Dp): Dp = screenWidth / LightGrid.WIDTH

    fun topBarHeight(screenWidth: Dp): Dp = unit(screenWidth) * TOP_BAR_UNITS

    fun titleMaxWidth(screenWidth: Dp): Dp = unit(screenWidth) * TITLE_MAX_WIDTH_UNITS

    /** The board's left edge, where the bottom Side's Captured Pieces start (P3, E12). */
    fun boardSide(screenWidth: Dp): Dp = (screenWidth - POSITION_VIEW_SIZE) / 2

    /**
     * E13: the top of the room a label's ink is centred in: on a Game's board ([captured]) the Captured
     * Pieces' ink bottom ([CapturedRowLayout.INK_BOTTOM], 15.9 dp), whether or not anything is taken
     * yet, so the labels never move; the row's top on the Puzzle board, which has no Captured Pieces
     * (P3, E14).
     */
    fun labelRoomTop(captured: Boolean): Dp = if (captured) CapturedRowLayout.INK_BOTTOM.dp else 0.dp

    /**
     * E13, E14: the baseline, from the row's top, of a button's label in an action row [rowHeight]
     * tall, for `Copy` [em] tall: the label's ink, [COPY_INK_ASCENT] + [COPY_INK_DESCENT] em, is
     * centred between [labelRoomTop] and the row's bottom. The button's target, the row's full
     * height, is not computed here: `ActionRow` gives each button's Box `fillMaxHeight`.
     */
    fun buttonLine(rowHeight: Dp, em: Dp, captured: Boolean): Dp {
        val top = labelRoomTop(captured)
        return top + (rowHeight - top - em * (COPY_INK_ASCENT + COPY_INK_DESCENT)) / 2 + em * COPY_INK_ASCENT
    }
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
 * E12-E14: the row under the board. On a Game's board ([captured] set), the Captured Pieces' line at its
 * top ([BarLayout.PIECES_LINE]): the drawings 3 dp under the board, the bottom Side's end from the
 * board's left edge and the other's from its right edge, placed across the board's width
 * ([CapturedRowLayout.fit]) whatever the buttons. Below them, this moment's [buttons] (at most three,
 * contradiction 2) in LightOS `Copy`, centred as a group, each a target the row's full height, their
 * ink centred between the drawings' ink and the row's bottom ([BarLayout.buttonLine]). On the Puzzle
 * board, which has no Captured Pieces, the ink is centred in the whole row (E14).
 */
@Composable
fun ActionRow(buttons: List<StripButton>, modifier: Modifier = Modifier, captured: CapturedRowState? = null) {
    require(buttons.size <= 3) { "the action row holds at most 3 buttons" }
    val copy = LightThemeTokens.typography.copy
    val em = with(LocalDensity.current) { copy.fontSize.value.designVerticalPxToSp().toDp() }
    val gameBoard = captured != null
    Box(modifier.fillMaxWidth()) {
        if (captured != null && captured.shown) {
            CapturedRow(captured, POSITION_VIEW_SIZE, Modifier.align(Alignment.TopCenter))
        }
        Row(Modifier.align(Alignment.Center).fillMaxHeight()) {
            for (button in buttons) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .lightClickable(onClickLabel = button.description, role = Role.Button) { if (button.enabled) button.onClick() }
                        .semantics { contentDescription = button.description }
                        .padding(horizontal = StripLayout.BUTTON_PADDING),
                ) {
                    LightText(
                        text = button.label,
                        variant = LightTextVariant.Copy,
                        lighten = !button.enabled,
                        maxLines = 1,
                        modifier = Modifier.labelLine(em, gameBoard),
                    )
                }
            }
        }
    }
}

/**
 * E13: lays a label out at its full text height and places it so its baseline is
 * [BarLayout.buttonLine]'s from the top of its target, which is the row's full height and which it
 * takes: the text's box may run past the row (its leading is empty), its ink stays inside. Where the
 * height is unbounded (no row to centre in), the label takes its own height.
 */
private fun Modifier.labelLine(em: Dp, captured: Boolean): Modifier = layout { measurable, constraints ->
    val text = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    if (!constraints.hasBoundedHeight) return@layout layout(text.width, text.height) { text.place(0, 0) }
    val height = constraints.maxHeight
    val baseline = BarLayout.buttonLine(height.toDp(), em, captured).roundToPx()
    layout(text.width, height) { text.place(0, baseline - text[FirstBaseline]) }
}
