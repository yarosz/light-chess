package com.yarosz.chess.rules

import java.security.MessageDigest

/** A FEN string that doesn't describe a legal Position. */
class FenException(message: String) : IllegalArgumentException(message)

internal fun parseFen(fen: String): Position {
    val fields = fen.trim().split(Regex("\\s+"))
    if (fields.size != 4 && fields.size != 6) fail(fen, "needs 4 or 6 fields, found ${fields.size}")

    val board = arrayOfNulls<Piece>(64)
    val ranks = fields[0].split('/')
    if (ranks.size != 8) fail(fen, "needs 8 ranks, found ${ranks.size}")
    for ((i, text) in ranks.withIndex()) {
        val rank = 7 - i
        var file = 0
        for (c in text) {
            if (c in '1'..'8') {
                file += c - '0'
            } else {
                val piece = Piece.fromFenChar(c) ?: fail(fen, "unknown piece '$c'")
                if (file > 7) fail(fen, "rank ${rank + 1} is longer than 8 squares")
                board[rank * 8 + file] = piece
                file++
            }
            if (file > 8) fail(fen, "rank ${rank + 1} is longer than 8 squares")
        }
        if (file != 8) fail(fen, "rank ${rank + 1} has $file squares")
    }
    for (side in Side.entries) {
        val kings = board.count { it == Piece.of(side, PieceType.KING) }
        if (kings != 1) fail(fen, "needs one ${side.name.lowercase()} king, found $kings")
    }
    for (i in (0..7) + (56..63)) {
        if (board[i]?.type == PieceType.PAWN) fail(fen, "a pawn on ${Square(i)}")
    }

    val sideToMove = when (fields[1]) {
        "w" -> Side.WHITE
        "b" -> Side.BLACK
        else -> fail(fen, "side to move must be w or b")
    }

    var bits = 0
    if (fields[2] != "-") {
        for (c in fields[2]) {
            val right = when (c) {
                'K' -> CastlingRights.WHITE_KINGSIDE
                'Q' -> CastlingRights.WHITE_QUEENSIDE
                'k' -> CastlingRights.BLACK_KINGSIDE
                'q' -> CastlingRights.BLACK_QUEENSIDE
                else -> fail(fen, "unknown castling right '$c'")
            }
            if (bits and right != 0) fail(fen, "castling right '$c' twice")
            bits = bits or right
        }
    }
    val castling = CastlingRights(bits)
    for ((right, king, rook) in listOf(
        Triple(CastlingRights.WHITE_KINGSIDE, 4 to Piece.WHITE_KING, 7 to Piece.WHITE_ROOK),
        Triple(CastlingRights.WHITE_QUEENSIDE, 4 to Piece.WHITE_KING, 0 to Piece.WHITE_ROOK),
        Triple(CastlingRights.BLACK_KINGSIDE, 60 to Piece.BLACK_KING, 63 to Piece.BLACK_ROOK),
        Triple(CastlingRights.BLACK_QUEENSIDE, 60 to Piece.BLACK_KING, 56 to Piece.BLACK_ROOK),
    )) {
        if (castling.has(right) && (board[king.first] != king.second || board[rook.first] != rook.second)) {
            fail(fen, "castling right without its king and rook at home")
        }
    }

    val enPassant = if (fields[3] == "-") null else {
        val sq = Square.parse(fields[3]) ?: fail(fen, "bad en passant square '${fields[3]}'")
        // The square a pawn of the side that just moved skipped: empty, with that pawn in front of it.
        val (rank, pusher, forward) = if (sideToMove == Side.WHITE) Triple(5, Piece.BLACK_PAWN, -8) else Triple(2, Piece.WHITE_PAWN, 8)
        if (sq.rank != rank || board[sq.index] != null || board[sq.index - forward] != null ||
            board[sq.index + forward] != pusher
        ) fail(fen, "en passant square ${sq.name} has no pawn that just moved two squares")
        sq
    }

    val halfmove = if (fields.size == 6) fields[4].toIntOrNull()?.takeIf { it >= 0 } ?: fail(fen, "bad halfmove clock") else 0
    val fullmove = if (fields.size == 6) fields[5].toIntOrNull()?.takeIf { it >= 1 } ?: fail(fen, "bad fullmove number") else 1

    val notToMove = sideToMove.opponent
    val theirKing = board.indexOf(Piece.of(notToMove, PieceType.KING))
    if (Position.attacked(board, theirKing, sideToMove)) fail(fen, "the side not to move is in check")

    return Position(board, sideToMove, castling, enPassant, halfmove, fullmove)
}

internal fun writeFen(position: Position, canonical: Boolean): String = buildString {
    for (rank in 7 downTo 0) {
        var empty = 0
        for (file in 0..7) {
            val piece = position.pieceAt(Square.of(file, rank))
            if (piece == null) {
                empty++
            } else {
                if (empty > 0) append(empty)
                empty = 0
                append(piece.fenChar)
            }
        }
        if (empty > 0) append(empty)
        if (rank > 0) append('/')
    }
    append(if (position.sideToMove == Side.WHITE) " w " else " b ")
    append(position.castling.fen)
    append(' ')
    val ep = position.enPassant
    append(if (ep == null || (canonical && !position.hasLegalEnPassant)) "-" else ep.name)
    append(' ').append(position.halfmoveClock)
    append(' ').append(position.fullmoveNumber)
}

internal fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun fail(fen: String, why: String): Nothing = throw FenException("bad FEN \"$fen\": $why")
