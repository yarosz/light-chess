package com.yarosz.chess.rules

/** Precomputed square geometry. Plain square indexes (a1 = 0) for speed inside move generation. */
internal object Geometry {

    // Directions as (file step, rank step). Rook directions first, then bishop directions.
    private val DIRECTIONS = arrayOf(
        0 to 1, 0 to -1, 1 to 0, -1 to 0,
        1 to 1, -1 to 1, 1 to -1, -1 to -1,
    )
    val ROOK_DIRECTIONS = intArrayOf(0, 1, 2, 3)
    val BISHOP_DIRECTIONS = intArrayOf(4, 5, 6, 7)
    val QUEEN_DIRECTIONS = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7)

    /** RAYS[direction][square]: the squares from `square` outward in `direction`, nearest first. */
    val RAYS: Array<Array<IntArray>> = Array(8) { d ->
        val (df, dr) = DIRECTIONS[d]
        Array(64) { sq -> walk(sq, df, dr) }
    }

    val KNIGHT_TARGETS: Array<IntArray> = Array(64) { sq ->
        steps(sq, listOf(1 to 2, 2 to 1, 2 to -1, 1 to -2, -1 to -2, -2 to -1, -2 to 1, -1 to 2))
    }

    val KING_TARGETS: Array<IntArray> = Array(64) { sq -> steps(sq, DIRECTIONS.toList()) }

    /** PAWN_ATTACKS[side.ordinal][square]: the squares a pawn of that side on `square` attacks. */
    val PAWN_ATTACKS: Array<Array<IntArray>> = arrayOf(
        Array(64) { sq -> steps(sq, listOf(-1 to 1, 1 to 1)) },
        Array(64) { sq -> steps(sq, listOf(-1 to -1, 1 to -1)) },
    )

    private fun onBoard(file: Int, rank: Int) = file in 0..7 && rank in 0..7

    private fun walk(sq: Int, df: Int, dr: Int): IntArray {
        val out = ArrayList<Int>()
        var f = (sq and 7) + df
        var r = (sq shr 3) + dr
        while (onBoard(f, r)) {
            out += r * 8 + f
            f += df
            r += dr
        }
        return out.toIntArray()
    }

    private fun steps(sq: Int, deltas: List<Pair<Int, Int>>): IntArray =
        deltas.mapNotNull { (df, dr) ->
            val f = (sq and 7) + df
            val r = (sq shr 3) + dr
            if (onBoard(f, r)) r * 8 + f else null
        }.toIntArray()
}
