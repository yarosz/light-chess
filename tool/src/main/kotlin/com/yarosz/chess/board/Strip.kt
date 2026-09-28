package com.yarosz.chess.board

import androidx.compose.foundation.layout.Arrangement
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
import com.thelightphone.sdk.ui.lightClickable

/** The strip's height under the board (R1.8). */
val STRIP_HEIGHT: Dp = 48.dp

/** One text button in the strip. [description] is its accessibility label (F11). */
data class StripButton(val label: String, val description: String = label, val onClick: () -> Unit)

/**
 * The 48 dp strip under the board: a status line on the left and up to three text buttons on the
 * right, chosen by context (R1.8, contradiction 2). Styled like Reader's footer (DESIGN.md): LightOS
 * `Copy` text, the status in the secondary content colour, buttons in the content colour with
 * LightOS's press-without-ripple.
 */
@Composable
fun Strip(status: String, buttons: List<StripButton>, modifier: Modifier = Modifier) {
    require(buttons.size <= 3) { "the strip holds at most 3 buttons" }
    Row(
        modifier.fillMaxWidth().height(STRIP_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = status,
            variant = LightTextVariant.Copy,
            lighten = true,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        for (button in buttons) {
            LightText(
                text = button.label,
                variant = LightTextVariant.Copy,
                maxLines = 1,
                modifier = Modifier
                    .lightClickable(onClickLabel = button.description, role = Role.Button, onClick = button.onClick)
                    .semantics { contentDescription = button.description }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
    }
}
