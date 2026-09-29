package com.yarosz.chess.board

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

    /** LightOS `Copy` text: 30 design px with a line height of 1.5 (light-sdk `LightTheme.kt`). */
    const val COPY_DESIGN_PX = 30f
    const val COPY_LINE_HEIGHT = 1.5f
}

/**
 * One text button in the strip. [description] is its accessibility label (F11). A button that can't
 * act now ([enabled] false) shows lightened and ignores taps (W6: New game and Enter code at the cap).
 */
data class StripButton(val label: String, val description: String = label, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * The strip under the board: a status on the left and up to three text buttons on the right, chosen
 * by context (R1.8, contradiction 2). Styled like Reader's footer (DESIGN.md): LightOS `Copy` text,
 * the status in the secondary content colour, buttons in the content colour with LightOS's
 * press-without-ripple. It is always two status lines tall, so the board never moves when a status
 * wraps.
 *
 * In a Game, [captured] puts the captured-pieces row in the strip's top band (P3). Once it shows,
 * the status and buttons centre in the part of the strip below it, and a status's two lines are set
 * closer so that a two-line Result still fits there; the strip keeps its size and place. Until
 * something is captured, and always in a Puzzle, the strip is as it was.
 */
@Composable
fun Strip(status: String, buttons: List<StripButton>, modifier: Modifier = Modifier, captured: CapturedRowState? = null) {
    // Three by context (contradiction 2); four only for a chosen Correspondence Move (SAN, Send, Undo, Menu).
    require(buttons.size <= 4) { "the strip holds at most 4 buttons" }
    val statusLines = with(StripLayout) { COPY_DESIGN_PX * COPY_LINE_HEIGHT * STATUS_MAX_LINES }.designVerticalPxToDp()
    val height = maxOf(StripLayout.MIN_HEIGHT, statusLines)
    val row = captured?.takeIf { it.shown }
    BoxWithConstraints(modifier.fillMaxWidth().height(height)) {
        if (row != null) CapturedRow(row, maxWidth, Modifier.align(Alignment.TopStart))
        val top = if (row != null) CapturedRowLayout.BOTTOM.dp else 0.dp
        Row(
            Modifier.fillMaxWidth().fillMaxHeight().padding(top = top),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val statusModifier = Modifier.weight(1f).padding(end = StripLayout.STATUS_GAP)
            if (row == null) {
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
                        .padding(horizontal = StripLayout.BUTTON_PADDING, vertical = 8.dp),
                )
            }
        }
    }
}
