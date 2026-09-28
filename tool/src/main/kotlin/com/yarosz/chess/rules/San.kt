package com.yarosz.chess.rules

/**
 * Standard algebraic notation for a legal [move] here: `Nbd7`, `exd6`, `e8=Q+`, `O-O-O`, `Qh4#`.
 * Throws if [move] is not legal here.
 */
fun Position.san(move: Move): String {
    require(move in legalMoves) { "illegal move ${move.uci} in $fen" }
    val after = play(move)
    val suffix = when {
        after.isCheckmate -> "#"
        after.inCheck -> "+"
        else -> ""
    }
    return sanWithoutSuffix(move) + suffix
}

/**
 * The legal Move this SAN names, or null. Tolerates a check or mate mark that is missing or wrong,
 * annotations (`!`, `?`), `0-0` for `O-O`, and a promotion written without `=` (`e8Q`).
 */
fun Position.moveFromSan(text: String): Move? {
    val wanted = normalizeSan(text)
    if (wanted.isEmpty()) return null
    return legalMoves.singleOrNull { normalizeSan(sanWithoutSuffix(it)) == wanted }
}

private fun normalizeSan(text: String): String =
    text.trim().trimEnd('+', '#', '!', '?').replace("0", "O").replace("=", "")

private fun Position.sanWithoutSuffix(move: Move): String {
    val piece = pieceAt(move.from) ?: error("no piece on ${move.from}")
    if (piece.type == PieceType.KING && move.to.file - move.from.file == 2) return "O-O"
    if (piece.type == PieceType.KING && move.from.file - move.to.file == 2) return "O-O-O"
    val capture = pieceAt(move.to) != null || (piece.type == PieceType.PAWN && move.from.file != move.to.file)
    return buildString {
        if (piece.type == PieceType.PAWN) {
            if (capture) append('a' + move.from.file).append('x')
            append(move.to.name)
            move.promotion?.let { append('=').append(it.letter.uppercaseChar()) }
        } else {
            append(piece.type.letter.uppercaseChar())
            val rivals = legalMoves.filter {
                it.to == move.to && it.from != move.from && pieceAt(it.from) == piece
            }
            if (rivals.isNotEmpty()) {
                when {
                    rivals.none { it.from.file == move.from.file } -> append('a' + move.from.file)
                    rivals.none { it.from.rank == move.from.rank } -> append('1' + move.from.rank)
                    else -> append(move.from.name)
                }
            }
            if (capture) append('x')
            append(move.to.name)
        }
    }
}
