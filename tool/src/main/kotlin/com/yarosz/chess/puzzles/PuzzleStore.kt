package com.yarosz.chess.puzzles

import com.yarosz.chess.SaveFile
import java.io.File

/**
 * `puzzles.json` in [dir] (the Tool's filesDir), saved through [SaveFile]: temp + rename, a `.bak`
 * of the last good file, a corrupt file set aside. The process has one
 * ([com.yarosz.chess.PuzzleOwner]). [rename] exists so a test can make it fail.
 */
class PuzzleStore(
    dir: File,
    rename: (File, File) -> Boolean = File::renameTo,
) {
    private val file = SaveFile(dir, FILE, PuzzleData::encode, PuzzleData::decode, rename)

    /** The main file, else the backup, else null (a first launch). Never throws. */
    fun load(): PuzzleData? = file.load()

    /** Throws IOException when a write or rename fails; a main file that parsed is then as it was. */
    fun save(data: PuzzleData) = file.save(data)

    companion object {
        const val FILE = "puzzles.json"
    }
}
