package com.yarosz.chess

import com.yarosz.chess.puzzles.AttemptState
import com.yarosz.chess.rules.Side

/** Every piece of UI copy, in one place (F11: English only). DESIGN.md quotes each string. */
object UiCopy {
    const val BOARD_DESCRIPTION = "Chess board"
    const val WHITE_TO_MOVE = "White to move"
    const val BLACK_TO_MOVE = "Black to move"
    const val LATEST = "Latest"
    const val LATEST_DESCRIPTION = "Back to the latest position"

    // The puzzle strip (A3, A5, A6, D1, F6).
    const val FIRST_PUZZLE = "Tap a piece, then a square"
    const val TRY_AGAIN = "Try again"
    const val CORRECT = "Correct"
    const val SOLUTION_PLAYING = "Solution"
    const val HINT = "Hint"
    const val HINT_DESCRIPTION = "Puzzle Hint: mark the piece to move"
    const val SOLUTION = "Solution"
    const val SOLUTION_DESCRIPTION = "Play the Solution"
    const val NEXT = "Next"
    const val NEXT_DESCRIPTION = "Next Puzzle"
    const val MENU = "Menu"
    const val MENU_DESCRIPTION = "Open the Menu"
    const val UNRATED_SOLVED = "Solved, unrated"
    const val UNRATED_HINTED = "Hinted, unrated"
    const val UNRATED_FAILED = "Failed, unrated"
    const val PACK_FINISHED = "Every Puzzle is finished"

    // The seed screen (D4, F6): four rows and Skip.
    const val SEED_QUESTION = "How well do you play chess?"
    val SEED_ROWS = listOf(
        "I'm new to chess" to 800.0,
        "I play now and then" to 1200.0,
        "I play often and study the game" to 1600.0,
        "I play in a club or in tournaments" to 2000.0,
    )
    const val SEED_SKIP = "Skip"
    const val SEED_SKIP_RATING = 1500.0

    // The Menu (A10, F4, F5, F11).
    const val MENU_TITLE = "Menu"
    const val MISSED = "Missed"
    const val ABOUT = "About"
    const val PLAYER_RATING = "Player Rating"
    const val RESET_RATING = "Reset rating"
    const val RESET_CONFIRM = "Tap again to reset"
    const val NO_HISTORY = "No rated Puzzles yet"
    const val NO_MISSED = "Nothing missed yet"
    const val BACK_DESCRIPTION = "Back"

    /** The Tool's version; `ToolMetadataTest` holds it equal to `versionName` in `tool/lighttool.toml`. */
    const val VERSION = "0.1.0"
    const val SOURCE = "github.com/yarosz/light-chess"

    /**
     * The one-line privacy statement (D9). Not "no network permission": the Tool declares none, but
     * Light's SDK libraries merge INTERNET into every Tool's manifest (`scripts/release-check.sh apk`).
     */
    const val PRIVACY = "Chess never uses the network. Nothing leaves this phone."

    const val ABOUT_COPYRIGHT = "Copyright 2026 Nicolas Yarosz."
    const val ABOUT_LICENCE = "Free software under the GNU General Public License, version 3 or later, with no warranty."

    /** The legal notices About shows after its own copy: verbatim licence text, kept out of code. */
    const val NOTICES_ASSET = "about/notices.txt"

    /**
     * The About page (D7), one plain-text paragraph per entry. [packDate] is the Lichess dump the Pack
     * was built from (`source.date` in the Pack manifest); [notices] is [NOTICES_ASSET]'s text, whose
     * paragraphs (split at blank lines) follow: the pieces' CC0 credit and the release APK's libraries.
     */
    fun about(packDate: String?, notices: String): List<String> = listOf(
        "Chess $VERSION",
        ABOUT_COPYRIGHT,
        ABOUT_LICENCE,
        "Source: $SOURCE",
        PRIVACY,
        if (packDate == null) "Puzzles: the Lichess puzzle database (lichess.org), CC0."
        else "Puzzles: the Lichess puzzle database (lichess.org), CC0, from the dump of $packDate.",
    ) + notices.split(Regex("\\n\\s*\\n")).map { it.trim().replace(Regex("\\s*\\n\\s*"), " ") }.filter { it.isNotEmpty() }

    fun toMove(side: Side) = if (side == Side.WHITE) WHITE_TO_MOVE else BLACK_TO_MOVE

    /** The strip's status in Review: the Ply shown and the latest one. */
    fun review(ply: Int, latest: Int) = "Review · $ply of $latest"

    /** A signed rating change: "+12", "−9" (a real minus sign), "+0". */
    fun delta(delta: Int) = if (delta < 0) "−${-delta}" else "+$delta"

    /** The result strip (D1): "Solved +12", "Failed −9", "Solved, unrated". */
    fun result(state: AttemptState, rated: Boolean, delta: Int?, solutionShown: Boolean): String = when {
        state == AttemptState.HINTED -> if (solutionShown) UNRATED_HINTED else UNRATED_SOLVED
        !rated -> if (state == AttemptState.SOLVED) UNRATED_SOLVED else UNRATED_FAILED
        state == AttemptState.SOLVED -> "Solved ${delta(delta ?: 0)}"
        else -> "Failed ${delta(delta ?: 0)}"
    }

    /** A Rating screen row (F11): the Puzzle Rating, the result and the change. */
    fun historyRow(puzzleRating: Int, state: AttemptState, delta: Int, solutionShown: Boolean) =
        "$puzzleRating · " + result(state, rated = true, delta = delta, solutionShown = solutionShown)

    /** A Missed row: the Puzzle Rating and how it was missed. */
    fun missedRow(puzzleRating: Int, state: AttemptState) =
        "$puzzleRating · " + if (state == AttemptState.HINTED) "Hinted" else "Failed"

    fun ratingRow(text: String) = "$PLAYER_RATING · $text"

    fun missedCount(count: Int) = "$MISSED · $count"

    /**
     * The Menu's last row (A9 with D7, "v1 smoke fixes"): the Puzzle on screen by its Lichess id, then
     * its page on lichess.org on a line of its own, since Android would otherwise break the address
     * at a slash. Text, not a link: the phone has no browser and Chess never uses the network (D5).
     */
    fun puzzleRow(id: String) = "Puzzle $id\nlichess.org/training/$id"
}
