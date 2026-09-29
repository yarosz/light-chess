package com.yarosz.chess.board

import com.yarosz.chess.rules.Move
import com.yarosz.chess.rules.PieceType
import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.Side
import com.yarosz.chess.rules.Square
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class MoveInputTest {

    private fun sq(name: String) = Square.parse(name)!!

    private fun at(fen: String) = MoveInput(Position.fromFen(fen))

    private val start = MoveInput(Position.START)

    /** Taps each square in turn and returns the final input and every Move played on the way. */
    private fun MoveInput.taps(vararg squares: String): Pair<MoveInput, List<Move>> {
        var input = this
        val moves = ArrayList<Move>()
        for (s in squares) {
            val step = input.tap(sq(s))
            input = step.input
            step.move?.let { moves += it }
        }
        return input to moves
    }

    @Test
    fun tapTapPlaysE2E4() {
        val first = start.tap(sq("e2"))
        assertNull(first.move)
        assertEquals(sq("e2"), first.input.selected)
        assertEquals(setOf(sq("e3"), sq("e4")), first.input.targets)
        assertEquals(emptySet(), first.input.captures)
        val second = first.input.tap(sq("e4"))
        assertEquals(Move(sq("e2"), sq("e4")), second.move)
        assertNull(second.input.selected)
    }

    @Test
    fun tappingAnotherOwnPieceReselects() {
        val (input, moves) = start.taps("e2", "g1")
        assertEquals(sq("g1"), input.selected)
        assertEquals(setOf(sq("f3"), sq("h3")), input.targets)
        assertEquals(emptyList(), moves)
    }

    @Test
    fun tappingTheSelectedPieceDeselects() {
        val (input, moves) = start.taps("e2", "e2")
        assertNull(input.selected)
        assertEquals(emptyList(), moves)
    }

    @Test
    fun tappingAnEmptyNonTargetDeselects() {
        val (input, moves) = start.taps("e2", "e5")
        assertNull(input.selected)
        assertEquals(emptyList(), moves)
    }

    @Test
    fun tappingAnOpponentPieceThatCantBeTakenDeselects() {
        val (input, _) = start.taps("e2", "e7")
        assertNull(input.selected)
    }

    @Test
    fun onlyTheSideToMoveCanBeSelected() {
        val (input, _) = start.taps("e7")
        assertNull(input.selected)
    }

    @Test
    fun aSideNotMovableByTheUserCanNotBeSelected() {
        val input = MoveInput(Position.START, movable = setOf(Side.BLACK))
        assertNull(input.tap(sq("e2")).input.selected)
        assertSame(input, input.startDrag(sq("e2")))
    }

    @Test
    fun illegalTargetIsIgnored() {
        // Tap-tap: a knight tapping a square it can't reach plays nothing and deselects.
        val (input, moves) = start.taps("g1", "g3")
        assertEquals(emptyList(), moves)
        assertNull(input.selected)
        // Dropping on an illegal square puts the piece back and keeps it selected.
        val lifted = start.startDrag(sq("g1")).dragTo(sq("g3"))
        val dropped = lifted.drop(sq("g3"))
        assertNull(dropped.move)
        assertEquals(sq("g1"), dropped.input.selected)
        assertNull(dropped.input.dragFrom)
    }

    @Test
    fun aPinnedPieceHasNoTargetsAcrossThePin() {
        // The e2 knight is pinned by the e8 rook; the rules core, not the input, says so.
        val input = at("4r1k1/8/8/8/8/8/4N3/4K3 w - - 0 1").tap(sq("e2")).input
        assertEquals(sq("e2"), input.selected)
        assertEquals(emptySet(), input.targets)
        assertNull(input.tap(sq("c3")).move)
    }

    @Test
    fun capturesAreMarkedAsCaptures() {
        val input = at("rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 2").tap(sq("e4")).input
        assertEquals(setOf(sq("e5"), sq("d5")), input.targets)
        assertEquals(setOf(sq("d5")), input.captures)
        assertEquals(Move(sq("e4"), sq("d5")), input.tap(sq("d5")).move)
    }

    @Test
    fun enPassantTargetIsACapture() {
        val input = at("rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3").tap(sq("e5")).input
        assertEquals(setOf(sq("e6"), sq("f6")), input.targets)
        assertEquals(setOf(sq("f6")), input.captures)
    }

    @Test
    fun dragPlaysAMove() {
        val lifted = start.startDrag(sq("g1"))
        assertEquals(sq("g1"), lifted.dragFrom)
        assertEquals(sq("g1"), lifted.selected)
        val over = lifted.dragTo(sq("f3"))
        assertEquals(sq("f3"), over.dragOver)
        val step = over.drop(sq("f3"))
        assertEquals(Move(sq("g1"), sq("f3")), step.move)
        assertNull(step.input.dragFrom)
        assertNull(step.input.selected)
    }

    @Test
    fun droppingBackOnTheStartSquareKeepsTheSelection() {
        val step = start.startDrag(sq("e2")).drop(sq("e2"))
        assertNull(step.move)
        assertEquals(sq("e2"), step.input.selected)
    }

    @Test
    fun droppingOffTheBoardPutsThePieceBack() {
        val step = start.startDrag(sq("e2")).drop(null)
        assertNull(step.move)
        assertEquals(sq("e2"), step.input.selected)
        assertNull(step.input.dragFrom)
    }

    @Test
    fun liftingAnOpponentPieceDoesNothingAndItsReleaseIsATap() {
        val lifted = start.startDrag(sq("e7"))
        assertNull(lifted.dragFrom)
        val step = lifted.release(pressed = sq("e7"), over = sq("e5"))
        assertNull(step.move)
        assertNull(step.input.selected)
        // A press on an own piece that moved but lifted nothing is not possible; on a target it plays.
        val selected = start.tap(sq("e2")).input
        assertEquals(Move(sq("e2"), sq("e4")), selected.release(pressed = sq("e4"), over = sq("e5")).move)
    }

    @Test
    fun touchesMapOntoTheSameGestures() {
        var input = start
        input = input.touch(Touch.Lift(sq("b1"))).input
        input = input.touch(Touch.Over(sq("c3"))).input
        val step = input.touch(Touch.Release(sq("b1"), sq("c3")))
        assertEquals(Move(sq("b1"), sq("c3")), step.move)
        assertEquals(sq("e2"), start.touch(Touch.Tap(sq("e2"))).input.selected)
        assertNull(start.startDrag(sq("e2")).touch(Touch.Cancel).input.dragFrom)
    }

    // --- castling (A4)

    private val castlingReady = "r3k2r/pppq1ppp/2np1n2/2b1p1B1/2B1P1b1/2NP1N2/PPPQ1PPP/R3K2R w KQkq - 0 8"

    @Test
    fun kingOntoOwnRookCastlesByTap() {
        val (_, kingside) = at(castlingReady).taps("e1", "h1")
        assertEquals(listOf(Move(sq("e1"), sq("g1"))), kingside)
        val (_, queenside) = at(castlingReady).taps("e1", "a1")
        assertEquals(listOf(Move(sq("e1"), sq("c1"))), queenside)
    }

    @Test
    fun kingTwoSquaresCastlesToo() {
        val input = at(castlingReady).tap(sq("e1")).input
        assertEquals(true, sq("g1") in input.targets && sq("c1") in input.targets)
        assertEquals(Move(sq("e1"), sq("g1")), input.tap(sq("g1")).move)
    }

    @Test
    fun kingOntoOwnRookCastlesByDrop() {
        val step = at(castlingReady).startDrag(sq("e1")).dragTo(sq("h1")).drop(sq("h1"))
        assertEquals(Move(sq("e1"), sq("g1")), step.move)
    }

    @Test
    fun kingOntoRookWithoutTheRightReselectsTheRook() {
        // No castling rights: tapping the rook after the king selects the rook.
        val (input, moves) = at("r3k2r/8/8/8/8/8/8/R3K2R w - - 0 1").taps("e1", "h1")
        assertEquals(emptyList(), moves)
        assertEquals(sq("h1"), input.selected)
    }

    @Test
    fun blackCastlesKingOntoRook() {
        val (_, moves) = at("r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 0 1").taps("e8", "a8")
        assertEquals(listOf(Move(sq("e8"), sq("c8"))), moves)
    }

    // --- promotion

    private val promotionReady = "8/1P4k1/8/8/8/8/6K1/8 w - - 0 1"

    @Test
    fun promotionOpensThePickerAndTheQueenIsFirst() {
        val step = at(promotionReady).taps("b7", "b8").first
        val choice = assertNotNull(step.promotion)
        assertEquals(sq("b7"), choice.from)
        assertEquals(sq("b8"), choice.to)
        assertEquals(listOf("b8", "b7", "b6", "b5").map(::sq), choice.squares)
        val played = step.tap(sq("b8"))
        assertEquals(Move(sq("b7"), sq("b8"), PieceType.QUEEN), played.move)
        assertNull(played.input.promotion)
    }

    @Test
    fun underpromotionToEachPiece() {
        val open = at(promotionReady).taps("b7", "b8").first
        assertEquals(Move(sq("b7"), sq("b8"), PieceType.ROOK), open.tap(sq("b7")).move)
        assertEquals(Move(sq("b7"), sq("b8"), PieceType.BISHOP), open.tap(sq("b6")).move)
        assertEquals(Move(sq("b7"), sq("b8"), PieceType.KNIGHT), open.tap(sq("b5")).move)
    }

    @Test
    fun aTapOutsideThePickerCancelsThePromotion() {
        val open = at(promotionReady).taps("b7", "b8").first
        val step = open.tap(sq("e4"))
        assertNull(step.move)
        assertNull(step.input.promotion)
        assertNull(step.input.selected)
    }

    @Test
    fun promotionByDropAndByCapture() {
        val fen = "1r4k1/P7/8/8/8/8/6K1/8 w - - 0 1"
        val open = at(fen).startDrag(sq("a7")).dragTo(sq("b8")).drop(sq("b8")).input
        assertEquals(sq("b8"), open.promotion?.to)
        assertEquals(Move(sq("a7"), sq("b8"), PieceType.KNIGHT), open.tap(sq("b5")).move)
        val input = at(fen).tap(sq("a7")).input
        assertEquals(setOf(sq("a8"), sq("b8")), input.targets)
        assertEquals(setOf(sq("b8")), input.captures)
    }

    @Test
    fun blackPromotesDownTheBoard() {
        val open = at("8/6k1/8/8/8/8/1p4K1/8 b - - 0 1").taps("b2", "b1").first
        assertEquals(listOf("b1", "b2", "b3", "b4").map(::sq), open.promotion?.squares)
        assertEquals(Move(sq("b2"), sq("b1"), PieceType.KNIGHT), open.tap(sq("b4")).move)
    }

    @Test
    fun aDragCanNotStartWhileThePickerIsOpen() {
        val open = at(promotionReady).taps("b7", "b8").first
        assertSame(open, open.startDrag(sq("g2")))
    }

    @Test
    fun noSelectionOnceTheGameHasNoLegalMoves() {
        // Fool's mate: White is checkmated.
        val mated = at("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3")
        assertNull(mated.tap(sq("e1")).input.selected)
    }

    @Test
    fun afterAMoveTheNextPositionStartsClear() {
        val step = start.taps("e2").first.tap(sq("e4"))
        val next = step.input.after(Position.START.play(step.move!!))
        assertNull(next.selected)
        assertEquals(Side.BLACK, next.position.sideToMove)
        assertEquals(sq("e7"), next.tap(sq("e7")).input.selected)
    }
}
