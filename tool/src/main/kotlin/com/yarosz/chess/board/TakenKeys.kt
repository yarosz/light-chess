package com.yarosz.chess.board

/**
 * The keys whose key-down a screen took, so that their repeats and key-up stay with the screen too
 * (R4.17).
 *
 * LightActivity asks the screen about a key's down, repeats and up separately, and passes every
 * LightDeviceKeys key the screen doesn't take to LightOS, which toggles the flashlight on a wheel
 * click and changes the brightness on a turn. A screen that takes only the down would still hand
 * LightOS the up. So a taken down makes its up taken, and a key the screen left alone goes to LightOS
 * whole: down, repeats and up. An up with no down seen (the down went to another screen) isn't taken.
 */
class TakenKeys {
    private val taken = mutableSetOf<Int>()

    /**
     * A key-down. A first press asks [take] and remembers the answer; a repeat ([repeat]) gets the
     * press's answer without asking again, so holding a key doesn't act twice.
     */
    fun down(keyCode: Int, repeat: Boolean, take: () -> Boolean): Boolean {
        if (repeat) return keyCode in taken
        val took = take()
        if (took) taken += keyCode else taken -= keyCode
        return took
    }

    /** A repeat reported on its own (KeyEvent.ACTION_MULTIPLE): follows the press. */
    fun repeat(keyCode: Int): Boolean = keyCode in taken

    /** A key-up: taken if its press was, and the press is forgotten. */
    fun up(keyCode: Int): Boolean = taken.remove(keyCode)
}
