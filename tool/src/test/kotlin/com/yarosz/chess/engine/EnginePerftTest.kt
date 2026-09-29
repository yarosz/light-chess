package com.yarosz.chess.engine

import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.perft
import pirarucu.board.factory.BoardFactory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import pirarucu.util.Perft as EnginePerft

/**
 * The Tool carries two move generators on purpose (ADR 0001): Pirarucu's fast board and our rules
 * core. Perft keeps them in agreement (B8): on the standard positions, where both must also match the
 * published counts, and on Positions from random Games, where castling, en passant and promotion
 * turn up in combinations the standard set misses.
 */
class EnginePerftTest {

    private fun enginePerft(fen: String, depth: Int): Long = EnginePerft.perft(BoardFactory.getBoard(fen), depth)

    /** Depth by depth, the engine's count equals our core's up to [coreDepth], then the published one. */
    private fun check(fen: String, coreDepth: Int, vararg published: Long) {
        val core = Position.fromFen(fen)
        for (depth in 1..coreDepth) {
            assertEquals(perft(core, depth), enginePerft(fen, depth), "$fen depth $depth")
        }
        published.forEachIndexed { i, expected ->
            assertEquals(expected, enginePerft(fen, i + 1), "$fen depth ${i + 1} (published)")
        }
    }

    @Test
    fun `start position`() = check(Position.START_FEN, 4, 20, 400, 8_902, 197_281, 4_865_609)

    @Test
    fun kiwipete() = check(KIWIPETE, 3, 48, 2_039, 97_862, 4_085_603)

    @Test
    fun `position 3`() =
        check("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1", 5, 14, 191, 2_812, 43_238, 674_624, 11_030_083)

    @Test
    fun `position 4 and its mirror`() {
        check("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1", 3, 6, 264, 9_467, 422_333, 15_833_292)
        check("r2q1rk1/pP1p2pp/Q4n2/bbp1p3/Np6/1B3NBn/pPPP1PPP/R3K2R b KQ - 0 1", 3, 6, 264, 9_467, 422_333, 15_833_292)
    }

    @Test
    fun `position 5`() =
        check("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8", 3, 44, 1_486, 62_379, 2_103_487)

    @Test
    fun `position 6`() =
        check("r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10", 3, 46, 2_079, 89_890, 3_894_594)

    @Test
    fun `random Games agree at depth 2 in every Position`() {
        var positions = 0
        for (seed in 1..40) {
            val random = Random(seed)
            var position = Position.START
            repeat(120) {
                val moves = position.legalMoves
                if (moves.isEmpty()) return@repeat
                assertEquals(perft(position, 2), enginePerft(position.fen, 2), position.fen)
                positions++
                position = position.play(moves[random.nextInt(moves.size)])
            }
        }
        assert(positions > 2_000) { "only $positions positions" }
    }

    private companion object {
        const val KIWIPETE = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
    }
}
