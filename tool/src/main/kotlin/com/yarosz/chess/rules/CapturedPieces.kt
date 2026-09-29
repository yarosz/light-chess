package com.yarosz.chess.rules

/**
 * The Captured Pieces of a Game at one Ply (CONTEXT.md, P3): the pieces each Side has taken, read
 * from the Moves up to that Ply, en passant included. A captured piece is the one its capture removed
 * from the board, so a promoted pawn taken later counts as the piece it became, and the pawn it came
 * from was never captured. Each list is in [ORDER], pawns first.
 *
 * [lead] is the Material Lead from White's view (P1, N3, B3, R5, Q9): the material on the board at
 * that Ply, so a promotion counts. Positive when White is ahead, negative when Black is, 0 when level.
 */
data class CapturedPieces(val byWhite: List<PieceType>, val byBlack: List<PieceType>, val lead: Int) {

    /** The pieces [side] has taken. */
    fun by(side: Side): List<PieceType> = if (side == Side.WHITE) byWhite else byBlack

    /** The Side with the Material Lead, or null when the material is level. */
    val leader: Side? get() = when {
        lead > 0 -> Side.WHITE
        lead < 0 -> Side.BLACK
        else -> null
    }

    /** Nothing taken and the material level: the row under the board shows nothing. */
    val isEmpty: Boolean get() = byWhite.isEmpty() && byBlack.isEmpty() && lead == 0

    companion object {
        /** The kinds a Side can capture, in the order they are listed and drawn. */
        val ORDER = listOf(PieceType.PAWN, PieceType.KNIGHT, PieceType.BISHOP, PieceType.ROOK, PieceType.QUEEN)

        val NONE = CapturedPieces(emptyList(), emptyList(), 0)

        /** The standard material values; a king has none. */
        fun value(type: PieceType): Int = when (type) {
            PieceType.PAWN -> 1
            PieceType.KNIGHT, PieceType.BISHOP -> 3
            PieceType.ROOK -> 5
            PieceType.QUEEN -> 9
            PieceType.KING -> 0
        }

        /** [game]'s Captured Pieces after its first [ply] Moves (0 is the start Position). */
        fun of(game: Game, ply: Int = game.ply): CapturedPieces {
            require(ply in 0..game.ply) { "ply $ply outside 0..${game.ply}" }
            val byWhite = mutableListOf<PieceType>()
            val byBlack = mutableListOf<PieceType>()
            val moves = game.moves
            for (i in 0 until ply) {
                val taken = takenBy(game.positions[i], moves[i]) ?: continue
                if (taken.side == Side.BLACK) byWhite += taken.type else byBlack += taken.type
            }
            return CapturedPieces(byWhite.sortedBy(ORDER::indexOf), byBlack.sortedBy(ORDER::indexOf), lead(game.positions[ply]))
        }

        /** The piece [move] takes in [position], en passant included, or null for a quiet Move. */
        private fun takenBy(position: Position, move: Move): Piece? {
            val mover = position.pieceAt(move.from) ?: return null
            val target = position.pieceAt(move.to)
            if (target != null) return target.takeIf { it.side != mover.side }
            val enPassant = mover.type == PieceType.PAWN && move.from.file != move.to.file
            return if (enPassant) Piece.of(mover.side.opponent, PieceType.PAWN) else null
        }

        private fun lead(position: Position): Int =
            position.pieces.sumOf { (_, piece) -> if (piece.side == Side.WHITE) value(piece.type) else -value(piece.type) }
    }
}
