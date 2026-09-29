package com.yarosz.chess.rules

/**
 * The number of distinct Move sequences of [depth] plies from [position]: the standard check that a
 * move generator is exact (R1.1), and later the check that the engine's generator agrees (B8).
 */
fun perft(position: Position, depth: Int): Long {
    require(depth >= 0) { "negative depth" }
    if (depth == 0) return 1
    val moves = position.legalMoves
    if (depth == 1) return moves.size.toLong()
    var nodes = 0L
    for (move in moves) nodes += perft(position.apply(move), depth - 1)  // legal by construction
    return nodes
}
