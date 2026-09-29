package com.yarosz.chess.board

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The player's chosen look for the pieces (CONTEXT.md, P2), drawn from `art/pieces/geometric` or
 * `art/pieces/rounded`. It changes nothing about play. The save file writes these names.
 */
@Serializable
enum class PieceSet {
    @SerialName("geometric") GEOMETRIC,
    @SerialName("rounded") ROUNDED;

    /** The Menu row's next choice: each tap moves to the next set, and the last wraps to the first. */
    val next: PieceSet get() = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = GEOMETRIC
    }
}
