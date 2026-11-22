package com.yarosz.chess

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Light's design note (LD1-LD3): Home's rows at LightOS `Heading` size, and the pages' text buttons in
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
        assertEquals(ActionBarLayout.HEIGHT_UNITS, constant("BOTTOMBAR_HEIGHT_UNITS"))
        assertEquals(ActionBarLayout.PADDING_UNITS, constant("HORIZONTAL_PADDING_MULTI_UNITS"))
        assertTrue("LightTextVariant.Button" in bar, "the SDK's bar text is no longer Button")
    }

    @Test
    fun barLabelsAreAllCaps() {
        assertEquals("START GAME", ActionBarLayout.label(UiCopy.START_GAME))
        assertEquals("NEW GAME", ActionBarLayout.label(UiCopy.NEW_GAME))
        assertEquals("JOIN GAME", ActionBarLayout.label(UiCopy.JOIN_GAME))
        assertEquals("CREATE CODE", ActionBarLayout.label(UiCopy.CREATE_CODE))
        assertEquals("SENDING", ActionBarLayout.label(UiCopy.SENDING))
        assertEquals("CANCEL", ActionBarLayout.label(UiCopy.CANCEL))
        assertEquals("RETRY", ActionBarLayout.label(UiCopy.RETRY))
        assertEquals("JOIN", ActionBarLayout.label(UiCopy.JOIN))
    }

    /**
     * One button is centred across the whole bar (the SDK pads it by 0), kept 16 dp clear of each edge,
     * the back arrow's inset: New game's Start game (LD2); the friend New
     * game's Create code and Sending, the invite page's Cancel and Retry, and Join game's Join (LD3).
     */
    @Test
    fun aLoneButtonFitsTheBar() {
        val lone = listOf(UiCopy.START_GAME, UiCopy.CREATE_CODE, UiCopy.SENDING, UiCopy.CANCEL, UiCopy.RETRY, UiCopy.JOIN)
        for (label in lone) {
            val w = buttonWidth(ActionBarLayout.label(label))
            assertTrue(w <= width - 2 * EDGE, "\"$label\" is $w dp")
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

    /** LD1: at `Heading` size every Home row holds one line, "Play a friend*" included. */
    @Test
    fun homeRowsHoldOneHeadingLine() {
        val room = width - 2 * 24f
        for (row in HomeRows.of(yourMove = 5)) {
            val w = AkkuratProxy.width(row.text, HEADING_PX)
            assertTrue(w <= room, "\"${row.text}\" is $w dp of $room")
        }
    }

    private companion object {
        const val BUTTON_PX = 30f
        const val HEADING_PX = 38f
        const val EDGE = 16f
    }
}
