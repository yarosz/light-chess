package com.yarosz.chess.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReviewTest {

    private val live = Review()

    @Test
    fun wheelKeysMapFromLightKeycodes() {
        assertEquals(Wheel.BACK, Wheel.of(317))
        assertEquals(Wheel.FORWARD, Wheel.of(318))
        assertEquals(Wheel.CLICK, Wheel.of(319))
        assertNull(Wheel.of(24)) // volume up stays with LightOS
    }

    @Test
    fun backEntersReviewOnePlyBeforeTheLatest() {
        assertEquals(Review(4), live.wheel(Wheel.BACK, latestPly = 5))
    }

    @Test
    fun backStopsAtTheStartButStillTakesTheKey() {
        assertEquals(Review(0), Review(1).wheel(Wheel.BACK, 5))
        assertEquals(Review(0), Review(0).wheel(Wheel.BACK, 5))
    }

    @Test
    fun forwardStepsAndLeavesReviewAtTheLatest() {
        assertEquals(Review(3), Review(2).wheel(Wheel.FORWARD, 5))
        assertEquals(live, Review(4).wheel(Wheel.FORWARD, 5))
    }

    @Test
    fun clickReturnsToTheLatestPosition() {
        assertEquals(live, Review(1).wheel(Wheel.CLICK, 5))
    }

    @Test
    fun outsideReviewForwardAndClickAreLeftToLightOS() {
        assertNull(live.wheel(Wheel.FORWARD, 5))
        assertNull(live.wheel(Wheel.CLICK, 5))
    }

    @Test
    fun withNoMovesPlayedBackIsLeftToLightOS() {
        assertNull(live.wheel(Wheel.BACK, 0))
    }
}
