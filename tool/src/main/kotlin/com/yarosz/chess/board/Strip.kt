package com.yarosz.chess.board

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToDp
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.lightClickable

/**
 * The strip's measures. `StripFitTest` reads the same values to check that every status fits in
 * [STATUS_MAX_LINES] lines next to its buttons, at the LP3's size, in a stand-in for the LightOS font.
 * Since layout E (E1, E2) the boards have a top bar and an action row (`BoardBars.kt`); the strip is
 * the invite page's and the Play a friend list's, under LightOS top bars that hold the back arrow.
 */
object StripLayout {
    /** The strip is never shorter than this (R1.8). */
    val MIN_HEIGHT: Dp = 48.dp

    /** A long status wraps to a second line instead of losing its end. */
    const val STATUS_MAX_LINES = 2

    /** Space after the status, before the first button. */
    val STATUS_GAP: Dp = 4.dp

    /** Padding on each side of a button's label. */
    val BUTTON_PADDING: Dp = 8.dp

    /** Padding above and below a button's label. */
    val BUTTON_VERTICAL_PADDING: Dp = 8.dp

    /** LightOS `Copy` text: 30 design px with a line height of 1.5 (light-sdk `LightTheme.kt`). */
    const val COPY_DESIGN_PX = 30f
    const val COPY_LINE_HEIGHT = 1.5f

    /** The board's side margin (R1.8): where the status starts. */
    val SIDE: Dp = 24.dp

    /**
     * The Menu mark (N4), LightOS's "more" as the Album tool draws it: three solid squares, 8 by 7 px
     * on the LP3, 28 px apart centre to centre. The boards' top bar draws the same squares (E1).
     */
    val DOT_WIDTH: Dp = (8f / 3).dp
    val DOT_HEIGHT: Dp = (7f / 3).dp
    val DOT_PITCH: Dp = (28f / 3).dp

    /** The mark's ink ends this far from the screen's right edge: the back arrow's ink start, mirrored. */
    val MENU_INK_END: Dp = 16.dp

    /** The mark's target in the strip: this wide at the screen's right edge, the strip's full height. */
    val MENU_TARGET: Dp = 48.dp

    /**
     * How far the last text button ends from the screen's right edge. Beside the Menu mark, its label
     * ends where the mark's target starts, and its right padding lies under the target, which takes
     * those touches. Without the mark, its label's ink ends where the mark's would, 16 dp in (the
     * arrow's mirror), so whatever sits rightmost in a strip or a board's action row (E2) ends there.
     */
    fun buttonsEnd(menu: Boolean): Dp = (if (menu) MENU_TARGET else MENU_INK_END) - BUTTON_PADDING
}

/**
 * One text button in the strip or a board's action row. [description] is its accessibility label
 * (F11). A button that can't act now ([enabled] false) shows lightened and ignores taps (W6: New game
 * and Enter code at the cap). The back arrow and the Menu mark are described the same way, their
 * [label] unused.
 */
data class StripButton(val label: String, val description: String = label, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * The strip at the foot of the invite page and the Play a friend list: a status, up to three text
 * buttons chosen by context (R1.8, contradiction 2), and the Menu mark on the right ([menu], N4).
 * Styled like Reader's footer (DESIGN.md): LightOS `Copy` text, the status in the secondary content
 * colour, buttons in the content colour with LightOS's press-without-ripple. It spans the screen and
 * is always two status lines tall. The boards have `BoardTopBar` and `ActionRow` instead (layout E).
 */
@Composable
fun Strip(
    status: String,
    buttons: List<StripButton>,
    modifier: Modifier = Modifier,
    menu: StripButton? = null,
) {
    // Three by context (contradiction 2), beside the Menu mark.
    require(buttons.size <= 3) { "the strip holds at most 3 text buttons" }
    val statusLines = with(StripLayout) { COPY_DESIGN_PX * COPY_LINE_HEIGHT * STATUS_MAX_LINES }.designVerticalPxToDp()
    val height = maxOf(StripLayout.MIN_HEIGHT, statusLines)
    Box(modifier.fillMaxWidth().height(height)) {
        Row(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(start = StripLayout.SIDE, end = StripLayout.buttonsEnd(menu = menu != null)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText(
                text = status,
                variant = LightTextVariant.Copy,
                lighten = true,
                maxLines = StripLayout.STATUS_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = StripLayout.STATUS_GAP),
            )
            for (button in buttons) {
                LightText(
                    text = button.label,
                    variant = LightTextVariant.Copy,
                    lighten = !button.enabled,
                    maxLines = 1,
                    modifier = Modifier
                        .lightClickable(onClickLabel = button.description, role = Role.Button) { if (button.enabled) button.onClick() }
                        .semantics { contentDescription = button.description }
                        .padding(horizontal = StripLayout.BUTTON_PADDING, vertical = StripLayout.BUTTON_VERTICAL_PADDING),
                )
            }
        }
        // The Row centres every label on the strip's middle line.
        if (menu != null) MenuMark(menu, height / 2, Modifier.align(Alignment.TopEnd))
    }
}

/**
 * N4: the Menu mark, three squares drawn as shapes (a font's full stops differ between the emulator's
 * Roboto and the LP3's Akkurat), centred on the x-height of the strip's `Copy` text in the font in
 * use rather than on its baseline or its line box.
 */
@Composable
private fun MenuMark(menu: StripButton, lineCentre: Dp, modifier: Modifier) {
    val copy = LightThemeTokens.typography.copy
    val style = copy.copy(fontSize = copy.fontSize.value.designVerticalPxToSp(), lineHeight = copy.lineHeight.value.designVerticalPxToSp())
    val measurer = rememberTextMeasurer()
    val resolver = LocalFontFamilyResolver.current
    val density = LocalDensity.current
    // From a label's line centre down to the middle of its x-height: the baseline, less half an "x"'s
    // ink, both measured in this font at this size.
    val xHeightCentre = remember(style, density) {
        val line = measurer.measure("x", style)
        val paint = Paint().apply {
            typeface = resolver.resolve(style.fontFamily, style.fontWeight ?: FontWeight.Normal).value as? Typeface
            textSize = with(density) { style.fontSize.toPx() }
        }
        val ink = Rect().also { paint.getTextBounds("x", 0, 1, it) }
        with(density) { (line.firstBaseline + ink.top / 2f - line.size.height / 2f).toDp() }
    }
    val color = LightThemeTokens.colors.content
    Box(
        modifier
            .width(StripLayout.MENU_TARGET)
            .fillMaxHeight()
            .lightClickable(onClickLabel = menu.description, role = Role.Button, onClick = menu.onClick)
            .semantics { contentDescription = menu.description },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = StripLayout.DOT_WIDTH.toPx()
            val h = StripLayout.DOT_HEIGHT.toPx()
            val pitch = StripLayout.DOT_PITCH.toPx()
            val centreY = (lineCentre + xHeightCentre).toPx()
            // The box ends at the screen's right edge.
            val lastLeft = size.width - StripLayout.MENU_INK_END.toPx() - w
            for (i in 0 until 3) {
                drawRect(color, topLeft = Offset(lastLeft - (2 - i) * pitch, centreY - h / 2), size = Size(w, h))
            }
        }
    }
}
