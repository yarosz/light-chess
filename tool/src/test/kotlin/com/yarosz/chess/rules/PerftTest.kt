package com.yarosz.chess.rules

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Perft against the published counts (chessprogramming.org "Perft Results"). Exact or the core is
 * wrong (R1.1). The default run takes about 15 s. The two deepest counts (Kiwipete and position 6 at
 * depth 5, about 35 s more) run with `./gradlew :tool:testDebugUnitTest -Dperft.deep=true`.
 */
class PerftTest {

    private fun check(fen: String, vararg expected: Long) {
        val position = Position.fromFen(fen)
        val got = expected.indices.map { perft(position, it + 1) }
        assertEquals(expected.toList(), got, fen)
    }

    private fun deep() = assumeTrue("set -Dperft.deep=true", System.getProperty("perft.deep") == "true")

    @Test
    fun `start position to depth 5`() =
        check(Position.START_FEN, 20, 400, 8_902, 197_281, 4_865_609)

    @Test
    fun `Kiwipete to depth 4`() =
        check(KIWIPETE, 48, 2_039, 97_862, 4_085_603)

    @Test
    fun `Kiwipete to depth 5 (deep)`() {
        deep()
        assertEquals(193_690_690, perft(Position.fromFen(KIWIPETE), 5))
    }

    @Test
    fun `position 3 to depth 6`() =
        check("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1", 14, 191, 2_812, 43_238, 674_624, 11_030_083)

    @Test
    fun `position 4 to depth 5`() =
        check("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1", 6, 264, 9_467, 422_333, 15_833_292)

    @Test
    fun `position 4 mirrored to depth 5`() =
        check("r2q1rk1/pP1p2pp/Q4n2/bbp1p3/Np6/1B3NBn/pPPP1PPP/R3K2R b KQ - 0 1", 6, 264, 9_467, 422_333, 15_833_292)

    @Test
    fun `position 5 to depth 5`() =
        check("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8", 44, 1_486, 62_379, 2_103_487, 89_941_194)

    @Test
    fun `position 6 to depth 4`() =
        check(POSITION_6, 46, 2_079, 89_890, 3_894_594)

    @Test
    fun `position 6 to depth 5 (deep)`() {
        deep()
        assertEquals(164_075_551, perft(Position.fromFen(POSITION_6), 5))
    }

    private companion object {
        const val KIWIPETE = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
        const val POSITION_6 = "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10"
    }
}
