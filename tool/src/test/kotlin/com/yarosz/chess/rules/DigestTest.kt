package com.yarosz.chess.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** The canonical FEN and the digest two phones compare (R1.11, C4, contradiction 8). */
class DigestTest {

    private fun fen(text: String) = Position.fromFen(text)

    @Test
    fun `the start digest is SHA-256 of the first four canonical FEN fields`() {
        // Pinned: both phones of a Correspondence Game must compute exactly this.
        assertEquals("05bd4852088c5cfdb74e9b435432b63d6699f8e7502ddb7f44235aa0b1953c6c", Position.START.digest)
    }

    @Test
    fun `a double push with no en passant capture equals the Position without the square`() {
        val afterE4 = Position.START.play(Position.START.moveFromUci("e2e4")!!)
        assertEquals("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1", afterE4.fen)
        assertEquals("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1", afterE4.canonicalFen)
        val without = fen("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1")
        assertEquals(without.digest, afterE4.digest)
    }

    @Test
    fun `a double push that allows an en passant capture keeps the square`() {
        val with = fen("rnbqkbnr/ppp1pppp/8/8/3pP3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 3")
        val without = fen("rnbqkbnr/ppp1pppp/8/8/3pP3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 3")
        assertEquals(with.fen, with.canonicalFen)
        assertNotEquals(without.digest, with.digest)
    }

    @Test
    fun `an en passant capture that is only pseudo-legal does not count`() {
        // b5xc6 would open the fifth rank between the king on a5 and the rook on h5.
        val pinned = fen("7k/8/8/KPp4r/8/8/8/8 w - c6 0 1")
        assertEquals("7k/8/8/KPp4r/8/8/8/8 w - - 0 1", pinned.canonicalFen)
        assertEquals(fen("7k/8/8/KPp4r/8/8/8/8 w - - 0 1").digest, pinned.digest)
    }

    @Test
    fun `castling rights are canonical in KQkq order`() {
        assertEquals(Position.START.digest, fen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w kqKQ - 0 1").digest)
        assertEquals("r3k2r/8/8/8/8/8/8/R3K2R w Kq - 0 1", fen("r3k2r/8/8/8/8/8/8/R3K2R w qK - 0 1").canonicalFen)
    }

    @Test
    fun `move counters do not enter the digest, everything else does`() {
        assertEquals(Position.START.digest, fen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 7 30").digest)
        assertNotEquals(Position.START.digest, fen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQk - 0 1").digest)
        assertNotEquals(
            fen("rnbqkbnr/pppppppp/8/8/8/5N2/PPPPPPPP/RNBQKB1R b KQkq - 1 1").digest,
            fen("rnbqkbnr/pppppppp/8/8/8/5N2/PPPPPPPP/RNBQKB1R w KQkq - 1 1").digest,
        )
    }
}
