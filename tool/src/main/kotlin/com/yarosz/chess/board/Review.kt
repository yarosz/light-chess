package com.yarosz.chess.board

/** The scroll wheel's three events (PLATFORM.md: KEYCODE_WHEEL_CCW 317, CW 318, CLICK 319). */
enum class Wheel {
    BACK, FORWARD, CLICK;

    companion object {
        fun of(keyCode: Int): Wheel? = when (keyCode) {
            317 -> BACK
            318 -> FORWARD
            319 -> CLICK
            else -> null
        }
    }
}

/**
 * Review with the wheel (R1.9, F2): which Ply is shown, stepping through the Moves already played
 * without changing them. [ply] is null outside Review, when the latest Position shows.
 *
 * - Back (counter-clockwise) enters Review one Ply before the latest, then steps back to the start.
 * - Forward (clockwise) steps toward the latest; reaching it leaves Review.
 * - Click returns to the latest Position.
 *
 * [wheel] returns null when the key isn't Review's to take (not in Review, or nothing to review), so
 * the screen returns false and LightOS keeps the wheel's brightness and flashlight (for the whole
 * press, down and up: R4.17, [TakenKeys]).
 */
data class Review(val ply: Int? = null) {

    val active: Boolean get() = ply != null

    fun wheel(key: Wheel, latestPly: Int): Review? = when (key) {
        Wheel.BACK -> when {
            ply != null -> Review(maxOf(0, ply - 1))
            latestPly > 0 -> Review(latestPly - 1)
            else -> null
        }
        Wheel.FORWARD -> ply?.let { if (it + 1 >= latestPly) Review() else Review(it + 1) }
        Wheel.CLICK -> ply?.let { Review() }
    }
}
