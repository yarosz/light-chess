package com.yarosz.chess.board

/**
 * Every colour the board draws, as a gray level 0-255 (D10). The palette is all gray, so a screencap
 * (taken before LightOS's grayscale filter) shows what the panel shows. Starting values from R1.7,
 * to be tuned on the LP3; [ShadesTest] enforces the contrast floors below, and DESIGN.md explains them.
 */
object Shades {
    const val LIGHT_SQUARE = 0xD8
    const val DARK_SQUARE = 0x8C

    /** Both squares of the last Move (A5), with [MARKER] corner marks on top. */
    const val LAST_MOVE = 0xB2

    /** Dots, capture rings, the check ring, the selection border, corner marks, the drag outline. */
    const val MARKER = 0x1E

    /** Coordinates, drawn in the opposite square's direction so they stay quiet but legible. */
    const val COORDINATE_ON_LIGHT = 0x5A
    const val COORDINATE_ON_DARK = 0xF0

    /** The promotion picker's cells; the rest of the board is dimmed with [MARKER] at [DIM_ALPHA]. */
    const val PICKER = 0xF0
    const val DIM_ALPHA = 0.6f

    /**
     * The body of a captured black piece in the captured-pieces row under the board (P3): the white
     * drawing with this gray body, since solid black would vanish on the black ground. The piece
     * vectors are generated with it (`scripts/build-pieces.py`, CAPTURED_BLACK_BODY).
     */
    const val CAPTURED_BLACK_BODY = 0x96

    /** The grounds a marker can be drawn on. */
    val GROUNDS = listOf(LIGHT_SQUARE, DARK_SQUARE, LAST_MOVE)

    // Contrast floors, in gray levels (D10).
    const val SQUARE_CONTRAST = 60
    const val LAST_MOVE_CONTRAST = 25
    const val MARKER_CONTRAST = 60

    /** 0xAARRGGBB for a gray [level]. */
    fun argb(level: Int, alpha: Float = 1f): Long {
        require(level in 0..255)
        val a = (alpha * 255 + 0.5f).toInt().toLong()
        return (a shl 24) or (level.toLong() shl 16) or (level.toLong() shl 8) or level.toLong()
    }
}

/**
 * Marker sizes as fractions of a square (39 dp on the LP3, R1.8). Strokes are at least [MIN_STROKE]
 * of a square (2 dp) so a marker stays readable after the grayscale filter and at arm's length.
 */
object Marks {
    const val DOT_RADIUS = 0.16f
    const val CAPTURE_RING_RADIUS = 0.46f
    const val CAPTURE_RING_STROKE = 0.08f
    const val CHECK_RING_RADIUS = 0.40f
    const val CHECK_RING_STROKE = 0.11f
    const val SELECTION_BORDER = 0.11f

    /** The Puzzle Hint (A6): a heavy ring hugging the piece to move, heavier than a capture ring. */
    const val HINT_RING_RADIUS = 0.45f
    const val HINT_RING_STROKE = 0.11f
    const val CORNER_LENGTH = 0.28f
    const val CORNER_STROKE = 0.08f
    const val DRAG_OUTLINE = 0.06f
    const val COORDINATE_SIZE = 0.26f
    const val COORDINATE_INSET = 0.05f
    const val PIECE_INSET = 0.03f

    const val MIN_STROKE = 2f / 39f

    val STROKES = listOf(CAPTURE_RING_STROKE, CHECK_RING_STROKE, HINT_RING_STROKE, SELECTION_BORDER, CORNER_STROKE, DRAG_OUTLINE)
}
