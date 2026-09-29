package com.yarosz.chess.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P3: the Captured Pieces and the Material Lead of a Game at a Ply, read from its Moves. */
class CapturedPiecesTest {

    private fun play(fen: String?, vararg uci: String): Game =
        uci.fold(Game.of(fen?.let(Position::fromFen) ?: Position.START)) { g, m ->
            g + requireNotNull(g.position.moveFromUci(m)) { "$m is not legal in ${g.position.fen}" }
        }

    @Test
    fun nothingIsCapturedAtTheStart() {
        val captured = CapturedPieces.of(Game.of())
        assertEquals(CapturedPieces.NONE, captured)
        assertTrue(captured.isEmpty)
        assertNull(captured.leader)
    }

    @Test
    fun bothSidesCapturesAreCountedAndSortedPawnFirst() {
        // 1.e4 d5 2.exd5 Qxd5 3.Nc3 Qxa2 4.Rxa2: White takes a pawn and the queen, Black two pawns.
        val game = play(null, "e2e4", "d7d5", "e4d5", "d8d5", "b1c3", "d5a2", "a1a2")
        val captured = CapturedPieces.of(game)
        assertEquals(listOf(PieceType.PAWN, PieceType.QUEEN), captured.byWhite)
        assertEquals(listOf(PieceType.PAWN, PieceType.PAWN), captured.byBlack)
        assertEquals(8, captured.lead)
        assertEquals(Side.WHITE, captured.leader)
        assertEquals(captured.byWhite, captured.by(Side.WHITE))
        assertEquals(captured.byBlack, captured.by(Side.BLACK))
    }

    @Test
    fun theRowFollowsThePlyOnScreen() {
        val game = play(null, "e2e4", "d7d5", "e4d5", "d8d5", "b1c3", "d5a2", "a1a2")
        assertEquals(CapturedPieces.NONE, CapturedPieces.of(game, 2))
        assertEquals(CapturedPieces(listOf(PieceType.PAWN), emptyList(), 1), CapturedPieces.of(game, 3))
        // After 2...Qxd5 each Side has taken a pawn: the material is level, but the row isn't empty.
        val level = CapturedPieces.of(game, 4)
        assertEquals(CapturedPieces(listOf(PieceType.PAWN), listOf(PieceType.PAWN), 0), level)
        assertNull(level.leader)
        assertTrue(!level.isEmpty)
        assertEquals(-1, CapturedPieces.of(game, 6).lead)
        assertEquals(Side.BLACK, CapturedPieces.of(game, 6).leader)
        assertFailsWith<IllegalArgumentException> { CapturedPieces.of(game, 8) }
        assertFailsWith<IllegalArgumentException> { CapturedPieces.of(game, -1) }
    }

    @Test
    fun enPassantTakesThePawnBesideTheTarget() {
        // 1.e4 a6 2.e5 d5 3.exd6: the pawn on d5 is taken, though d6 was empty.
        val game = play(null, "e2e4", "a7a6", "e4e5", "d7d5", "e5d6")
        assertEquals(listOf(PieceType.PAWN), CapturedPieces.of(game).byWhite)
        assertEquals(emptyList(), CapturedPieces.of(game).byBlack)
        assertEquals(1, CapturedPieces.of(game).lead)
        // Black's en passant too.
        val black = play(null, "a2a3", "e7e5", "a3a4", "e5e4", "d2d4", "e4d3")
        assertEquals(listOf(PieceType.PAWN), CapturedPieces.of(black).byBlack)
    }

    @Test
    fun aPromotedPawnTakenLaterIsThePieceItBecame() {
        // 1.a8=Q+ Rxa8: Black takes a queen; the pawn it came from was never captured.
        val game = play("4k3/P7/8/8/8/8/r7/4K3 w - - 0 1", "a7a8q", "a2a8")
        assertEquals(emptyList(), CapturedPieces.of(game).byWhite)
        assertEquals(listOf(PieceType.QUEEN), CapturedPieces.of(game).byBlack)
        assertEquals(-5, CapturedPieces.of(game).lead)
        // Before the capture the promotion alone counts: a queen against a rook.
        assertEquals(CapturedPieces(emptyList(), emptyList(), 4), CapturedPieces.of(game, 1))
        assertEquals(Side.WHITE, CapturedPieces.of(game, 1).leader)
    }

    @Test
    fun aPromotionThatCapturesCountsThePieceTaken() {
        // 1.axb8=N: the capture is the rook on b8; White's knight is new material.
        val game = play("1r2k3/P7/8/8/8/8/8/4K3 w - - 0 1", "a7b8n")
        assertEquals(listOf(PieceType.ROOK), CapturedPieces.of(game).byWhite)
        assertEquals(3, CapturedPieces.of(game).lead)
    }

    @Test
    fun castlingCapturesNothing() {
        val game = play(null, "e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6", "e1g1")
        assertEquals(CapturedPieces.NONE, CapturedPieces.of(game))
    }

    @Test
    fun theStandardValues() {
        assertEquals(listOf(1, 3, 3, 5, 9, 0), PieceType.entries.map(CapturedPieces::value))
        assertEquals(CapturedPieces.ORDER, PieceType.entries - PieceType.KING)
    }
}
