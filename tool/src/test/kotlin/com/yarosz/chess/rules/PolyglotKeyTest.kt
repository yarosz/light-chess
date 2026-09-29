package com.yarosz.chess.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Polyglot keys against the test data published in the Polyglot book-format specification,
 * https://hgm.nubati.net/book_format.html ("Test data", computed with Toga II). Book ruling 3's gate.
 */
class PolyglotKeyTest {

    // (moves from the start, FEN as the spec prints it, key as the spec prints it)
    private val published = listOf(
        Triple("", "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", "463b96181691fc9c"),
        Triple("e2e4", "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1", "823c9b50fd114196"),
        Triple("e2e4 d7d5", "rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 2", "0756b94461c50fb0"),
        Triple("e2e4 d7d5 e4e5", "rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 2", "662fafb965db29d4"),
        Triple("e2e4 d7d5 e4e5 f7f5", "rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3", "22a48b5a8e47ff78"),
        Triple("e2e4 d7d5 e4e5 f7f5 e1e2", "rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPPKPPP/RNBQ1BNR b kq - 0 3", "652a607ca3f242c1"),
        Triple("e2e4 d7d5 e4e5 f7f5 e1e2 e8f7", "rnbq1bnr/ppp1pkpp/8/3pPp2/8/8/PPPPKPPP/RNBQ1BNR w - - 0 4", "00fdd303c946bdd9"),
        Triple("a2a4 b7b5 h2h4 b5b4 c2c4", "rnbqkbnr/p1pppppp/8/8/PpP4P/8/1P1PPPP1/RNBQKBNR b KQkq c3 0 3", "3c8123ea7b067637"),
        Triple("a2a4 b7b5 h2h4 b5b4 c2c4 b4c3 a1a3", "rnbqkbnr/p1pppppp/8/8/P6P/R1p5/1P1PPPP1/1NBQKBNR b Kkq - 0 4", "5c3f9b829b279560"),
    )

    private fun hex(key: Long) = key.toULong().toString(16).padStart(16, '0')

    @Test
    fun `the Random64 table is the spec's 781 distinct numbers`() {
        assertEquals(781, POLYGLOT_RANDOM64.size)
        assertEquals(781, POLYGLOT_RANDOM64.toSet().size)
        assertEquals("9d39247e33776d41", hex(POLYGLOT_RANDOM64.first()))
        assertEquals("f8d626aaaf278509", hex(POLYGLOT_RANDOM64.last()))
    }

    @Test
    fun `keys of the spec's FENs match its published keys`() {
        for ((_, fen, key) in published) assertEquals(key, hex(Position.fromFen(fen).polyglotKey), fen)
    }

    @Test
    fun `keys after playing the spec's moves match its published keys`() {
        for ((moves, fen, key) in published) {
            var position = Position.START
            for (uci in moves.split(' ').filter { it.isNotEmpty() }) position = position.play(position.moveFromUci(uci)!!)
            // The spec's FENs keep the halfmove clock at 0 after quiet moves; compare the other fields.
            assertEquals(fen.split(' ').take(4), position.fen.split(' ').take(4), moves)
            assertEquals(key, hex(position.polyglotKey), moves)
        }
    }

    @Test
    fun `the en passant file counts only with a side-to-move pawn beside the pushed pawn`() {
        // After e2e4 no Black pawn stands on d4 or f4: the same key with or without e3 in the FEN.
        val withEp = Position.fromFen("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")
        val withoutEp = Position.fromFen("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1")
        assertEquals(withoutEp.polyglotKey, withEp.polyglotKey)
        // The e5 pawn beside d5 counts even though exd6 is illegal (it would expose the king on a5):
        // the spec ignores legality, unlike the canonical FEN.
        val pinned = Position.fromFen("8/8/8/K2pP2r/8/8/8/7k w - d6 0 1")
        val none = Position.fromFen("8/8/8/K2pP2r/8/8/8/7k w - - 0 1")
        assertEquals("8/8/8/K2pP2r/8/8/8/7k w - - 0 1", pinned.canonicalFen)
        assertEquals(none.polyglotKey xor POLYGLOT_RANDOM64[772 + 3], pinned.polyglotKey)
    }

    @Test
    fun `move counters do not enter the key, and the side to move does`() {
        val a = Position.fromFen("rnbqkbnr/pppppppp/8/8/8/5N2/PPPPPPPP/RNBQKB1R b KQkq - 1 1")
        val b = Position.fromFen("rnbqkbnr/pppppppp/8/8/8/5N2/PPPPPPPP/RNBQKB1R b KQkq - 7 9")
        val c = Position.fromFen("rnbqkbnr/pppppppp/8/8/8/5N2/PPPPPPPP/RNBQKB1R w KQkq - 1 1")
        assertEquals(a.polyglotKey, b.polyglotKey)
        assertNotEquals(a.polyglotKey, c.polyglotKey)
    }
}
