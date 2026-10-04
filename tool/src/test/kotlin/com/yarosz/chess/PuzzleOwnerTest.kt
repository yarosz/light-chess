package com.yarosz.chess

import com.yarosz.chess.board.Motion
import com.yarosz.chess.board.PieceSet
import com.yarosz.chess.puzzles.Attempt
import com.yarosz.chess.puzzles.Lines
import com.yarosz.chess.puzzles.Puzzle
import com.yarosz.chess.puzzles.PuzzleData
import com.yarosz.chess.puzzles.PuzzleFlow
import com.yarosz.chess.puzzles.Stage
import com.yarosz.chess.puzzles.TestPacks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The owner's pure rules: which slide the board shows, when the stage clock starts again, and the
 * Piece Set chosen before the first Puzzle was read (M4).
 */
class PuzzleOwnerTest {
    private val puzzle = Puzzle.parse(Lines.MATE_IN_2)
    private val hold = Attempt(puzzle)
    private val setup = hold.advance()
    private val play = setup.advance()
    private val reply = play.play(puzzle.solution[0])
    private val afterReply = reply.advance()

    @Test
    fun `a Move that plays itself slides in, and the user's own dropped Move doesn't`() {
        val setupSlide = slideAfter(hold, setup, null, 1)
        assertEquals(Motion(puzzle.setupMove, 1), setupSlide)
        val replySlide = slideAfter(reply, afterReply, setupSlide, 2)
        assertEquals(Motion(puzzle.solution[1], 2), replySlide)
        assertEquals(replySlide, slideAfter(afterReply, afterReply.hint(), replySlide, 3), "still the latest Move")
    }

    @Test
    fun `the user's own Move slides in by tap-tap, and lands at once when dropped (Z1)`() {
        val setupSlide = Motion(puzzle.setupMove, 1)
        val tapped = slideAfter(play, reply, setupSlide, 2, ownSlide = true)
        assertEquals(Motion(puzzle.solution[0], 2), tapped)
        val dropped = slideAfter(play, reply, setupSlide, 2, ownSlide = false)
        assertNull(dropped, "dropped: no slide at all")
        // The last Move of the line (Solved) slides too.
        val lastTurn = afterReply.play(puzzle.solution[2])
        assertEquals(Stage.DONE, lastTurn.stage)
        val last = slideAfter(afterReply, lastTurn, null, 3, ownSlide = true)
        assertEquals(Motion(puzzle.solution[2], 3), last)
    }

    @Test
    fun `the reply supersedes the user's slide, which ends before it (Z1, A5)`() {
        val ownSlide = slideAfter(play, reply, Motion(puzzle.setupMove, 1), 2, ownSlide = true)
        assertEquals(Motion(puzzle.solution[1], 3), slideAfter(reply, afterReply, ownSlide, 3))
        // Hint while the reply is pending keeps the user's slide: its Move is still the latest.
        assertEquals(ownSlide, slideAfter(reply, reply.hint(), ownSlide, 3))
        // The reply waits for the slide to end, so the two never overlap on the board.
        assertTrue(Motion.MS < Attempt.REPLY_MS)
    }

    @Test
    fun `a wrong Move made by tap-tap never slides, as it is never drawn (A3)`() {
        val setupSlide = Motion(puzzle.setupMove, 1)
        val wrong = play.positions.last().legalMoves.first { it != puzzle.solution[0] }
        val tried = play.play(wrong)
        assertEquals(play.moves, tried.moves)
        assertEquals(setupSlide, slideAfter(play, tried, setupSlide, 2, ownSlide = true))
    }

    @Test
    fun `a slide ends once its Move is no longer the latest, so it can't replay on the user's piece`() {
        val replySlide = Motion(puzzle.solution[1], 2)
        // The user's Move lands on the board: the reply's slide no longer matches it.
        assertNull(slideAfter(afterReply, afterReply.play(puzzle.solution[2]), replySlide, 3))
    }

    @Test
    fun `a new Puzzle starts with no slide`() {
        val other = Attempt(Puzzle.parse(TestPacks.line("other", 1600)))
        assertNull(slideAfter(afterReply, other, Motion(puzzle.solution[1], 2), 3))
        assertNull(slideAfter(null, hold, null, 1))
    }

    @Test
    fun `a tap that changes nothing leaves the stage clock running`() {
        for (waiting in listOf(hold, setup, reply)) {
            assertTrue(waiting.stage != Stage.PLAY)
            assertFalse(restartsClock(waiting, waiting.hint()), "Hint in ${waiting.stage}")
            assertFalse(restartsClock(waiting, waiting.play(puzzle.solution[0])), "a Move in ${waiting.stage}")
            assertTrue(restartsClock(waiting, waiting.advance()), "the next stage in ${waiting.stage}")
        }
        assertTrue(restartsClock(null, hold), "the first Attempt")
        assertTrue(restartsClock(play, reply), "a correct Move starts the reply's wait")
    }

    @Test
    fun `a Piece Set chosen while the first Puzzle is read survives the read (M4)`() {
        val flow = PuzzleFlow(TestPacks.of("A", listOf(TestPacks.line("a1500", 1500))))
        val opened = flow.open(PuzzleData(pieceSet = PieceSet.ROUNDED))
        assertSame(opened, withPieceSet(opened, null), "nothing read yet changes nothing")
        assertSame(opened, withPieceSet(opened, PieceSet.ROUNDED), "the file's own set")
        val tapped = withPieceSet(opened, PieceSet.GEOMETRIC)
        assertEquals(PieceSet.GEOMETRIC, tapped.data.pieceSet)
        assertEquals(opened.current, tapped.current, "the Attempt on screen is untouched")
        assertTrue(opened.kept != tapped.kept, "the choice reaches the file")
    }
}
