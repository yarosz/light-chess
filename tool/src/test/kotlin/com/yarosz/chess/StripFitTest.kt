package com.yarosz.chess

import com.yarosz.chess.board.POSITION_VIEW_SIZE
import com.yarosz.chess.board.StripLayout
import com.yarosz.chess.puzzles.AttemptState
import com.yarosz.chess.rules.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every strip the puzzle screen can show fits the LP3: each button's label on one line, and the
 * status in at most [StripLayout.STATUS_MAX_LINES] lines in the room the buttons leave. The cases
 * follow DESIGN.md's "Buttons by context" table; a new strip state or string belongs here too.
 *
 * The LightOS font (Akkurat) can't ship with the repo, so text is measured with [AkkuratProxy]:
 * Helvetica's advance widths, scaled up until no word is narrower than the ink of the same word on LP3
 * screencaps (see [proxyIsNeverNarrowerThanTheLp3]).
 */
class StripFitTest {

    private val menu = UiCopy.MENU
    private val solving = listOf(UiCopy.HINT, UiCopy.SOLUTION, menu)
    private val result = listOf(UiCopy.NEXT, menu)

    /** (status, buttons) for every state in DESIGN.md "Buttons by context", with the widest numbers. */
    private val strips: List<Pair<String, List<String>>> = buildList {
        for (side in Side.entries) add(UiCopy.toMove(side) to solving)
        add(UiCopy.TRY_AGAIN to solving)
        add(UiCopy.CORRECT to solving)
        add(UiCopy.FIRST_PUZZLE to listOf(menu))
        add(UiCopy.SOLUTION_PLAYING to listOf(menu))
        for (state in AttemptState.entries) for (rated in listOf(true, false)) for (shown in listOf(true, false)) {
            for (delta in listOf(-999, 999)) add(UiCopy.result(state, rated, delta, shown) to result)
        }
        add(UiCopy.review(99, 99) to listOf(UiCopy.LATEST, UiCopy.NEXT, menu))
        add(UiCopy.review(99, 99) to listOf(UiCopy.LATEST, menu))
        add(UiCopy.PACK_FINISHED to listOf(menu))
    }

    /** The room left for the status, in dp, next to [buttons]. */
    private fun statusRoom(buttons: List<String>): Float =
        POSITION_VIEW_SIZE.value - StripLayout.STATUS_GAP.value -
            buttons.sumOf { (AkkuratProxy.width(it) + 2 * StripLayout.BUTTON_PADDING.value).toDouble() }.toFloat()

    /** Greedy word wrap, as Android breaks a line without hyphens. */
    private fun wrap(text: String, room: Float): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        for (word in text.split(' ')) {
            val longer = if (line.isEmpty()) word else "$line $word"
            if (line.isEmpty() || AkkuratProxy.width(longer) <= room) line = longer else {
                lines += line
                line = word
            }
        }
        return lines + line
    }

    @Test
    fun everyStatusFitsNextToItsButtons() {
        for ((status, buttons) in strips) {
            val room = statusRoom(buttons)
            val lines = wrap(status, room)
            assertTrue(lines.size <= StripLayout.STATUS_MAX_LINES, "\"$status\" next to $buttons needs ${lines.size} lines")
            for (line in lines) {
                assertTrue(AkkuratProxy.width(line) <= room, "\"$line\" (${AkkuratProxy.width(line)} dp) in $room dp next to $buttons")
            }
        }
    }

    @Test
    fun theStripCoversEveryStripString() {
        val used = strips.map { it.first }.toSet() + strips.flatMap { it.second }
        for (copy in listOf(
            UiCopy.WHITE_TO_MOVE, UiCopy.BLACK_TO_MOVE, UiCopy.LATEST, UiCopy.FIRST_PUZZLE, UiCopy.TRY_AGAIN,
            UiCopy.CORRECT, UiCopy.SOLUTION_PLAYING, UiCopy.HINT, UiCopy.SOLUTION, UiCopy.NEXT, UiCopy.MENU,
            UiCopy.UNRATED_SOLVED, UiCopy.UNRATED_HINTED, UiCopy.UNRATED_FAILED, UiCopy.PACK_FINISHED,
        )) assertTrue(copy in used, "\"$copy\" is not checked")
    }

    /** The bug seen on the LP3: on one line, the first Puzzle's status lost its end next to Menu. */
    @Test
    fun oneLineWasTooShortForTheFirstPuzzle() {
        assertEquals(2, wrap(UiCopy.FIRST_PUZZLE, statusRoom(listOf(menu))).size)
        assertFalse(AkkuratProxy.width(UiCopy.FIRST_PUZZLE) <= statusRoom(listOf(menu)))
    }

    /**
     * Ink widths (px / 3 = dp) of strip text on LP3 screencaps, LightOS 582 at 480 dpi. The proxy's
     * advance width (ink plus side bearings) must never be narrower. Akkurat ran up to 12% wider than
     * Helvetica on these ("Restart"), hence the proxy's scale.
     */
    @Test
    fun proxyIsNeverNarrowerThanTheLp3() {
        val lp3InkPx = mapOf(
            "Menu" to 148, "Latest" to 173, "Restart" to 206, "White to move" to 399,
            "Review · 0 of 1" to 382, "Tap a piece, then a squ…" to 694,
        )
        for ((text, px) in lp3InkPx) {
            assertTrue(AkkuratProxy.width(text) >= px / 3f, "\"$text\": proxy ${AkkuratProxy.width(text)} dp, LP3 ${px / 3f} dp")
        }
    }
}

/** A conservative stand-in for Akkurat at LightOS `Copy` size on the LP3. */
object AkkuratProxy {
    /** The LP3's app area is 1168 px tall at 480 dpi: Configuration.screenHeightDp = 389. */
    private const val LP3_SCREEN_HEIGHT_DP = 389f

    /** LightOS scales design px by screenHeightDp / 600 (light-sdk `designVerticalPxToSp`); sp = dp at font scale 1. */
    private const val FONT_SIZE_DP = StripLayout.COPY_DESIGN_PX * LP3_SCREEN_HEIGHT_DP / 600f

    /** Akkurat against Helvetica, with margin over the widest word measured (12%). */
    private const val SCALE = 1.15f

    /** Helvetica advance widths, in 1/1000 em (the Adobe core font metrics). */
    private val WIDTHS: Map<Char, Int> = buildMap {
        val upper = intArrayOf(
            667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833,
            722, 778, 667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611,
        )
        val lower = intArrayOf(
            556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833,
            556, 556, 556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500,
        )
        for (i in 0 until 26) {
            put('A' + i, upper[i])
            put('a' + i, lower[i])
        }
        for (d in '0'..'9') put(d, 556)
        put(' ', 278); put(',', 278); put('.', 278); put('\'', 191); put('’', 222); put('?', 556)
        put('·', 278); put('+', 584); put('−', 584); put('-', 333); put('…', 1000)
    }

    /** The advance width of [text] in dp. A character without a width fails loudly: add it to the table. */
    fun width(text: String): Float =
        text.sumOf { c -> requireNotNull(WIDTHS[c]) { "no width for '$c' in \"$text\"" } } / 1000f * FONT_SIZE_DP * SCALE
}
