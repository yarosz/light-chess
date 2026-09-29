package com.yarosz.chess

import android.util.Log
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The Tool's modes (D6): Puzzles (v1), playing the computer (v2) and playing a friend (v3, W10: "friend" in mode.txt). */
enum class Mode { PUZZLES, GAME, FRIEND }

/**
 * Which mode the Tool opens on: the one LAST USED (D6). Kept in `mode.txt` in filesDir, one word, and
 * read before either mode loads, so it belongs to neither mode's file. A missing or unreadable file
 * means Puzzles. Process-wide, like the modes' owners.
 */
class ModeOwner private constructor(dir: File) {
    private val file = File(dir, FILE)
    private val temp = File(dir, "$FILE.tmp")
    private val modes = MutableStateFlow(read())

    val mode: StateFlow<Mode> = modes

    fun set(mode: Mode) {
        if (modes.value == mode) return
        modes.value = mode
        runCatching {
            temp.writeText(mode.name)
            if (!temp.renameTo(file)) error("rename failed")
        }.onFailure { Log.w("Chess", "$FILE save failed", it) }
    }

    private fun read(): Mode = runCatching { Mode.valueOf(file.readText().trim()) }.getOrDefault(Mode.PUZZLES)

    companion object {
        const val FILE = "mode.txt"
        private val owners = HashMap<String, ModeOwner>()

        fun of(filesDir: File): ModeOwner =
            synchronized(owners) { owners.getOrPut(filesDir.canonicalPath) { ModeOwner(filesDir) } }
    }
}
