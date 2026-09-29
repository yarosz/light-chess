package com.yarosz.chess.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** SAN and UCI move text. */
class NotationTest {

    private fun fen(text: String) = Position.fromFen(text)

    private fun Position.sanOf(uci: String) = san(assertNotNull(moveFromUci(uci), uci))

    @Test
    fun `plain moves, captures and pawn captures`() {
        val p = fen("rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 2")
        assertEquals("e5", p.sanOf("e4e5"))
        assertEquals("exd5", p.sanOf("e4d5"))
        assertEquals("Nf3", p.sanOf("g1f3"))
        assertEquals("Bb5+", p.sanOf("f1b5"))
    }

    @Test
    fun `disambiguation by file, then rank, then both`() {
        // Knights on b1 and f3 can both reach d2: by file.
        assertEquals("Nbd2", fen("4k3/8/8/8/8/5N2/8/1N2K3 w - - 0 1").sanOf("b1d2"))
        // Rooks on a1 and a5 reach a3: same file, so by rank.
        assertEquals("R1a3", fen("4k3/8/8/R7/8/8/8/R3K3 w - - 0 1").sanOf("a1a3"))
        // Queens on e4, h4 and h1 all reach e1. h4 shares a file with h1 and a rank with e4: both.
        assertEquals("Qh4e1", fen("2k5/8/8/8/4Q2Q/8/8/K6Q w - - 0 1").sanOf("h4e1"))
    }

    @Test
    fun `castling, promotion, en passant, check and mate`() {
        val castles = fen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        assertEquals("O-O", castles.sanOf("e1g1"))
        assertEquals("O-O-O", castles.sanOf("e1c1"))
        assertEquals("e8=Q+", fen("6k1/4P3/8/8/8/8/8/K7 w - - 0 1").sanOf("e7e8q"))
        assertEquals("e8=N", fen("6k1/4P3/8/8/8/8/8/K7 w - - 0 1").sanOf("e7e8n"))
        assertEquals("exd6", fen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 2").sanOf("e5d6"))
        val fools = fen("rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq g3 0 2")
        assertEquals("Qh4#", fools.sanOf("d8h4"))
    }

    @Test
    fun `SAN parsing tolerates marks, zeros and a missing equals sign`() {
        val p = fen("r3k2r/1P6/8/8/8/8/8/R3K2R w KQkq - 0 1")
        assertEquals(p.moveFromUci("e1g1"), p.moveFromSan("0-0"))
        assertEquals(p.moveFromUci("e1c1"), p.moveFromSan("O-O-O!"))
        assertEquals(p.moveFromUci("b7b8q"), p.moveFromSan("b8Q"))
        assertEquals(p.moveFromUci("b7a8r"), p.moveFromSan("bxa8=R+"))
        assertNull(p.moveFromSan("Nf3"))
        assertNull(p.moveFromSan(""))
    }

    @Test
    fun `UCI text for legal moves only, king onto rook reads as castling`() {
        val p = fen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        assertEquals("e1g1", p.moveFromUci("e1h1")?.uci)
        assertEquals("e1c1", p.moveFromUci("e1a1")?.uci)
        assertNull(fen("r3k2r/8/8/8/8/8/8/R3K2R w Qkq - 0 1").moveFromUci("e1h1"))
        assertNull(Position.START.moveFromUci("e2e5"))
        assertNull(Position.START.moveFromUci("e2"))
        assertNull(Position.START.moveFromUci("e7e8Q"))
        assertEquals("e7e8q", fen("6k1/4P3/8/8/8/8/8/K7 w - - 0 1").moveFromUci("e7e8q")?.uci)
        assertNull(fen("6k1/4P3/8/8/8/8/8/K7 w - - 0 1").moveFromUci("e7e8"))
    }
}
