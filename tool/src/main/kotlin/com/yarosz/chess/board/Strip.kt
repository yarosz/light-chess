package com.yarosz.chess.board

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The strip's measures that outlived it. Layout E (E1, E2) gave the boards a top bar and an action row
 * (`BoardBars.kt`); LD2 and LD3 moved the pages' text buttons into LightOS's bottom bar (`ActionBar`),
 * the invite page's last of them, so no strip is drawn. `StripFitTest` reads these values: the Menu
 * mark's, the action row's button padding, the status lines a Result may take, and the gap its
 * calibration against LP3 screencaps (the v1 and v2 strips) measured with.
 */
object StripLayout {
    /** A long status (a Result, R4.16) may take a second line. */
    const val STATUS_MAX_LINES = 2

    /** Space after the status, before the first button, in the calibration screencaps' strip. */
    val STATUS_GAP: Dp = 4.dp

    /** Padding on each side of a button's label in a board's action row. */
    val BUTTON_PADDING: Dp = 8.dp

    /** LightOS `Copy` text: 30 design px (light-sdk `LightTheme.kt`). */
    const val COPY_DESIGN_PX = 30f

    /**
     * The Menu mark (N4), LightOS's "more" as the Album tool draws it: three solid squares, 8 by 7 px
     * on the LP3, 28 px apart centre to centre, drawn in the top bar (E1).
     */
    val DOT_WIDTH: Dp = (8f / 3).dp
    val DOT_HEIGHT: Dp = (7f / 3).dp
    val DOT_PITCH: Dp = (28f / 3).dp

    /** The mark's ink ends this far from the screen's right edge: the back arrow's ink start, mirrored. */
    val MENU_INK_END: Dp = 16.dp
}

/**
 * One text button in a board's action row or LightOS's bottom bar (`ActionBar`). [description] is its
 * accessibility label (F11). A button that can't act now ([enabled] false) shows lightened and ignores
 * taps (W6: at the cap of five). The back arrow and the Menu mark are described the same way, their
 * [label] unused.
 */
data class StripButton(val label: String, val description: String = label, val enabled: Boolean = true, val onClick: () -> Unit)
