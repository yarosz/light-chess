package com.yarosz.chess.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Results derived by replay (R1.11) and the automatic draw rules (contradiction 9). */
class GameTest {

    private fun fen(text: String) = Position.fromFen(text)

    /** Plays UCI moves from [start], one Game Event each. */
    private fun game(start: Position, vararg uci: String): Game {
        var position = start
        val moves = uci.map { text ->
            val move = position.moveFromUci(text) ?: error("illegal $text in ${position.fen}")
            position = position.play(move)
            move
        }
        return Game.of(start, moves)
    }

    @Test
    fun `a new Game is under way`() {
        val g = Game.of()
        assertNull(g.result)
        assertEquals(0, g.ply)
        assertEquals(Position.START, g.position)
    }

    @Test
    fun `checkmate wins for the side that gave it`() {
        val g = game(Position.START, "f2f3", "e7e5", "g2g4", "d8h4")
        assertEquals(Result.Win(Side.BLACK, WinReason.CHECKMATE), g.result)
        assertEquals(4, g.ply)
        assertTrue(g.position.isCheckmate)
    }

    @Test
    fun `stalemate is a draw`() {
        val g = game(fen("7k/8/6K1/5Q2/8/8/8/8 w - - 0 1"), "f5f7")
        assertTrue(g.position.isStalemate)
        assertEquals(Result.Draw(DrawReason.STALEMATE), g.result)
    }

    @Test
    fun `a start Position that is already decided has its Result at once`() {
        assertEquals(Result.Draw(DrawReason.STALEMATE), Game.of(fen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")).result)
        assertEquals(Result.Win(Side.WHITE, WinReason.CHECKMATE), Game.of(fen("7k/6Q1/6K1/8/8/8/8/8 b - - 0 1")).result)
    }

    @Test
    fun `threefold repetition is an automatic draw`() {
        val shuffle = arrayOf("g1f3", "g8f6", "f3g1", "f6g8")
        val twice = game(Position.START, *shuffle)
        assertNull(twice.result, "the start Position has occurred twice")
        val thrice = game(Position.START, *shuffle, *shuffle)
        assertEquals(Result.Draw(DrawReason.REPETITION), thrice.result)
        assertEquals(8, thrice.ply)
    }

    @Test
    fun `repetition ignores an en passant square no capture can use`() {
        // After 1.e4 the Position has e3 in its FEN but no capture, so it matches itself later
        // without the square: e4 Nf6 Nf3 Ng8 Ng1 Nf6 Nf3 Ng8 Ng1 repeats the Position after 1.e4.
        val g = game(Position.START, "e2e4", "g8f6", "g1f3", "f6g8", "f3g1", "g8f6", "g1f3", "f6g8", "f3g1")
        assertEquals(Result.Draw(DrawReason.REPETITION), g.result)
    }

    @Test
    fun `castling rights make Positions different for repetition`() {
        // Rook out and back loses the right, so the first Position never recurs.
        val start = fen("4k3/8/8/8/8/8/8/4K2R w K - 0 1")
        val g = game(start, "h1h2", "e8d8", "h2h1", "d8e8", "h1h2", "e8d8", "h2h1", "d8e8")
        assertNull(g.result, "the Position with K is seen once, the one without twice")
        assertEquals(Result.Draw(DrawReason.REPETITION), (g + g.position.moveFromUci("h1h2")!!).result)
    }

    @Test
    fun `the 50-move rule is automatic at 100 plies without a capture or pawn move`() {
        val start = fen("4k3/8/8/8/8/8/8/R3K3 w - - 99 80")
        val g = game(start, "a1a2")
        assertEquals(Result.Draw(DrawReason.FIFTY_MOVE_RULE), g.result)
        val capture = game(fen("4k3/8/8/8/8/8/r7/R3K3 w - - 99 80"), "a1a2")
        assertNull(capture.result, "a capture resets the clock")
        assertEquals(0, capture.position.halfmoveClock)
    }

    @Test
    fun `checkmate on the hundredth ply is checkmate`() {
        val g = game(fen("7k/8/6K1/8/8/8/8/R7 w - - 99 80"), "a1a8")
        assertEquals(Result.Win(Side.WHITE, WinReason.CHECKMATE), g.result)
    }

    @Test
    fun `insufficient material`() {
        val dead = listOf(
            "4k3/8/8/8/8/8/8/4K3 w - - 0 1", // K v K
            "4k3/8/8/8/8/8/8/2B1K3 w - - 0 1", // K+B v K
            "4k3/8/8/8/8/8/8/1N2K3 w - - 0 1", // K+N v K
            "4kb2/8/8/8/8/8/8/2B1K3 w - - 0 1", // bishops on dark squares only (c1, f8)
            "4k3/8/8/8/8/8/8/B1B1K3 w - - 0 1", // two bishops, one colour (a1, c1)
        )
        val alive = listOf(
            "2b1k3/8/8/8/8/8/8/2B1K3 w - - 0 1", // bishops on both colours (c1, c8)
            "4k3/8/8/8/8/8/8/1NN1K3 w - - 0 1", // two knights
            "4k3/8/8/8/8/8/8/1N2Kb2 w - - 0 1", // knight v bishop
            "4k3/8/8/8/8/8/P7/4K3 w - - 0 1", // a pawn
            "4k3/8/8/8/8/8/8/R3K3 w - - 0 1", // a rook
        )
        for (text in dead) assertTrue(fen(text).hasInsufficientMaterial, text)
        for (text in alive) assertFalse(fen(text).hasInsufficientMaterial, text)
        // Kxd2 leaves king against king.
        val g = game(fen("4k3/8/8/8/8/8/3r4/4K3 w - - 0 1"), "e1d2")
        assertEquals(Result.Draw(DrawReason.INSUFFICIENT_MATERIAL), g.result)
    }

    @Test
    fun `resignation and agreed draws`() {
        val g = Game.of()
        assertEquals(Result.Win(Side.BLACK, WinReason.RESIGNATION), (g + Resignation(Side.WHITE)).result)
        val offered = g + DrawOffer(Side.WHITE)
        assertEquals(Side.WHITE, offered.openDrawOffer)
        assertEquals(Result.Draw(DrawReason.AGREEMENT), (offered + DrawAcceptance(Side.BLACK)).result)
        val refused = offered + DrawRefusal(Side.BLACK)
        assertNull(refused.result)
        assertNull(refused.openDrawOffer)
    }

    @Test
    fun `a Move by the other side refuses an open offer, the offerer's own Move does not`() {
        val offered = Game.of() + DrawOffer(Side.BLACK)
        val afterWhite = offered + Position.START.moveFromUci("e2e4")!!
        assertNull(afterWhite.openDrawOffer)
        val own = Game.of() + DrawOffer(Side.WHITE) + Position.START.moveFromUci("e2e4")!!
        assertEquals(Side.WHITE, own.openDrawOffer)
    }

    @Test
    fun `events that can't happen are refused where they stand`() {
        val mated = game(Position.START, "f2f3", "e7e5", "g2g4", "d8h4")
        val cases = listOf(
            Game.of() to Move(Square.parse("e2")!!, Square.parse("e5")!!),
            Game.of() to DrawAcceptance(Side.BLACK),
            Game.of() to DrawRefusal(Side.BLACK),
            (Game.of() + DrawOffer(Side.WHITE)) to DrawAcceptance(Side.WHITE),
            (Game.of() + DrawOffer(Side.WHITE)) to DrawOffer(Side.BLACK),
            mated to Resignation(Side.WHITE),
            (Game.of() + Resignation(Side.BLACK)) to Position.START.moveFromUci("e2e4")!!,
        )
        for ((before, event) in cases) {
            val e = assertFailsWith<InvalidGameEventException>("$event") { before + event }
            assertEquals(before.events.size, e.index)
        }
        val e = assertFailsWith<InvalidGameEventException> {
            Game.of(Position.START, mated.events + Resignation(Side.WHITE))
        }
        assertEquals(4, e.index)
    }
}
