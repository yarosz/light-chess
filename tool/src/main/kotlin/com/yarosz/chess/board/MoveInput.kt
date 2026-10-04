package com.yarosz.chess.board

import com.yarosz.chess.rules.Move
import com.yarosz.chess.rules.Piece
import com.yarosz.chess.rules.PieceType
import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.Side
import com.yarosz.chess.rules.Square

/**
 * What the user's touches on the board have chosen so far, and the Move they complete (R1.6).
 * Pure and immutable: each gesture returns the next [MoveInput], plus the Move when one is complete.
 * Every rule of what is legal comes from [position]; this class only maps touches onto its
 * [Position.legalMoves].
 *
 * - Tap-tap: tap one of the moving side's pieces, then a target. Tapping another of its pieces
 *   reselects; tapping the selected piece, an empty square that is no target, or an opponent's piece
 *   that can't be taken deselects.
 * - Drag: pressing a piece and moving past touch slop lifts it ([startDrag]); dropping it on a target
 *   plays the Move, dropping it anywhere else puts it back and keeps it selected.
 * - King onto its own rook castles, as Lichess accepts (A4), by tap or by drop.
 * - A pawn reaching the last rank opens the [promotion] picker; the next tap chooses the piece, or
 *   cancels when it lands outside the picker.
 * - A Move completed by tap-tap slides in from its origin; one dropped lands at once, where the
 *   finger left it ([Step.slides], Z1). A promotion slides when the pawn got to the picker by tap.
 *
 * [movable] lists the Sides whose pieces the user may move; the side to move must be one of them.
 */
data class MoveInput(
    val position: Position,
    val movable: Set<Side> = Side.entries.toSet(),
    val selected: Square? = null,
    /** The square a lifted piece started from, while it is being dragged. */
    val dragFrom: Square? = null,
    /** The square under the finger during a drag, or null off the board. */
    val dragOver: Square? = null,
    val promotion: PromotionChoice? = null,
) {
    /**
     * The next [input], and the Move the gesture completed, if any. [slides]: the Move was made by
     * tap-tap, so the board slides it in from its origin; a dropped Move lands at once (Z1).
     */
    data class Step(val input: MoveInput, val move: Move? = null, val slides: Boolean = false)

    /**
     * A pawn Move waiting for its piece: [squares] are the picker's cells on the promotion file,
     * from the promotion square inward, holding [PieceType.PROMOTIONS] in order (queen first).
     * [dropped]: the pawn was dropped on [to], so the chosen Move lands without a slide (Z1).
     */
    data class PromotionChoice(
        val from: Square,
        val to: Square,
        val side: Side,
        val dropped: Boolean = false,
    ) {
        val squares: List<Square>
            get() {
                val step = if (to.rank == 7) -1 else 1
                return List(PieceType.PROMOTIONS.size) { i -> Square.of(to.file, to.rank + step * i) }
            }

        fun typeAt(square: Square): PieceType? = squares.indexOf(square).takeIf { it >= 0 }?.let { PieceType.PROMOTIONS[it] }
    }

    private val canMove: Boolean get() = position.sideToMove in movable && position.legalMoves.isNotEmpty()

    /** The squares the selected piece can move to (for castling, the king's destination). */
    val targets: Set<Square>
        get() = selected?.let { from -> position.legalMoves.filter { it.from == from }.mapTo(HashSet()) { it.to } } ?: emptySet()

    /** The [targets] that capture: an opponent's piece stands there, or it is an en passant capture. */
    val captures: Set<Square>
        get() = targets.filterTo(HashSet()) { to ->
            position.pieceAt(to) != null ||
                (to == position.enPassant && selected?.let { position.pieceAt(it)?.type } == PieceType.PAWN)
        }

    fun tap(square: Square): Step {
        promotion?.let { choice ->
            val type = choice.typeAt(square) ?: return Step(copy(selected = null, promotion = null))
            return complete(Move(choice.from, choice.to, type), dropped = choice.dropped)
        }
        if (!canMove) return Step(this)
        val from = selected ?: return Step(if (isOwnPiece(square)) copy(selected = square) else this)
        if (square == from) return Step(copy(selected = null))
        moveTo(from, square, dropped = false)?.let { return it }
        return Step(copy(selected = if (isOwnPiece(square)) square else null))
    }

    /** A press on [square] moved past touch slop. Lifts the piece there if the user may move it. */
    fun startDrag(square: Square): MoveInput {
        if (promotion != null || !canMove || !isOwnPiece(square)) return this
        return copy(selected = square, dragFrom = square, dragOver = square)
    }

    fun dragTo(square: Square?): MoveInput = if (dragFrom == null) this else copy(dragOver = square)

    /** The finger lifted over [square] (null: off the board). */
    fun drop(square: Square?): Step {
        val from = dragFrom ?: return Step(this)
        val settled = copy(dragFrom = null, dragOver = null)
        if (square == null || square == from) return Step(settled)
        return settled.moveTo(from, square, dropped = true) ?: Step(settled)
    }

    fun cancelDrag(): MoveInput = copy(dragFrom = null, dragOver = null)

    /**
     * The finger lifted after moving past touch slop from [pressed]. A lifted piece drops on [over];
     * a press that lifted nothing (an empty square, an opponent's piece) counts as a tap on [pressed].
     */
    fun release(pressed: Square, over: Square?): Step = if (dragFrom == null) tap(pressed) else drop(over)

    /** Applies one [Touch] from the board view. */
    fun touch(touch: Touch): Step = when (touch) {
        is Touch.Tap -> tap(touch.square)
        is Touch.Lift -> Step(startDrag(touch.square))
        is Touch.Over -> Step(dragTo(touch.square))
        is Touch.Release -> release(touch.pressed, touch.over)
        Touch.Cancel -> Step(cancelDrag())
    }

    /**
     * The Move from [from] to [square] if one is legal, as a completed Step or an open promotion;
     * [dropped] says the piece was dropped there rather than tapped (Z1).
     */
    private fun moveTo(from: Square, square: Square, dropped: Boolean): Step? {
        val candidates = position.legalMoves.filter { it.from == from && it.to == square }
        if (candidates.isNotEmpty()) {
            if (candidates.any { it.promotion != null }) {
                val side = position.pieceAt(from)!!.side
                val choice = PromotionChoice(from, square, side, dropped)
                return Step(copy(selected = from, promotion = choice))
            }
            return complete(candidates.single(), dropped)
        }
        // King onto its own rook: the rules core turns it into the castling Move when that is legal (A4).
        val piece = position.pieceAt(from)
        if (piece?.type == PieceType.KING && position.pieceAt(square) == Piece.of(piece.side, PieceType.ROOK)) {
            position.moveFromUci(from.name + square.name)?.let { return complete(it, dropped) }
        }
        return null
    }

    private fun complete(move: Move, dropped: Boolean): Step =
        Step(
            copy(selected = null, dragFrom = null, dragOver = null, promotion = null),
            move,
            slides = !dropped,
        )

    private fun isOwnPiece(square: Square): Boolean = position.pieceAt(square)?.side == position.sideToMove

    /** The same choices carried to [next], the Position after a Move: nothing stays selected. */
    fun after(next: Position): MoveInput = MoveInput(next, movable)
}
