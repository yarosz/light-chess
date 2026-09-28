package com.yarosz.chess.rules

enum class Side {
    WHITE, BLACK;

    val opponent: Side get() = if (this == WHITE) BLACK else WHITE
}

enum class PieceType(val letter: Char) {
    PAWN('p'), KNIGHT('n'), BISHOP('b'), ROOK('r'), QUEEN('q'), KING('k');

    companion object {
        /** The pieces a pawn may become, strongest first. */
        val PROMOTIONS = listOf(QUEEN, ROOK, BISHOP, KNIGHT)

        fun fromLetter(c: Char): PieceType? = entries.firstOrNull { it.letter == c.lowercaseChar() }
    }
}

enum class Piece(val side: Side, val type: PieceType) {
    WHITE_PAWN(Side.WHITE, PieceType.PAWN),
    WHITE_KNIGHT(Side.WHITE, PieceType.KNIGHT),
    WHITE_BISHOP(Side.WHITE, PieceType.BISHOP),
    WHITE_ROOK(Side.WHITE, PieceType.ROOK),
    WHITE_QUEEN(Side.WHITE, PieceType.QUEEN),
    WHITE_KING(Side.WHITE, PieceType.KING),
    BLACK_PAWN(Side.BLACK, PieceType.PAWN),
    BLACK_KNIGHT(Side.BLACK, PieceType.KNIGHT),
    BLACK_BISHOP(Side.BLACK, PieceType.BISHOP),
    BLACK_ROOK(Side.BLACK, PieceType.ROOK),
    BLACK_QUEEN(Side.BLACK, PieceType.QUEEN),
    BLACK_KING(Side.BLACK, PieceType.KING);

    /** The FEN letter: uppercase for White. */
    val fenChar: Char get() = if (side == Side.WHITE) type.letter.uppercaseChar() else type.letter

    companion object {
        fun of(side: Side, type: PieceType): Piece = entries[side.ordinal * 6 + type.ordinal]

        fun fromFenChar(c: Char): Piece? {
            val type = PieceType.fromLetter(c) ?: return null
            return of(if (c.isUpperCase()) Side.WHITE else Side.BLACK, type)
        }
    }
}

/** A square of the board, a1 = 0, b1 = 1, ..., h8 = 63. */
@JvmInline
value class Square(val index: Int) {
    init {
        require(index in 0..63) { "square index out of range: $index" }
    }

    /** 0 = the a-file. */
    val file: Int get() = index and 7

    /** 0 = the first rank. */
    val rank: Int get() = index shr 3

    val name: String get() = "${'a' + file}${'1' + rank}"

    /** Light squares are the ones where a1-h8 parity is odd (a1 is dark). */
    val isLight: Boolean get() = (file + rank) % 2 == 1

    override fun toString() = name

    companion object {
        fun of(file: Int, rank: Int): Square = Square(rank * 8 + file)

        fun parse(name: String): Square? {
            if (name.length != 2) return null
            val file = name[0] - 'a'
            val rank = name[1] - '1'
            return if (file in 0..7 && rank in 0..7) of(file, rank) else null
        }
    }
}
