package com.yarosz.chess.games

import com.yarosz.chess.SaveFile
import java.io.File

/**
 * `games.json` in [dir] (the Tool's filesDir), saved through [SaveFile]: temp + rename, a `.bak` of
 * the last good file, a corrupt file set aside. [rename] exists so a test can make it fail.
 */
class GameStore(
    dir: File,
    rename: (File, File) -> Boolean = File::renameTo,
) {
    private val file = SaveFile(dir, FILE, GameData::encode, GameData::decode, rename)

    /** The main file, else the backup, else null (no Game yet). Never throws. */
    fun load(): GameData? = file.load()

    /** Throws IOException when a write or rename fails; a main file that parsed is then as it was. */
    fun save(data: GameData) = file.save(data)

    companion object {
        const val FILE = "games.json"
    }
}
