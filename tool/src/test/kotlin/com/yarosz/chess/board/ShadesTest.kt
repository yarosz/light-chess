package com.yarosz.chess.board

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The contrast targets of D10, as unit tests on the pixel constants. DESIGN.md states the rules. */
class ShadesTest {

    private fun apart(a: Int, b: Int) = abs(a - b)

    @Test
    fun squaresAreAtLeast60LevelsApart() {
        assertTrue(apart(Shades.LIGHT_SQUARE, Shades.DARK_SQUARE) >= Shades.SQUARE_CONTRAST)
        assertEquals(60, Shades.SQUARE_CONTRAST)
    }

    @Test
    fun lastMoveShadeIsAtLeast25FromBothSquares() {
        assertTrue(apart(Shades.LAST_MOVE, Shades.LIGHT_SQUARE) >= Shades.LAST_MOVE_CONTRAST)
        assertTrue(apart(Shades.LAST_MOVE, Shades.DARK_SQUARE) >= Shades.LAST_MOVE_CONTRAST)
        assertEquals(25, Shades.LAST_MOVE_CONTRAST)
    }

    /**
     * The readability rule for markers: a marker's level is at least [Shades.MARKER_CONTRAST] (60, the
     * same floor as the two squares) from every ground it can be drawn on (light, dark and last-move
     * squares), and its strokes are at least 2 dp thick, its dots at least 8 dp across.
     */
    @Test
    fun markersAreReadableOnEveryGround() {
        for (ground in Shades.GROUNDS) {
            assertTrue(apart(Shades.MARKER, ground) >= Shades.MARKER_CONTRAST, "marker on $ground")
        }
        for (stroke in Marks.STROKES) assertTrue(stroke >= Marks.MIN_STROKE, "stroke $stroke")
        assertTrue(2 * Marks.DOT_RADIUS * 39 >= 8f, "dot diameter")
    }

    @Test
    fun theCheckRingIsHeavierThanACaptureRing() {
        // The two rings never share a square (a king in check can't be captured), but they should
        // still read as different marks: at least 1 dp heavier and visibly smaller.
        assertTrue((Marks.CHECK_RING_STROKE - Marks.CAPTURE_RING_STROKE) * 39 >= 1f)
        assertTrue((Marks.CAPTURE_RING_RADIUS - Marks.CHECK_RING_RADIUS) * 39 >= 2f)
    }

    @Test
    fun coordinatesAreReadableOnTheirSquareAndOnTheLastMoveShade() {
        for ((text, square) in listOf(
            Shades.COORDINATE_ON_LIGHT to Shades.LIGHT_SQUARE,
            Shades.COORDINATE_ON_DARK to Shades.DARK_SQUARE,
        )) {
            assertTrue(apart(text, square) >= Shades.MARKER_CONTRAST, "coordinate $text on $square")
            assertTrue(apart(text, Shades.LAST_MOVE) >= Shades.MARKER_CONTRAST, "coordinate $text on last move")
        }
    }

    /**
     * P3: a captured black piece's gray body stands out from the strip's black ground and from a
     * captured white piece's body, by the squares' floor, so the two ends read as two Sides.
     */
    @Test
    fun capturedBlackPiecesStandOutFromTheGroundAndFromWhite() {
        assertTrue(apart(Shades.CAPTURED_BLACK_BODY, 0x00) >= Shades.SQUARE_CONTRAST)
        assertTrue(apart(Shades.CAPTURED_BLACK_BODY, 0xFF) >= Shades.SQUARE_CONTRAST)
        assertEquals(0x96, Shades.CAPTURED_BLACK_BODY, "the owner's mock-up (#969696), and scripts/build-pieces.py")
    }

    @Test
    fun thePickerStandsOutFromTheDimmedBoard() {
        fun dimmed(level: Int) = level * (1 - Shades.DIM_ALPHA) + Shades.MARKER * Shades.DIM_ALPHA
        for (ground in Shades.GROUNDS) {
            assertTrue(Shades.PICKER - dimmed(ground) >= Shades.SQUARE_CONTRAST, "picker over dimmed $ground")
        }
    }

    @Test
    fun everyShadeIsAGray() {
        for (level in listOf(0, Shades.MARKER, Shades.DARK_SQUARE, Shades.LAST_MOVE, Shades.LIGHT_SQUARE, 255)) {
            val argb = Shades.argb(level)
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            assertEquals(listOf(level.toLong(), level.toLong(), level.toLong()), listOf(r, g, b))
            assertEquals(0xFFL, argb ushr 24)
        }
    }
}
