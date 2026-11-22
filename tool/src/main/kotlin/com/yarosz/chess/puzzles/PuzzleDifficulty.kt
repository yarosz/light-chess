package com.yarosz.chess.puzzles

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Puzzle Difficulty (CONTEXT.md, PD2): how far from the Player Rating the next Puzzles are chosen,
 * Lichess's five steps and offsets (lila `PuzzleDifficulty.scala`). It never changes the Player
 * Rating. The save file writes these names.
 */
@Serializable
enum class PuzzleDifficulty(val offset: Int) {
    @SerialName("Easiest") EASIEST(-600),
    @SerialName("Easier") EASIER(-300),
    @SerialName("Normal") NORMAL(0),
    @SerialName("Harder") HARDER(300),
    @SerialName("Hardest") HARDEST(600);

    /** The Menu row's next choice: each tap moves one step harder, and Hardest wraps to Easiest. */
    val next: PuzzleDifficulty get() = entries[(ordinal + 1) % entries.size]

    companion object {
        /** The owner's choice (PD2), and what a file without the field reads as. */
        val DEFAULT = EASIER
    }
}
