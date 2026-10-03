package com.yarosz.chess.games

import com.yarosz.chess.rules.FenException
import com.yarosz.chess.rules.Game
import com.yarosz.chess.rules.Position
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `games.json`, the game mode's save file (B5, B7, D6): the Game in progress and the last
 * [FINISHED_CAP] finished ones, each as PGN ([Pgn]). One file, like `puzzles.json`, so that ending a
 * Game (in progress to finished) and F7's "Play from here" (the old Game to finished, the new one in
 * progress) are one atomic save, and schemaVersion has a place to live. The compatibility rule is
 * [com.yarosz.chess.puzzles.PuzzleData]'s: a later schema only adds fields.
 *
 * Every change goes through [save], [startNew] or [reopen], which keep the invariants: [current] is
 * never over, [finished] is newest first and at most [FINISHED_CAP] long.
 */
@Serializable
data class GameData(
    val schemaVersion: Int = SCHEMA_VERSION,
    val current: SavedGame? = null,
    /** Newest first, at most [FINISHED_CAP] (B7). Unfinished Games replaced by F7 are here with Result "*". */
    val finished: List<SavedGame> = emptyList(),
    /** The new-game page's last choices (v2 PR 4). */
    val choices: GameChoices = GameChoices(),
    /** The board flip in the Menu (D10, v2): the computer's Side at the bottom. */
    val flipped: Boolean = false,
) {
    /**
     * [record] saved: while its Game goes on it becomes [current], with a FEN checkpoint; once it is
     * over it goes to the front of [finished] and [current] is cleared.
     */
    fun save(record: GameRecord): GameData =
        if (record.game.isOver) copy(current = null, finished = (listOf(SavedGame(Pgn.write(record))) + finished).take(FINISHED_CAP))
        else copy(current = SavedGame(Pgn.write(record), record.game.position.fen))

    /**
     * A new Game in progress in place of [current] (F7's "Play from here"): the old one, if any, goes
     * to [finished] unfinished, Result "*".
     */
    fun startNew(record: GameRecord): GameData {
        val archived = current?.let { copy(current = null, finished = (listOf(SavedGame(it.pgn)) + finished).take(FINISHED_CAP)) } ?: this
        return archived.save(record)
    }

    /**
     * The Game in progress, exactly as saved: its PGN replayed through the core. When the PGN doesn't
     * read (say, written by a newer build), the FEN checkpoint restarts the Game from its Position,
     * without the Moves before it. Null when there is none or neither reads.
     */
    fun resume(): GameRecord? {
        val saved = current ?: return null
        runCatching { Pgn.read(saved.pgn) }.getOrNull()?.takeIf { !it.game.isOver }?.let { return it }
        val fen = saved.fen ?: return null
        val position = try {
            Position.fromFen(fen)
        } catch (e: FenException) {
            return null
        }
        return GameRecord(Game.of(position)).takeIf { !it.game.isOver }
    }

    /**
     * [record], over, is the Game that ended last: the newest of [finished], with no Game in
     * progress since. Only that Game can be taken back at its Result (X1).
     */
    fun endedLast(record: GameRecord): Boolean = current == null && finished.firstOrNull()?.pgn == Pgn.write(record)

    /**
     * X1's Takeback at the Result: [record], which [endedLast], comes off the front of [finished],
     * and [back], its Takeback, is in progress again. One save, so the Games list never holds it
     * twice.
     */
    fun reopen(record: GameRecord, back: GameRecord): GameData {
        require(endedLast(record)) { "only the Game that ended last can be reopened" }
        require(!back.game.isOver) { "a Takeback is in play" }
        return copy(finished = finished.drop(1)).save(back)
    }

    /** The finished Games that read, newest first; one that doesn't is left out. */
    fun history(): List<GameRecord> = finished.mapNotNull { runCatching { Pgn.read(it.pgn) }.getOrNull() }

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        const val SCHEMA_VERSION = 1
        const val FINISHED_CAP = 50

        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }

        /** Reads a save file leniently: unknown fields and a newer schemaVersion are fine. Null when it doesn't parse. */
        fun decode(text: String): GameData? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/** One Game as PGN; [fen] is the in-progress Game's checkpoint, the Position after its last Move. */
@Serializable
data class SavedGame(val pgn: String, val fen: String? = null)
