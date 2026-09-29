package com.yarosz.chess.book

import com.yarosz.chess.rules.Move
import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.polyglotKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookPolicyTest {

    private val start = Position.START

    // The knights go out and back forever: every Position of the cycle is in this Book, so a Game can
    // stay in book for as many plies as it likes, and only the ply limit ends it.
    private val cycle = listOf("g1f3", "g8f6", "f3g1", "f6g8")
    private val cycleBook = bookOf(*cycle.indices.map { i -> Triple(start.after(*cycle.take(i).toTypedArray()), cycle[i], 10) }.toTypedArray())

    private fun cycleMoves(n: Int): List<Move> {
        var position = start
        return List(n) {
            val move = position.moveFromUci(cycle[it % 4])!!
            position = position.play(move)
            move
        }
    }

    @Test
    fun `Level 1 never plays from the Book`() {
        val policy = BookPolicy(1)
        assertEquals(0, policy.maxPly)
        for (seed in 0L..20L) assertNull(policy.pick(cycleBook, start, emptyList(), seed))
    }

    @Test
    fun `Levels 2 to 4 play from the Book to ply 8, Levels 5 to 8 to ply 20`() {
        for (level in 2..8) {
            val policy = BookPolicy(level)
            val limit = if (level <= 4) 8 else 20
            assertEquals(limit, policy.maxPly)
            for (played in 0 until limit) {
                assertEquals(cycle[played % 4], policy.pick(cycleBook, start, cycleMoves(played), 1L)?.uci, "L$level ply ${played + 1}")
            }
            assertNull(policy.pick(cycleBook, start, cycleMoves(limit), 1L), "L$level ply ${limit + 1}")
        }
    }

    @Test
    fun `out of book and a Move the Book doesn't offer both end the Book for the rest of the Game`() {
        val policy = BookPolicy(8)
        // No entry for the Position: the engine.
        assertNull(policy.pick(cycleBook, start, listOf(start.moveFromUci("e2e4")!!), 3L))
        // The user leaves the Book (b1c3), then transposes back into it (c3b1): still the engine.
        val moves = mutableListOf<Move>()
        var position = start
        for (uci in listOf("b1c3", "g8f6", "c3b1", "f6g8")) {
            val move = position.moveFromUci(uci)!!
            moves += move
            position = position.play(move)
        }
        assertEquals(start.polyglotKey, position.polyglotKey, "back at the start Position")
        assertTrue(cycleBook.moves(position).isNotEmpty())
        assertNull(policy.pick(cycleBook, start, moves, 3L))
        // The computer's own book Moves keep it in book: after the same four plies from the cycle, it is.
        assertNotNull(policy.pick(cycleBook, start, cycleMoves(4), 3L))
    }

    @Test
    fun `the same seed gives the same pick, in any call order, and seeds differ`() {
        val wide = bookOf(*listOf("e2e4", "d2d4", "c2c4", "g1f3", "b2b3", "g2g3").map { Triple(start, it, 100) }.toTypedArray())
        val policy = BookPolicy(6)
        val first = (0L until 50L).map { policy.pick(wide, start, emptyList(), it) }
        val again = (0L until 50L).reversed().map { policy.pick(wide, start, emptyList(), it) }.reversed()
        assertEquals(first, again)
        assertTrue(first.toSet().size > 3, "different seeds pick different Moves: $first")
    }

    @Test
    fun `Levels 2 to 4 pick in proportion to the square root of the weight, 5 to 8 to the weight`() {
        // Weights 1 and 9: sqrt gives 1 : 3 (25% for the lighter), linear gives 1 : 9 (10%).
        val book = bookOf(Triple(start, "a2a3", 1), Triple(start, "e2e4", 9))
        val trials = 20_000
        fun share(level: Int) = (0L until trials).count { BookPolicy(level).pick(book, start, emptyList(), it)?.uci == "a2a3" } / trials.toDouble()
        for (level in 2..4) assertEquals(0.25, share(level), 0.015, "L$level")
        for (level in 5..8) assertEquals(0.10, share(level), 0.01, "L$level")
    }
}
