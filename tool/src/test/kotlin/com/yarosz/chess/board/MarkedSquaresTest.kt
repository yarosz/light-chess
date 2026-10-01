package com.yarosz.chess.board

import com.yarosz.chess.rules.Move
import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.Side
import com.yarosz.chess.rules.Square
import kotlin.test.Test
import kotlin.test.assertEquals

/** K1: no coordinate on a square whose mark crosses it. */
class MarkedSquaresTest {

    private fun sq(name: String) = Square.parse(name)!!

    private fun squares(vararg names: String) = names.mapTo(HashSet()) { sq(it) }

    private val start = Position.START

    @Test
    fun `nothing is marked on a quiet board`() {
        assertEquals(emptySet(), markedSquares(start, null, null, null, null, null))
    }

    @Test
    fun `the last Move marks both its squares`() {
        val move = Move(sq("a1"), sq("a3"))
        assertEquals(squares("a1", "a3"), markedSquares(start, move, null, null, null, null))
    }

    @Test
    fun `a selection marks its square and its captures, not its quiet targets`() {
        // The rook on a1 can take on a3 and move to a2: a3 gets a capture ring, a2 only a dot.
        val position = Position.fromFen("4k3/8/8/8/8/p7/8/R3K3 w - - 0 1")
        val input = MoveInput(position, movable = setOf(Side.WHITE)).tap(sq("a1")).input
        assertEquals(squares("a1", "a3"), markedSquares(position, null, input, null, null, null))
    }

    @Test
    fun `the drag outline marks the square under the lifted piece only while one is lifted`() {
        val input = MoveInput(start, movable = setOf(Side.WHITE))
        val lifted = input.copy(selected = sq("b1"), dragFrom = sq("b1"), dragOver = sq("c3"))
        assertEquals(squares("b1", "c3"), markedSquares(start, null, lifted, null, null, null))
        val dropped = input.copy(dragOver = sq("c3"))
        assertEquals(emptySet(), markedSquares(start, null, dropped, null, null, null))
    }

    @Test
    fun `the check ring and the ring on the piece to move mark their squares`() {
        assertEquals(squares("e1", "g1"), markedSquares(start, null, null, sq("e1"), sq("g1"), null))
    }

    @Test
    fun `the Game Hint's destination is marked only when a piece stands there`() {
        // The Game Hint's capture ring: a piece on the destination (a black pawn on a7).
        assertEquals(squares("a7"), markedSquares(start, null, null, null, null, sq("a7")))
        // A dot on an empty destination (a3) leaves its coordinate.
        assertEquals(emptySet(), markedSquares(start, null, null, null, null, sq("a3")))
    }
}
