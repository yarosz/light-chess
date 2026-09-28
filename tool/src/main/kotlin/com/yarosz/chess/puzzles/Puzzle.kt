package com.yarosz.chess.puzzles

import com.yarosz.chess.rules.FenException
import com.yarosz.chess.rules.Move
import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.Side

/** A Pack line that isn't a valid Puzzle. */
class PackFormatException(message: String) : IllegalArgumentException(message)

/**
 * A Position and its Solution, from the Lichess puzzle database. [start] is the Position the Lichess
 * FEN names; the opponent plays [setupMove] from it, and the user plays from [position], the
 * Position after it. [solution] alternates the user's Moves and the opponent's replies and ends with
 * the user's. Every Move is checked against the rules core.
 *
 * The Pack reads a Band's worth of Puzzles at a time but only needs the Positions of the one the user
 * plays, so the FEN and Moves are parsed on first use (see [Pack]); [parse] parses them at once.
 */
class Puzzle private constructor(
    /** The Lichess puzzle id: lichess.org/training/<id>. Finished Puzzles are saved by it (ADR 0003). */
    val id: String,
    /** The Puzzle Rating, fixed for the life of the Pack (A7). */
    val rating: Int,
    val ratingDeviation: Int,
    val themes: List<String>,
    private val fen: String,
    private val uci: String,
) {
    private val line: Pair<Position, List<Move>> by lazy { parseLine(id, fen, uci) }

    val start: Position get() = line.first

    /** The opponent's Move that sets the Puzzle up. */
    val setupMove: Move get() = line.second[0]

    val solution: List<Move> get() = line.second.subList(1, line.second.size)

    /** The Position the user plays from. */
    val position: Position get() = start.play(setupMove)

    /** The Side the user plays: the one not to move in [start]. */
    val solver: Side get() = start.sideToMove.opponent

    override fun equals(other: Any?) = other is Puzzle && id == other.id

    override fun hashCode() = id.hashCode()

    override fun toString() = "Puzzle($id, $rating)"

    companion object {
        /**
         * Parses one Pack line, `id;FEN;UCI moves;rating;RD;themes`, checking every Move. Throws
         * [PackFormatException].
         */
        fun parse(line: String): Puzzle = read(line).also { it.line }

        /** Parses a Pack line's id, ratings and themes now, and its FEN and Moves on first use. */
        internal fun read(line: String): Puzzle {
            val fields = line.split(';')
            val id = fields[0]
            if (fields.size != 6) fail(id, "needs 6 fields, found ${fields.size}")
            val rating = fields[3].toIntOrNull() ?: fail(id, "bad rating '${fields[3]}'")
            val deviation = fields[4].toIntOrNull() ?: fail(id, "bad rating deviation '${fields[4]}'")
            val themes = if (fields[5].isEmpty()) emptyList() else fields[5].split(' ')
            return Puzzle(id, rating, deviation, themes, fields[1], fields[2])
        }

        private fun parseLine(id: String, fen: String, uci: String): Pair<Position, List<Move>> {
            val start = try {
                Position.fromFen(fen)
            } catch (e: FenException) {
                fail(id, e.message ?: "bad FEN")
            }
            val texts = uci.split(' ')
            // Moves[0] is the opponent's, and the user makes the last Move.
            if (texts.size < 2 || texts.size % 2 != 0) fail(id, "needs an even number of Moves, at least 2, found ${texts.size}")
            var position = start
            val moves = texts.mapIndexed { ply, text ->
                val move = position.moveFromUci(text) ?: fail(id, "Move ${ply + 1} ($text) is not legal in ${position.fen}")
                position = position.play(move)
                move
            }
            return start to moves
        }

        private fun fail(id: String, why: String): Nothing = throw PackFormatException("puzzle $id: $why")
    }
}
