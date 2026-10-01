package com.yarosz.chess.board

import com.yarosz.chess.rules.Move
import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.Side
import com.yarosz.chess.rules.Square
import kotlin.test.Test
import kotlin.test.assertEquals

/** K1: no coordinate on a square carrying a mark that reaches its corners. */
class MarkedSquaresTest {

    private fun sq(name: String) = Square.parse(name)!!

    private fun squares(vararg names: String) = names.mapTo(HashSet()) { sq(it) }

    @Test
    fun `nothing is marked on a quiet board`() {
        assertEquals(emptySet(), markedSquares(null, null, null, null, null))
    }

    @Test
    fun `the last Move marks both its squares`() {
        val move = Move(sq("a1"), sq("a3"))
        assertEquals(squares("a1", "a3"), markedSquares(move, null, null, null, null))
    }

    @Test
    fun `a selection marks its square and its captures, not its quiet targets`() {
        // The rook on a1 can take on a3 and move to a2: a3 gets a capture ring, a2 only a dot.
        val input = MoveInput(Position.fromFen("4k3/8/8/8/8/p7/8/R3K3 w - - 0 1"), movable = setOf(Side.WHITE))
            .tap(sq("a1")).input
        assertEquals(squares("a1", "a3"), markedSquares(null, input, null, null, null))
    }

    @Test
    fun `the drag outline marks the square under the lifted piece only while one is lifted`() {
        val start = MoveInput(Position.START, movable = setOf(Side.WHITE))
        val lifted = start.copy(selected = sq("b1"), dragFrom = sq("b1"), dragOver = sq("c3"))
        assertEquals(squares("b1", "c3"), markedSquares(null, lifted, null, null, null))
        val dropped = start.copy(dragOver = sq("c3"))
        assertEquals(emptySet(), markedSquares(null, dropped, null, null, null))
    }

    @Test
    fun `the check, Hint and Hint capture rings mark their squares`() {
        assertEquals(squares("e1", "g1", "h8"), markedSquares(null, null, sq("e1"), sq("g1"), sq("h8")))
    }
}
