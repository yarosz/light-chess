package com.yarosz.chess.puzzles

import com.yarosz.chess.board.MoveInput
import com.yarosz.chess.rules.Move
import com.yarosz.chess.rules.PieceType
import com.yarosz.chess.rules.Side
import com.yarosz.chess.rules.Square
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Test Puzzles, as Pack lines: `id;FEN;UCI moves;rating;RD;themes`. */
object Lines {
    /** From the Pack: a mate in 2 for Black. Setup c7b8, then f3f2 (user), e1d1, f2f1#. */
    const val MATE_IN_2 = "CkDUF;rn2k2r/ppB2pRp/4p3/8/1bPPn3/2N2q2/PPQ1NP2/R3K3 w Qkq - 3 13;c7b8 f3f2 e1d1 f2f1;1500;75;mate mateIn2"

    /** White castles kingside; king onto the rook must count. */
    const val CASTLE = "castle;4k3/8/8/8/8/8/8/4K2R b K - 0 1;e8d7 e1g1;1500;75;"

    /** Ra8# is stored; Rb8# mates as well. */
    const val TWO_MATES = "twoMates;7k/2p3pp/8/8/8/8/8/RR4K1 b - - 0 1;c7c6 a1a8;1500;75;mate mateIn1"

    /** a8=Q# is stored; a8=R# mates too, a8=N doesn't. */
    const val PROMOTE_MATE = "promoteMate;7k/P1p3pp/8/8/8/8/8/6K1 b - - 0 1;c7c6 a7a8q;1500;75;mate mateIn1"

    /** e8=Q is stored and doesn't mate; e8=R is another piece and doesn't mate either. */
    const val PROMOTE = "promote;8/4P3/8/8/8/8/k7/6K1 b - - 0 1;a2a3 e7e8q;1500;75;"
}

class AttemptTest {

    private fun open(line: String): Attempt = Attempt(Puzzle.parse(line))

    /** Past the 500 ms hold and the 250 ms setup animation (A5). */
    private fun ready(line: String): Attempt = open(line).advance().advance()

    private fun Attempt.uci(text: String): Move = requireNotNull(position.moveFromUci(text)) { "$text in ${position.fen}" }

    private fun Attempt.playUci(text: String) = play(uci(text))

    @Test
    fun `an Attempt holds the Position, animates the setup Move, then waits for the user`() {
        val attempt = open(Lines.MATE_IN_2)
        assertEquals(Stage.HOLD, attempt.stage)
        assertEquals(500L, attempt.delayMs)
        assertEquals(attempt.puzzle.start, attempt.position)
        assertEquals(emptyList(), attempt.moves)
        val setup = attempt.advance()
        assertEquals(Stage.SETUP, setup.stage)
        assertEquals(250L, setup.delayMs)
        assertEquals(attempt.puzzle.position, setup.position)
        assertEquals(listOf(attempt.puzzle.setupMove), setup.moves)
        val play = setup.advance()
        assertEquals(Stage.PLAY, play.stage)
        assertNull(play.delayMs)
        assertEquals(Side.BLACK, play.position.sideToMove, "the solver moves next, and sits at the bottom")
        assertEquals(Side.BLACK, play.puzzle.solver)
    }

    @Test
    fun `a correct Move gets the reply 300 ms later, and the last one solves`() {
        val first = ready(Lines.MATE_IN_2).playUci("f3f2")
        assertEquals(Stage.REPLY, first.stage)
        assertEquals(300L, first.delayMs)
        assertEquals(AttemptState.OPEN, first.state)
        val replied = first.advance()
        assertEquals(Stage.PLAY, replied.stage)
        assertEquals(listOf("f3f2", "e1d1"), replied.played.map { it.uci })
        val solved = replied.playUci("f2f1")
        assertEquals(Stage.DONE, solved.stage)
        assertEquals(AttemptState.SOLVED, solved.state)
        assertTrue(solved.clean)
        assertEquals(5, solved.positions.size, "start, after the setup Move, then three Moves")
    }

    @Test
    fun `a wrong Move is taken back, fails the Attempt, and Try Mode goes on`() {
        val attempt = ready(Lines.MATE_IN_2)
        val wrong = attempt.playUci("h7h6")
        assertEquals(AttemptState.FAILED, wrong.state)
        assertEquals(Stage.PLAY, wrong.stage, "Try Mode: still the user's Move")
        assertEquals(attempt.position, wrong.position, "the wrong Move is taken back")
        assertTrue(wrong.justWrong)
        val again = wrong.playUci("h7h5")
        assertEquals(2, again.wrongMoves)
        assertEquals(AttemptState.FAILED, again.state)
        val finished = again.playUci("f3f2").advance().playUci("f2f1")
        assertEquals(Stage.DONE, finished.stage)
        assertEquals(AttemptState.FAILED, finished.state, "finishing in Try Mode doesn't undo the fail")
        assertFalse(finished.clean)
    }

    @Test
    fun `a Puzzle Hint rings the piece to move and makes an Open Attempt Hinted`() {
        val hinted = ready(Lines.MATE_IN_2).hint()
        assertEquals(Square.parse("f3"), hinted.hintSquare)
        assertEquals(AttemptState.HINTED, hinted.state)
        val moved = hinted.playUci("f3f2")
        assertNull(moved.hintSquare, "the ring goes with the next Move")
        val wrongAfter = moved.advance().playUci("h7h6")
        assertEquals(AttemptState.HINTED, wrongAfter.state, "a Hinted Attempt stays unrated after a mistake")
        val done = wrongAfter.playUci("f2f1")
        assertEquals(AttemptState.HINTED, done.state)
        assertEquals(Stage.DONE, done.stage)
    }

    @Test
    fun `a Puzzle Hint after a fail is free`() {
        val failed = ready(Lines.MATE_IN_2).playUci("h7h6")
        val hinted = failed.hint()
        assertEquals(AttemptState.FAILED, hinted.state)
        assertEquals(Square.parse("f3"), hinted.hintSquare)
    }

    @Test
    fun `Solution plays the line and fails an Open Attempt`() {
        val shown = ready(Lines.MATE_IN_2).showSolution()
        assertEquals(AttemptState.FAILED, shown.state)
        assertEquals(Stage.SOLUTION, shown.stage)
        assertEquals(600L, shown.delayMs)
        val one = shown.advance()
        assertEquals(listOf("f3f2"), one.played.map { it.uci })
        val done = one.advance().advance()
        assertEquals(Stage.DONE, done.stage)
        assertEquals(listOf("f3f2", "e1d1", "f2f1"), done.played.map { it.uci })
        assertTrue(done.position.isCheckmate)
        assertEquals(done, done.advance())
    }

    @Test
    fun `Solution keeps a Hinted Attempt Hinted`() {
        val shown = ready(Lines.MATE_IN_2).hint().showSolution()
        assertEquals(AttemptState.HINTED, shown.state)
        assertTrue(shown.solutionShown)
    }

    @Test
    fun `Solution during the reply's wait plays on from there`() {
        val waiting = ready(Lines.MATE_IN_2).playUci("f3f2")
        val shown = waiting.showSolution()
        assertEquals(AttemptState.FAILED, shown.state)
        assertEquals(listOf("f3f2", "e1d1", "f2f1"), shown.advance().advance().played.map { it.uci })
    }

    @Test
    fun `another checkmating Move solves the step`() {
        val attempt = ready(Lines.TWO_MATES)
        val solved = attempt.playUci("b1b8")
        assertEquals(AttemptState.SOLVED, solved.state)
        assertEquals(Stage.DONE, solved.stage)
        assertEquals(listOf("b1b8"), solved.played.map { it.uci })
    }

    @Test
    fun `king onto its own rook counts as castling, by UCI and by tap`() {
        val attempt = ready(Lines.CASTLE)
        val byRook = attempt.play(attempt.uci("e1h1"))
        assertEquals(AttemptState.SOLVED, byRook.state)
        var input = MoveInput(attempt.position, setOf(attempt.puzzle.solver))
        input = input.tap(Square.parse("e1")!!).input
        val step = input.tap(Square.parse("h1")!!)
        assertEquals(AttemptState.SOLVED, attempt.play(assertNotNull(step.move)).state)
    }

    @Test
    fun `a promotion must match the stored piece unless it mates`() {
        val mate = ready(Lines.PROMOTE_MATE)
        assertEquals(AttemptState.SOLVED, mate.play(Move(Square.parse("a7")!!, Square.parse("a8")!!, PieceType.ROOK)).state, "a8=R# mates too")
        assertEquals(AttemptState.FAILED, mate.play(Move(Square.parse("a7")!!, Square.parse("a8")!!, PieceType.KNIGHT)).state)
        val plain = ready(Lines.PROMOTE)
        assertEquals(AttemptState.FAILED, plain.play(Move(Square.parse("e7")!!, Square.parse("e8")!!, PieceType.ROOK)).state)
        assertEquals(AttemptState.SOLVED, plain.playUci("e7e8q").state)
    }

    @Test
    fun `nothing plays out of turn`() {
        val holding = open(Lines.MATE_IN_2)
        assertEquals(holding, holding.hint())
        assertEquals(holding, holding.showSolution())
        val waiting = ready(Lines.MATE_IN_2).playUci("f3f2")
        assertEquals(waiting, waiting.hint(), "no hint while the reply is pending")
    }

    @Test
    fun `resume replays the saved Moves against the Solution`() {
        val puzzle = Puzzle.parse(Lines.MATE_IN_2)
        val mid = assertNotNull(Attempt.resume(puzzle, AttemptState.FAILED, listOf("f3f2", "e1d1"), solutionShown = false, done = false))
        assertEquals(Stage.PLAY, mid.stage)
        assertEquals(AttemptState.FAILED, mid.state)
        assertEquals(Stage.REPLY, Attempt.resume(puzzle, AttemptState.OPEN, listOf("f3f2"), false, false)!!.stage)
        assertEquals(Stage.HOLD, Attempt.resume(puzzle, AttemptState.HINTED, emptyList(), false, false)!!.stage)
        assertEquals(Stage.SOLUTION, Attempt.resume(puzzle, AttemptState.FAILED, listOf("f3f2"), true, false)!!.stage)
        assertEquals(Stage.DONE, Attempt.resume(puzzle, AttemptState.SOLVED, listOf("f3f2", "e1d1", "f2f1"), false, true)!!.stage)
        assertNull(Attempt.resume(puzzle, AttemptState.OPEN, listOf("h7h6"), false, false), "Moves off the line")
    }
}
