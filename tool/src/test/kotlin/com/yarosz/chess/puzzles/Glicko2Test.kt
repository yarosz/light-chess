package com.yarosz.chess.puzzles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Glicko2Test {

    /** Glickman, "Example of the Glicko-2 system" (2012), section "Example calculation", τ = 0.5. */
    @Test
    fun `the published worked example`() {
        val player = Glicko(1500.0, 200.0, 0.06)
        val games = listOf(
            Glicko2.Game(1400.0, 30.0, 1.0),
            Glicko2.Game(1550.0, 100.0, 0.0),
            Glicko2.Game(1700.0, 300.0, 0.0),
        )
        val after = Glicko2.update(player, games, tau = 0.5, floor = 0.0, ceiling = Double.POSITIVE_INFINITY)
        assertEquals(1464.06, after.rating, 0.01)
        assertEquals(151.52, after.deviation, 0.01)
        assertEquals(0.05999, after.volatility, 0.00001)
    }

    @Test
    fun `a Puzzle whose own RD is larger moves the Player Rating less`() {
        val player = Glicko(1500.0, 120.0)
        val sure = Glicko2.update(player, listOf(Glicko2.Game(1500.0, 45.0, 1.0)))
        val unsure = Glicko2.update(player, listOf(Glicko2.Game(1500.0, 300.0, 1.0)))
        assertTrue(sure.rating - 1500 > unsure.rating - 1500, "sure ${sure.rating}, unsure ${unsure.rating}")
        assertTrue(unsure.rating > 1500)
        val sureLoss = Glicko2.update(player, listOf(Glicko2.Game(1500.0, 45.0, 0.0)))
        val unsureLoss = Glicko2.update(player, listOf(Glicko2.Game(1500.0, 300.0, 0.0)))
        assertTrue(1500 - sureLoss.rating > 1500 - unsureLoss.rating)
    }

    @Test
    fun `a new Player Rating starts at 1500, RD 500, volatility 0,09 and is provisional`() {
        val start = Glicko.start()
        assertEquals(Glicko(1500.0, 500.0, 0.09), start)
        assertEquals("(1500)", start.text)
        assertEquals("1523", Glicko(1523.4, 75.0).text, "RD 75 is settled")
        assertEquals("(1523)", Glicko(1523.4, 75.1).text, "provisional: in parentheses (Q1)")
    }

    @Test
    fun `the deviation never falls below 45 nor rises above 500`() {
        val many = List(20) { Glicko2.Game(1500.0, 45.0, (it % 2).toDouble()) }
        assertTrue(Glicko2.update(Glicko(1500.0, 50.0, 0.001), many, floor = 0.0).deviation < 45, "the plain system goes lower")
        assertEquals(45.0, Glicko2.update(Glicko(1500.0, 50.0, 0.001), many).deviation, 1e-9)
        var player = Glicko.start()
        repeat(300) { i -> player = Glicko2.update(player, listOf(Glicko2.Game(player.rating, 45.0, (i % 2).toDouble()))) }
        assertTrue(player.deviation in 45.0..75.0, "one Puzzle per period settles out of the parentheses: ${player.deviation}")
        val grown = Glicko2.update(Glicko(1500.0, 499.0, 0.09), listOf(Glicko2.Game(1500.0, 500.0, 1.0)))
        assertTrue(grown.deviation <= 500.0)
    }

    @Test
    fun `from the start, a solve at the Player Rating gains as much as a fail loses`() {
        val win = Glicko2.update(Glicko.start(), listOf(Glicko2.Game(1500.0, 75.0, 1.0)))
        val loss = Glicko2.update(Glicko.start(), listOf(Glicko2.Game(1500.0, 75.0, 0.0)))
        assertTrue(win.rating > 1600, "${win.rating}")
        assertEquals(win.rating - 1500, 1500 - loss.rating, 1e-6)
        assertTrue(win.deviation < 500)
    }
}
