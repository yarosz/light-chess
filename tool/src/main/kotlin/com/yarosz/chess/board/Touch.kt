package com.yarosz.chess.board

import com.yarosz.chess.rules.Square

/** One touch gesture on the board, already mapped to squares by the board view ([MoveInput.touch]). */
sealed interface Touch {
    /** Pressed and released without moving past touch slop. */
    data class Tap(val square: Square) : Touch

    /** A press on [square] moved past touch slop. */
    data class Lift(val square: Square) : Touch

    /** The finger moved over [square] during a drag (null: off the board). */
    data class Over(val square: Square?) : Touch

    /** The finger lifted over [over] after a drag that started on [pressed]. */
    data class Release(val pressed: Square, val over: Square?) : Touch

    /** The system took the gesture away. */
    data object Cancel : Touch
}
