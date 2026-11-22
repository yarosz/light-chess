package com.yarosz.chess

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Light's design note (LD1, LD2): Home's rows at LightOS `Heading` size, and the pages' text buttons in
 * LightOS's bottom bar, all caps. Measured with [AkkuratProxy] at the LP3's 360 dp, as `StripFitTest`
 * measures the strips.
 */
class ActionBarFitTest {

    private val width = 360f
    private val unit = width / 27f

    /** LightOS `Button`: 30 design px, 0.15 em between letters (light-sdk `LightTheme.kt`). */
    private fun buttonWidth(label: String): Float =
        AkkuratProxy.width(label, BUTTON_PX) + label.length * AkkuratProxy.size(BUTTON_PX) * 0.15f

    @Test
    fun theBarIsLightOsBottomBar() {
        val bar = File("../light-sdk/sdk/ui/src/main/kotlin/com/thelightphone/sdk/ui/LightBottomBar.kt").readText()
        fun constant(name: String) = Regex("""const val $name = ([0-9.]+)f""").find(bar)?.groupValues?.get(1)?.toFloat()
        assertEquals(constant("BOTTOMBAR_HEIGHT_UNITS"), ActionBarLayout.HEIGHT_UNITS)
        assertEquals(constant("HORIZONTAL_PADDING_MULTI_UNITS"), ActionBarLayout.PADDING_UNITS)
        assertTrue("LightTextVariant.Button" in bar, "the SDK's bar text is no longer Button")
    }

    @Test
    fun barLabelsAreAllCaps() {
        assertEquals("START GAME", ActionBarLayout.label(UiCopy.START_GAME))
        assertEquals("NEW GAME", ActionBarLayout.label(UiCopy.NEW_GAME))
        assertEquals("JOIN GAME", ActionBarLayout.label(UiCopy.JOIN_GAME))
        assertEquals("TAP AGAIN TO REPLACE", ActionBarLayout.label(UiCopy.REPLACE_CONFIRM))
    }

    /** One button is centred across the whole bar: New game's Start game and its second tap's prompt. */
    @Test
    fun aLoneButtonFitsTheBar() {
        for (label in listOf(UiCopy.START_GAME, UiCopy.REPLACE_CONFIRM)) {
            val w = buttonWidth(ActionBarLayout.label(label))
            assertTrue(w <= width - 2 * 16f, "\"$label\" is $w dp")
        }
    }

    /** Two buttons each hold half the bar, inside its 2-unit padding: the Play a friend list's. */
    @Test
    fun twoButtonsEachFitHalfTheBar() {
        val half = (width - 2 * ActionBarLayout.PADDING_UNITS * unit) / 2
        for (label in listOf(UiCopy.NEW_GAME, UiCopy.JOIN_GAME)) {
            val w = buttonWidth(ActionBarLayout.label(label))
            assertTrue(w <= half, "\"$label\" is $w dp of $half")
        }
    }

    /**
     * LD1: every Home row holds one `Heading` line, but Play a friend's with "Your move: 5", which
     * wraps to a second.
     */
    @Test
    fun homeRowsHoldOneHeadingLine() {
        val room = width - 2 * 24f
        for (row in HomeRows.of(yourMove = 5)) {
            val w = AkkuratProxy.width(row.text, HEADING_PX)
            val lines = if (row.entry == HomeEntry.PLAY_FRIEND) 2 else 1
            assertTrue(w <= room * lines, "\"${row.text}\" is $w dp of $room × $lines")
        }
        assertTrue(AkkuratProxy.width(UiCopy.PLAY_FRIEND, HEADING_PX) <= room)
    }

    private companion object {
        const val BUTTON_PX = 30f
        const val HEADING_PX = 38f
    }
}
