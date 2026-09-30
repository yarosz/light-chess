package com.yarosz.chess.board

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
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
import com.thelightphone.sdk.ui.LightGrid
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToDp
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.lightClickable

/**
 * The strip's measures. `StripFitTest` reads the same values to check that every status fits in
 * [STATUS_MAX_LINES] lines next to its buttons, at the LP3's size, in a stand-in for the LightOS font.
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

    /**
     * The same while the captured-pieces row shows (P3): a label's line (Copy, 45 design px) plus this
     * above and below fits the 38 dp left under the row, so no label is clipped; still a 37 dp target.
     */
    val BUTTON_VERTICAL_PADDING_BELOW_ROW: Dp = 4.dp

    /**
     * Where the status and buttons start, from the strip's top (P3, the owner's choice): below the
     * captured-pieces row's band on every Game board ([captured] non-null), captures or not, so the
     * text never moves when the first piece is taken or when Review steps across it; at the top in a
     * Puzzle, as before.
     */
    fun textTop(captured: CapturedRowState?): Dp = if (captured != null) CapturedRowLayout.BOTTOM.dp else 0.dp

    /** The buttons' padding above and below: the smaller one wherever the row's band is reserved. */
    fun buttonVerticalPadding(captured: CapturedRowState?): Dp =
        if (captured != null) BUTTON_VERTICAL_PADDING_BELOW_ROW else BUTTON_VERTICAL_PADDING

    /** LightOS `Copy` text: 30 design px with a line height of 1.5 (light-sdk `LightTheme.kt`). */
    const val COPY_DESIGN_PX = 30f
    const val COPY_LINE_HEIGHT = 1.5f

    /** The board's side margin (R1.8): where the status starts without the back arrow. */
    val SIDE: Dp = 24.dp

    /**
     * The back arrow (N3), where LightOS's top bar draws it: one grid unit in from the screen's edge (a
     * grid unit is a 27th of the screen's width, light-sdk `LightGrid`), two grid units square. Its
     * ink runs from 16 to 27 dp on the LP3 (pixels 48 to 82 of 1080).
     */
    const val BACK_START_UNITS = 1f
    const val BACK_SIZE_UNITS = 2f

    /** With the arrow, the status starts this many grid units in: 37 dp (112 px) on the LP3. */
    const val STATUS_START_UNITS = 2.8f

    /**
     * The Menu mark (N4), LightOS's "more" as the Album tool draws it: three solid squares, 8 by 7 px
     * on the LP3, 28 px apart centre to centre.
     */
    val DOT_WIDTH: Dp = (8f / 3).dp
    val DOT_HEIGHT: Dp = (7f / 3).dp
    val DOT_PITCH: Dp = (28f / 3).dp

    /** The mark's ink ends this far from the screen's right edge: the arrow's ink start, mirrored. */
    val MENU_INK_END: Dp = 16.dp

    /** The mark's target: this wide at the screen's right edge, the strip's full height. */
    val MENU_TARGET: Dp = 48.dp

    /** The status's left edge on a screen [screenWidth] wide, with or without the back arrow. */
    fun statusStart(screenWidth: Dp, back: Boolean): Dp =
        if (back) screenWidth / LightGrid.WIDTH * STATUS_START_UNITS else SIDE

    /**
     * How far the last text button ends from the screen's right edge. Beside the Menu mark, its label
     * ends where the mark's target starts, and its right padding lies under the target, which takes
     * those touches: "Rematch?" beside Accept and Decline needs the 8 dp (N9). Without the mark, its
     * label's ink ends where the mark's would, 16 dp in (the arrow's mirror), so whatever sits
     * rightmost in a strip ends at the same place.
     */
    fun buttonsEnd(menu: Boolean): Dp = (if (menu) MENU_TARGET else MENU_INK_END) - BUTTON_PADDING
}

/**
 * One text button in the strip. [description] is its accessibility label (F11). A button that can't
 * act now ([enabled] false) shows lightened and ignores taps (W6: New game and Enter code at the cap).
 * The back arrow and the Menu mark are described the same way, their [label] unused.
 */
data class StripButton(val label: String, val description: String = label, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * The strip under the board: the back arrow on the left ([back], N3), a status, up to three text
 * buttons chosen by context (R1.8, contradiction 2), and the Menu mark on the right ([menu], N4).
 * Styled like Reader's footer (DESIGN.md): LightOS `Copy` text, the status in the secondary content
 * colour, buttons in the content colour with LightOS's press-without-ripple. It spans the screen and
 * is always two status lines tall, so the board never moves when a status wraps.
 *
 * On a Game board, [captured] reserves the strip's top band for the captured-pieces row (P3): the
 * status and buttons always centre in the part of the strip below it, from the first Position on, and
 * a status's two lines are set closer so that a two-line Result still fits there; the pieces appear
 * in the band once something is taken, aligned with the board. The strip keeps its size and place. A
 * Puzzle passes no [captured].
 */
@Composable
fun Strip(
    status: String,
    buttons: List<StripButton>,
    modifier: Modifier = Modifier,
    captured: CapturedRowState? = null,
    back: StripButton? = null,
    menu: StripButton? = null,
) {
    // Three by context (contradiction 2), beside the arrow and the Menu mark.
    require(buttons.size <= 3) { "the strip holds at most 3 text buttons" }
    val statusLines = with(StripLayout) { COPY_DESIGN_PX * COPY_LINE_HEIGHT * STATUS_MAX_LINES }.designVerticalPxToDp()
    val height = maxOf(StripLayout.MIN_HEIGHT, statusLines)
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    BoxWithConstraints(modifier.fillMaxWidth().height(height)) {
        if (captured != null && captured.shown) {
            CapturedRow(captured, POSITION_VIEW_SIZE, Modifier.align(Alignment.TopCenter).width(POSITION_VIEW_SIZE))
        }
        val top = StripLayout.textTop(captured)
        val buttonPadding = StripLayout.buttonVerticalPadding(captured)
        val statusStart = StripLayout.statusStart(screenWidth, back = back != null)
        Row(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(top = top, start = statusStart, end = StripLayout.buttonsEnd(menu = menu != null)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val statusModifier = Modifier.weight(1f).padding(end = StripLayout.STATUS_GAP)
            if (captured == null) {
                LightText(
                    text = status,
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    maxLines = StripLayout.STATUS_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = statusModifier,
                )
            } else {
                // LightText has no line height of its own: the same Copy style, its lines set to fill
                // the room below the row exactly.
                val copy = LightThemeTokens.typography.copy
                val lineHeight = with(LocalDensity.current) { ((height - top) / StripLayout.STATUS_MAX_LINES).toSp() }
                BasicText(
                    text = status,
                    style = copy.copy(
                        fontSize = copy.fontSize.value.designVerticalPxToSp(),
                        lineHeight = lineHeight,
                        color = LightThemeTokens.colors.contentSecondary,
                    ),
                    maxLines = StripLayout.STATUS_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    // Two such lines fill the room exactly: unbounded, so rounding never ellipsizes the second.
                    modifier = statusModifier.wrapContentHeight(unbounded = true),
                )
            }
            for (button in buttons) {
                LightText(
                    text = button.label,
                    variant = LightTextVariant.Copy,
                    lighten = !button.enabled,
                    maxLines = 1,
                    modifier = Modifier
                        .lightClickable(onClickLabel = button.description, role = Role.Button) { if (button.enabled) button.onClick() }
                        .semantics { contentDescription = button.description }
                        .padding(horizontal = StripLayout.BUTTON_PADDING, vertical = buttonPadding),
                )
            }
        }
        // The text line's centre, from the strip's top: the Row centres every label on it.
        val lineCentre = top + (height - top) / 2
        if (back != null) BackArrow(back, statusStart, lineCentre, screenWidth, Modifier.align(Alignment.TopStart))
        if (menu != null) MenuMark(menu, lineCentre, Modifier.align(Alignment.TopEnd))
    }
}

/**
 * N3: LightOS's back arrow at its top-bar place, centred on the strip's text line. Its target runs
 * the strip's height, from the screen's edge to the status.
 */
@Composable
private fun BackArrow(back: StripButton, width: Dp, lineCentre: Dp, screenWidth: Dp, modifier: Modifier) {
    val unit = screenWidth / LightGrid.WIDTH
    val size = unit * StripLayout.BACK_SIZE_UNITS
    Box(
        modifier
            .width(width)
            .fillMaxHeight()
            .lightClickable(onClickLabel = back.description, role = Role.Button, onClick = back.onClick)
            .semantics { contentDescription = back.description },
    ) {
        LightIcon(
            icon = LightIcons.BACK,
            size = StripLayout.BACK_SIZE_UNITS,
            contentDescription = null,
            modifier = Modifier.offset(x = unit * StripLayout.BACK_START_UNITS, y = lineCentre - size / 2),
        )
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
