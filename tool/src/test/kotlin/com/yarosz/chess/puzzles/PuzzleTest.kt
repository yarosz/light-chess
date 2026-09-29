package com.yarosz.chess.puzzles

import com.yarosz.chess.rules.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PuzzleTest {

    // Lichess puzzle 00008: Black sets up with Bxg3, White to find Rxe7.
    private val line = "00008;r6k/pp2r2p/4Rp1Q/3p4/8/1N1P2R1/PqP2bPP/7K b - - 0 24;" +
        "f2g3 e6e7 b2b1 b3c1 b1c1 h6c1;1797;76;crushing hangingPiece long middlegame"

    @Test
    fun `a Pack line becomes a Puzzle`() {
        val puzzle = Puzzle.parse(line)
        assertEquals("00008", puzzle.id)
        assertEquals("f2g3", puzzle.setupMove.uci)
        assertEquals(listOf("e6e7", "b2b1", "b3c1", "b1c1", "h6c1"), puzzle.solution.map { it.uci })
        assertEquals(1797, puzzle.rating)
        assertEquals(76, puzzle.ratingDeviation)
        assertEquals(listOf("crushing", "hangingPiece", "long", "middlegame"), puzzle.themes)
        assertEquals(Side.WHITE, puzzle.solver)
        assertEquals(Side.WHITE, puzzle.position.sideToMove)
    }

    @Test
    fun `castling written king onto rook is read as the king's step`() {
        val puzzle = Puzzle.parse("c;r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 0 1;e8h8 e1a1;1500;80;")
        assertEquals(listOf("e8g8", "e1c1"), (listOf(puzzle.setupMove) + puzzle.solution).map { it.uci })
        assertTrue(puzzle.themes.isEmpty())
    }

    @Test
    fun `bad lines are rejected with the puzzle id`() {
        val fen = "r6k/pp2r2p/4Rp1Q/3p4/8/1N1P2R1/PqP2bPP/7K b - - 0 24"
        for (bad in listOf(
            "x1;$fen;f2g3 e6e7;1797;76", // 5 fields
            "x2;r6k/pp2r2p/4Rp1Q/3p4/8/1N1P2R1/PqP2bPP b - - 0 24;f2g3 e6e7;1797;76;", // 7 ranks
            "x3;$fen;f2g3 e6e8;1797;76;", // an illegal Move
            "x4;$fen;f2g3;1797;76;", // no Solution
            "x5;$fen;f2g3 e6e7 b2b1;1797;76;", // the opponent has the last Move
            "x6;$fen;f2g3 e6e7;hard;76;",
        )) {
            val e = assertFailsWith<PackFormatException>(bad) { Puzzle.parse(bad) }
            assertTrue(e.message!!.startsWith("puzzle ${bad.substringBefore(';')}:"), e.message)
        }
    }
}
