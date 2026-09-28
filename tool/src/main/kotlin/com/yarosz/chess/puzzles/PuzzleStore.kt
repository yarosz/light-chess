package com.yarosz.chess.puzzles

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * `puzzles.json` in [dir] (the Tool's filesDir), saved so that a kill at any moment leaves a
 * readable file, as Reader saves its reading data (its ADR 0002). A save writes the new text to a
 * temp file, syncs it and renames it over the main file in one step. Before that, a main file that
 * parses becomes `puzzles.json.bak` the same way; one that doesn't parse is set aside as
 * `puzzles.json.corrupt` and never replaces a good backup. [load] reads the main file, else the
 * backup. Saves are exclusive per store; the process has one ([com.yarosz.chess.PuzzleOwner]).
 * [rename] exists so a test can make it fail.
 */
class PuzzleStore(
    dir: File,
    private val rename: (File, File) -> Boolean = File::renameTo,
) {
    private val main = File(dir, FILE)
    private val backup = File(dir, "$FILE.bak")
    private val corrupt = File(dir, "$FILE.corrupt")
    private val temp = File(dir, "$FILE.tmp")
    private val backupTemp = File(dir, "$FILE.bak.tmp")

    /** The main file, else the backup, else null (a first launch). Never throws. */
    fun load(): PuzzleData? = read(main) ?: read(backup)

    /** Throws IOException when a write or rename fails; a main file that parsed is then as it was. */
    @Synchronized
    fun save(data: PuzzleData) {
        val text = readText(main)
        if (text != null) {
            if (PuzzleData.decode(text) != null) replace(backupTemp, backup, text)
            else if (!rename(main, corrupt)) throw IOException("couldn't set $FILE aside")
        }
        replace(temp, main, data.encode())
    }

    private fun replace(via: File, target: File, text: String) {
        FileOutputStream(via).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!rename(via, target)) throw IOException("couldn't rename ${via.name} to ${target.name}")
    }

    private fun read(file: File): PuzzleData? = readText(file)?.let(PuzzleData::decode)

    private fun readText(file: File): String? = runCatching { file.takeIf { it.exists() }?.readText(Charsets.UTF_8) }.getOrNull()

    companion object {
        const val FILE = "puzzles.json"
    }
}
