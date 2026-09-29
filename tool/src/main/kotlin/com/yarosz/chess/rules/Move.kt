package com.yarosz.chess.rules

/**
 * One player moving one piece; castling is one Move, written as the king's two-square step (e1g1).
 * A Move is only meaningful in the Position it is played from: get one from [Position.legalMoves],
 * [Position.moveFromUci] or [Position.moveFromSan], never by guessing its fields.
 */
data class Move(val from: Square, val to: Square, val promotion: PieceType? = null) : GameEvent {

    /** UCI move text: `e2e4`, `e7e8q`, `e1g1`. */
    val uci: String get() = from.name + to.name + (promotion?.letter ?: "")

    override fun toString() = uci

    companion object {
        /**
         * Reads UCI text into its fields, without any Position. Null when the text is not UCI.
         * Whether the Move is legal is [Position.moveFromUci]'s question.
         */
        fun parseUci(text: String): Move? {
            if (text.length !in 4..5) return null
            val from = Square.parse(text.substring(0, 2)) ?: return null
            val to = Square.parse(text.substring(2, 4)) ?: return null
            val promotion = if (text.length == 5) {
                PieceType.fromLetter(text[4])?.takeIf { it in PieceType.PROMOTIONS && text[4].isLowerCase() }
                    ?: return null
            } else null
            return Move(from, to, promotion)
        }
    }
}

/** Which castling Moves are still allowed, as in FEN's third field. */
@JvmInline
value class CastlingRights(val bits: Int) {

    fun has(right: Int) = bits and right != 0

    fun without(mask: Int) = CastlingRights(bits and mask.inv())

    /** Always in KQkq order, "-" when there are none (canonical, contradiction 8). */
    val fen: String
        get() = if (bits == 0) "-" else buildString {
            if (has(WHITE_KINGSIDE)) append('K')
            if (has(WHITE_QUEENSIDE)) append('Q')
            if (has(BLACK_KINGSIDE)) append('k')
            if (has(BLACK_QUEENSIDE)) append('q')
        }

    override fun toString() = fen

    companion object {
        const val WHITE_KINGSIDE = 1
        const val WHITE_QUEENSIDE = 2
        const val BLACK_KINGSIDE = 4
        const val BLACK_QUEENSIDE = 8
        val NONE = CastlingRights(0)
        val ALL = CastlingRights(15)
    }
}
