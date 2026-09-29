package com.yarosz.chess.book

import com.yarosz.chess.rules.Move
import com.yarosz.chess.rules.Position
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * When and how the computer plays from the [Book] at a Level (book ruling 4, decision log):
 * - Level 1: never.
 * - Levels 2-4: for Game plies 1 to 8, a pick proportional to the square root of each weight.
 * - Levels 5-8: for Game plies 1 to 20, a pick proportional to the weight.
 * - Once a Game has left the Book (a Position with no book Move, or a Move the Book doesn't offer,
 *   by either side), the engine plays the rest of the Game, even if a later Position transposes
 *   back into the Book.
 *
 * Pure: the answer depends only on the Game's start Position, its Moves and [seed], which the caller
 * stores with the Game. Each pick draws from a [Random] seeded by [seed] and the ply, so a resume or a
 * Takeback replays the same pick.
 */
class BookPolicy(val level: Int) {
    init {
        require(level in 1..8) { "Level $level is not 1 to 8" }
    }

    /** The last Game ply the Book may play (0: none). */
    val maxPly: Int = when (level) {
        1 -> 0
        in 2..4 -> 8
        else -> 20
    }

    private val sqrtWeights = level in 2..4

    /**
     * The book Move for the computer in the Position after [moves] from [start], or null when the
     * engine should search. [moves] are the Game's Moves so far, all legal.
     */
    fun pick(book: Book, start: Position, moves: List<Move>, seed: Long): Move? {
        val ply = moves.size + 1
        if (ply > maxPly) return null
        var position = start
        for (move in moves) {
            if (book.moves(position).none { it.move == move }) return null
            position = position.play(move)
        }
        val offered = book.moves(position)
        if (offered.isEmpty()) return null
        return choose(offered, Random(seed * PLY_MIX + ply))
    }

    /** One of [offered], drawn with probability proportional to its (possibly square-rooted) weight. */
    internal fun choose(offered: List<BookMove>, random: Random): Move {
        val weights = offered.map { if (sqrtWeights) sqrt(it.weight.toDouble()) else it.weight.toDouble() }
        var r = random.nextDouble() * weights.sum()
        for ((i, w) in weights.withIndex()) {
            r -= w
            if (r < 0) return offered[i].move
        }
        return offered.last().move
    }

    private companion object {
        // Spreads the seed so that the seeds for neighbouring plies are unrelated.
        const val PLY_MIX = -0x61c8864680b583ebL
    }
}
