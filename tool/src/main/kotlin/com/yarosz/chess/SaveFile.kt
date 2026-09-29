package com.yarosz.chess

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * One saved-state file [name] in [dir] (the Tool's filesDir, D6), saved so that a kill at any moment
 * leaves a readable file, as Reader saves its reading data (its ADR 0002). A save writes the new text
 * to a temp file, syncs it and renames it over the main file in one step. Before that, a main file
 * that [decode]s becomes `<name>.bak` the same way; one that doesn't is set aside as
 * `<name>.corrupt` and never replaces a good backup. [load] reads the main file, else the backup.
 * Saves are exclusive per instance; each file has one owner in the process. [rename] exists so a test
 * can make it fail.
 */
class SaveFile<T : Any>(
    dir: File,
    private val name: String,
    private val encode: (T) -> String,
    private val decode: (String) -> T?,
    private val rename: (File, File) -> Boolean = File::renameTo,
) {
    private val main = File(dir, name)
    private val backup = File(dir, "$name.bak")
    private val corrupt = File(dir, "$name.corrupt")
    private val temp = File(dir, "$name.tmp")
    private val backupTemp = File(dir, "$name.bak.tmp")

    /** The main file, else the backup, else null (a first launch). Never throws. */
    fun load(): T? = read(main) ?: read(backup)

    /** Throws IOException when a write or rename fails; a main file that decoded is then as it was. */
    @Synchronized
    fun save(data: T) {
        val text = readText(main)
        if (text != null) {
            if (decode(text) != null) replace(backupTemp, backup, text)
            else if (!rename(main, corrupt)) throw IOException("couldn't set $name aside")
        }
        replace(temp, main, encode(data))
    }

    private fun replace(via: File, target: File, text: String) {
        FileOutputStream(via).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!rename(via, target)) throw IOException("couldn't rename ${via.name} to ${target.name}")
    }

    private fun read(file: File): T? = readText(file)?.let(decode)

    private fun readText(file: File): String? = runCatching { file.takeIf { it.exists() }?.readText(Charsets.UTF_8) }.getOrNull()
}
