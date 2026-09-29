package com.yarosz.chess.rules

/**
 * The Position's Polyglot key: the 64-bit Zobrist hash that Polyglot opening books are sorted and
 * searched by, as the Polyglot book-format specification defines it
 * (https://hgm.nubati.net/book_format.html), with that specification's Random64 table.
 *
 * key = pieces ^ castling ^ en passant ^ turn, where
 * - each piece adds Random64[64 * kind + square], kind = 2 * type + (1 for White), types in the
 *   order pawn, knight, bishop, rook, queen, king (our [PieceType] order);
 * - each castling right adds Random64[768 + (K, Q, k, q)];
 * - after a double pawn push, the pushed pawn's file adds Random64[772 + file] only when a pawn of
 *   the side to move stands next to the pushed pawn. Unlike [canonicalFen], the capture need not be
 *   legal (the spec: "it is irrelevant if the potential en passant capturing move is legal");
 * - White to move adds Random64[780].
 *
 * Move counters don't enter it. Equal Positions (except for counters) have equal keys.
 */
val Position.polyglotKey: Long
    get() {
        var key = 0L
        for (i in 0..63) {
            val piece = pieceAt(Square(i)) ?: continue
            val kind = 2 * piece.type.ordinal + if (piece.side == Side.WHITE) 1 else 0
            key = key xor POLYGLOT_RANDOM64[64 * kind + i]
        }
        if (castling.has(CastlingRights.WHITE_KINGSIDE)) key = key xor POLYGLOT_RANDOM64[768]
        if (castling.has(CastlingRights.WHITE_QUEENSIDE)) key = key xor POLYGLOT_RANDOM64[769]
        if (castling.has(CastlingRights.BLACK_KINGSIDE)) key = key xor POLYGLOT_RANDOM64[770]
        if (castling.has(CastlingRights.BLACK_QUEENSIDE)) key = key xor POLYGLOT_RANDOM64[771]
        polyglotEnPassantFile()?.let { key = key xor POLYGLOT_RANDOM64[772 + it] }
        if (sideToMove == Side.WHITE) key = key xor POLYGLOT_RANDOM64[780]
        return key
    }

/** The en passant file Polyglot hashes: a side-to-move pawn stands beside the pawn that just moved two. */
private fun Position.polyglotEnPassantFile(): Int? {
    val ep = enPassant ?: return null
    val pushedRank = if (sideToMove == Side.WHITE) 4 else 3
    val ourPawn = Piece.of(sideToMove, PieceType.PAWN)
    val beside = listOf(ep.file - 1, ep.file + 1).filter { it in 0..7 }
    return if (beside.any { pieceAt(Square.of(it, pushedRank)) == ourPawn }) ep.file else null
}
