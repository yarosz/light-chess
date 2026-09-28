package com.yarosz.chess.board

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.designVerticalPxToDp
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

/** One text button in the strip. [description] is its accessibility label (F11). */
data class StripButton(val label: String, val description: String = label, val onClick: () -> Unit)

/**
 * The strip under the board: a status on the left and up to three text buttons on the right, chosen
 * by context (R1.8, contradiction 2). Styled like Reader's footer (DESIGN.md): LightOS `Copy` text,
 * the status in the secondary content colour, buttons in the content colour with LightOS's
 * press-without-ripple. It is always two status lines tall, so the board never moves when a status
 * wraps.
 */
@Composable
fun Strip(status: String, buttons: List<StripButton>, modifier: Modifier = Modifier) {
    require(buttons.size <= 3) { "the strip holds at most 3 buttons" }
    val statusLines = with(StripLayout) { COPY_DESIGN_PX * COPY_LINE_HEIGHT * STATUS_MAX_LINES }.designVerticalPxToDp()
    Row(
        modifier.fillMaxWidth().height(maxOf(StripLayout.MIN_HEIGHT, statusLines)),
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
                maxLines = 1,
                modifier = Modifier
                    .lightClickable(onClickLabel = button.description, role = Role.Button, onClick = button.onClick)
                    .semantics { contentDescription = button.description }
                    .padding(horizontal = StripLayout.BUTTON_PADDING, vertical = 8.dp),
            )
        }
    }
}
