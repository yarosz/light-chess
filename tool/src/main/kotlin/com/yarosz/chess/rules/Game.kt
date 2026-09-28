package com.yarosz.chess.rules

/**
 * One thing that happens in a Game: a [Move], a resignation, or a draw offer and its answer.
 * Claims arrive with clocks (v3); threefold repetition and the 50-move rule need no claim
 * (contradiction 9).
 */
sealed interface GameEvent

data class Resignation(val side: Side) : GameEvent

data class DrawOffer(val side: Side) : GameEvent

data class DrawAcceptance(val side: Side) : GameEvent

data class DrawRefusal(val side: Side) : GameEvent

/** How a Game ended and why. */
sealed interface Result {
    data class Win(val winner: Side, val by: WinReason) : Result
    data class Draw(val by: DrawReason) : Result
}

enum class WinReason { CHECKMATE, RESIGNATION }

enum class DrawReason { STALEMATE, AGREEMENT, REPETITION, FIFTY_MOVE_RULE, INSUFFICIENT_MATERIAL }

/** A Game Event that can't happen at its place in the Game. */
class InvalidGameEventException(val index: Int, val event: GameEvent, why: String) :
    IllegalArgumentException("game event $index ($event): $why")

/**
 * A contest from a [start] Position, made of its Game [events] in order (R1.11). Everything else, the
 * current Position and the [result] included, follows from replaying them. Building a Game replays
 * and checks every event, so a Game that exists is a valid one: an illegal Move, an answer to no
 * draw offer, or anything after the Result throws [InvalidGameEventException].
 *
 * Draws by stalemate, repetition, the 50-move rule and insufficient material are automatic, in every
 * mode (contradiction 9). Checkmate on the Move that reaches the 50-move limit is still checkmate.
 */
class Game private constructor(
    val start: Position,
    val events: List<GameEvent>,
    /** [start], then the Position after each Move. */
    val positions: List<Position>,
    private val keys: List<String>,
    /** The side whose draw offer is waiting for an answer. */
    val openDrawOffer: Side?,
    val result: Result?,
) {
    val position: Position get() = positions.last()

    val moves: List<Move> get() = events.filterIsInstance<Move>()

    /** Ply of the latest Move: 0 before any Move. */
    val ply: Int get() = positions.size - 1

    val isOver: Boolean get() = result != null

    /** This Game with [event] appended. Throws [InvalidGameEventException] if it can't happen now. */
    operator fun plus(event: GameEvent): Game {
        val index = events.size
        fun invalid(why: String): Nothing = throw InvalidGameEventException(index, event, why)
        if (result != null) invalid("the game is already over ($result)")
        val toMove = position.sideToMove
        return when (event) {
            is Move -> {
                if (event !in position.legalMoves) invalid("illegal move in ${position.fen}")
                val next = position.play(event)
                val nextKeys = keys + next.repetitionKey
                Game(
                    start, events + event, positions + next, nextKeys,
                    // A Move answers the other side's open offer with a refusal (FIDE style).
                    openDrawOffer = openDrawOffer.takeIf { it == toMove },
                    result = automaticResult(next, nextKeys),
                )
            }
            is Resignation ->
                copy(event, openDrawOffer = null, result = Result.Win(event.side.opponent, WinReason.RESIGNATION))
            is DrawOffer -> {
                if (openDrawOffer != null) invalid("a draw offer is already open")
                copy(event, openDrawOffer = event.side, result = null)
            }
            is DrawAcceptance -> {
                if (openDrawOffer != event.side.opponent) invalid("no draw offer from the other side to accept")
                copy(event, openDrawOffer = null, result = Result.Draw(DrawReason.AGREEMENT))
            }
            is DrawRefusal -> {
                if (openDrawOffer != event.side.opponent) invalid("no draw offer from the other side to refuse")
                copy(event, openDrawOffer = null, result = null)
            }
        }
    }

    private fun copy(event: GameEvent, openDrawOffer: Side?, result: Result?) =
        Game(start, events + event, positions, keys, openDrawOffer, result)

    override fun equals(other: Any?) = other is Game && start == other.start && events == other.events

    override fun hashCode() = 31 * start.hashCode() + events.hashCode()

    companion object {
        /** Replays [events] from [start]. Throws [InvalidGameEventException] at the first one that can't happen. */
        fun of(start: Position = Position.START, events: List<GameEvent> = emptyList()): Game {
            val keys = listOf(start.repetitionKey)
            var game = Game(start, emptyList(), listOf(start), keys, null, automaticResult(start, keys))
            for (event in events) game += event
            return game
        }

        /** The Result the rules impose on [position], reached through the Positions whose keys are [keys]. */
        private fun automaticResult(position: Position, keys: List<String>): Result? = when {
            position.isCheckmate -> Result.Win(position.sideToMove.opponent, WinReason.CHECKMATE)
            position.isStalemate -> Result.Draw(DrawReason.STALEMATE)
            position.hasInsufficientMaterial -> Result.Draw(DrawReason.INSUFFICIENT_MATERIAL)
            keys.count { it == keys.last() } >= 3 -> Result.Draw(DrawReason.REPETITION)
            position.halfmoveClock >= 100 -> Result.Draw(DrawReason.FIFTY_MOVE_RULE)
            else -> null
        }
    }
}
