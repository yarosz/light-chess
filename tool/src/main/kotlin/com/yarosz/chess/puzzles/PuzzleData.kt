package com.yarosz.chess.puzzles

import com.yarosz.chess.board.PieceSet
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `puzzles.json`, the puzzle mode's save file (A8, D6, contradiction 5). The compatibility rule: a
 * later schema only adds fields and never changes the meaning of one an earlier schema knows, so a
 * file from a newer build reads for the fields this build knows and the rest are ignored.
 */
@Serializable
data class PuzzleData(
    val schemaVersion: Int = SCHEMA_VERSION,
    val rating: Double = Glicko.START_RATING,
    val deviation: Double = Glicko.START_DEVIATION,
    val volatility: Double = Glicko.START_VOLATILITY,
    /** The seed screen has been answered (D4); false again after "Reset rating" (F5). */
    val seeded: Boolean = false,
    /** The Pack this file was last written against (A8), to notice a new one (F1). */
    val packSha256: String? = null,
    /** Every Puzzle with a scored Attempt, by Lichess id: never served again (A7). Kept across Packs (F1). */
    val finished: List<String> = emptyList(),
    /** The rated Puzzle on screen, until Next. */
    val current: InProgress? = null,
    /** Newest first, at most [MISSED_CAP] (D2). */
    val missed: List<MissedEntry> = emptyList(),
    /** Newest first, at most [HISTORY_CAP] (F11). */
    val history: List<HistoryEntry> = emptyList(),
    /**
     * The Piece Set the board draws (P2). A Tool-wide choice kept here because this is v1's only save
     * file; Reset rating leaves it alone. A file without it, or with a set this build doesn't know,
     * reads as the default (coerceInputValues). Added without a schemaVersion bump: it only adds a
     * field, and an older build that drops it loses a look, never a meaning.
     */
    val pieceSet: PieceSet = PieceSet.DEFAULT,
) {
    val player: Glicko get() = Glicko(rating, deviation, volatility)

    fun withPlayer(glicko: Glicko) = copy(rating = glicko.rating, deviation = glicko.deviation, volatility = glicko.volatility)

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        const val SCHEMA_VERSION = 1
        const val MISSED_CAP = 100
        const val HISTORY_CAP = 50

        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }

        /** Reads a save file leniently: unknown fields and a newer schemaVersion are fine. Null when it doesn't parse. */
        fun decode(text: String): PuzzleData? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/** The rated Puzzle on screen and how far its Attempt got. */
@Serializable
data class InProgress(
    val id: String,
    /** The Puzzle Rating, so the Pack looks in the right Band first. */
    val puzzleRating: Int,
    val state: AttemptState = AttemptState.OPEN,
    /** UCI, the Moves after the setup Move. */
    val moves: List<String> = emptyList(),
    val solutionShown: Boolean = false,
    /** The last Move tried was wrong and taken back: the strip reads "Try again" until the next correct Move. */
    val justWrong: Boolean = false,
    /** The Attempt reached its result; the strip shows it and Next. */
    val done: Boolean = false,
    /** The Player Rating's change when the Attempt was scored, shown in the result strip. */
    val delta: Int? = null,
)

/**
 * One Missed Puzzle (D2). [state] has a default so that a state this build doesn't know, written by a
 * newer one, reads as Failed instead of making the whole file unreadable (coerceInputValues).
 */
@Serializable
data class MissedEntry(val id: String, val puzzleRating: Int, val state: AttemptState = AttemptState.FAILED)

/**
 * One scored Attempt: the Rating screen's text list (F11). [delta] is 0 when unrated. An unknown
 * [state] reads as Failed, as in [MissedEntry].
 */
@Serializable
data class HistoryEntry(
    val id: String,
    val puzzleRating: Int,
    val state: AttemptState = AttemptState.FAILED,
    val delta: Int,
    val solutionShown: Boolean = false,
)
