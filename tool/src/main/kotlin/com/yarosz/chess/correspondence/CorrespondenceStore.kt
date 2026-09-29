package com.yarosz.chess.correspondence

import com.yarosz.chess.SaveFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * `correspondence.json`: every Correspondence Game this phone holds, seat secrets included. One file,
 * so that a pending entry and the Games it touches (a rematch offer and its new Game) are one atomic
 * save. The compatibility rule is PuzzleData's: a later schema only adds fields.
 */
@Serializable
data class CorrespondenceData(
    val schemaVersion: Int = SCHEMA_VERSION,
    val games: List<CorrespondenceGame> = emptyList(),
    /** The Relay's clock minus this phone's, at the last response: how the phone estimates the Relay's time. */
    val clockOffset: Long = 0,
    /** Seats being taken, sent again until the Relay answers (W9). */
    val seats: List<PendingSeat> = emptyList(),
) {
    fun game(gameId: String): CorrespondenceGame? = games.firstOrNull { it.gameId == gameId }

    /** [game] in place of the Game with its id, or added at the end. */
    fun with(game: CorrespondenceGame): CorrespondenceData {
        val i = games.indexOfFirst { it.gameId == game.gameId }
        val next = if (i < 0) games + game else games.toMutableList().also { it[i] = game }
        return copy(games = pruned(next))
    }

    fun without(gameId: String): CorrespondenceData = copy(games = games.filterNot { it.gameId == gameId })

    fun withSeat(seat: PendingSeat): CorrespondenceData = copy(seats = seats.filterNot { it.secret == seat.secret } + seat)

    fun withoutSeat(seat: PendingSeat): CorrespondenceData = copy(seats = seats.filterNot { it.secret == seat.secret })

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        const val SCHEMA_VERSION = 1

        /** Finished Games kept, newest first by when they closed; the Games being played are never dropped. */
        const val OVER_CAP = 50

        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }

        fun decode(text: String): CorrespondenceData? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()

        private fun pruned(games: List<CorrespondenceGame>): List<CorrespondenceGame> {
            val over = games.filter { it.stage == Stage.OVER }
            if (over.size <= OVER_CAP) return games
            val keep = over.sortedByDescending { it.closedAt ?: 0 }.take(OVER_CAP).map { it.gameId }.toSet()
            return games.filter { it.stage != Stage.OVER || it.gameId in keep }
        }
    }
}

/**
 * [CorrespondenceData] saved through [SaveFile] (temp + rename, a `.bak`, a corrupt file set aside)
 * in [dir]. On the phone [dir] is [noBackupDir]: the seat secrets stay out of every backup path
 * (C3, F10), since the generated manifest leaves `allowBackup` at Android's default.
 */
class CorrespondenceStore(
    dir: File,
    rename: (File, File) -> Boolean = File::renameTo,
) {
    private val file = SaveFile(dir.also { it.mkdirs() }, FILE, CorrespondenceData::encode, CorrespondenceData::decode, rename)

    fun load(): CorrespondenceData? = file.load()

    /** Throws IOException when a write or rename fails; a main file that parsed is then as it was. */
    fun save(data: CorrespondenceData) = file.save(data)

    companion object {
        const val FILE = "correspondence.json"

        /**
         * The app's `no_backup` directory beside [filesDir] (what Android's getNoBackupFilesDir
         * returns): Auto Backup and device-to-device transfer never copy it. The SDK hands a Tool its
         * filesDir only, and `android.content.Context` is a blocked import, so it is reached from there.
         */
        fun noBackupDir(filesDir: File): File = File(filesDir.absoluteFile.parentFile ?: filesDir, "no_backup")
    }
}
