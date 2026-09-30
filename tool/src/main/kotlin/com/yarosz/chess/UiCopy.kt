package com.yarosz.chess

import com.yarosz.chess.board.PieceSet
import com.yarosz.chess.correspondence.Refusal
import com.yarosz.chess.puzzles.AttemptState
import com.yarosz.chess.rules.CapturedPieces
import com.yarosz.chess.rules.DrawReason
import com.yarosz.chess.rules.PieceType
import com.yarosz.chess.rules.Result
import com.yarosz.chess.rules.Side
import com.yarosz.chess.rules.WinReason

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
    const val PIECES = "Pieces"
    const val PLAYER_RATING = "Player Rating"
    const val RESET_RATING = "Reset rating"
    const val RESET_CONFIRM = "Tap again to reset"
    const val NO_HISTORY = "No rated Puzzles yet"
    const val NO_MISSED = "Nothing missed yet"
    const val BACK_DESCRIPTION = "Back"

    // The game screen's strip (contradictions 2 and 3, B5, G1).
    const val YOUR_MOVE = "Your move"
    const val THINKING = "Thinking"
    const val FINDING_HINT = "Finding a Game Hint"
    const val TAKEBACK = "Takeback"
    const val GAME_HINT_DESCRIPTION = "Game Hint: show the computer's best Move"
    const val MOVE_NOW = "Move now"
    const val MOVE_NOW_DESCRIPTION = "Move now: the computer plays at once"
    const val NEW_GAME_DESCRIPTION = "Start a new game"
    const val BACK = "Back"
    const val GAMES_BACK_DESCRIPTION = "Back to the Games"
    const val DRAW_AGREED = "Draw agreed"
    const val DRAW_DECLINED = "Draw declined"
    const val UNFINISHED = "Unfinished"

    // The Menu in the game mode (D6, B5, F11).
    const val PLAY_COMPUTER = "Play the computer"
    const val PUZZLES = "Puzzles"
    const val NEW_GAME = "New game"
    const val OFFER_DRAW = "Offer draw"
    const val OFFER_DRAW_ON_YOUR_MOVE = "Offer draw on your move"
    const val RESIGN = "Resign"
    const val RESIGN_CONFIRM = "Tap again to resign"
    const val FLIP_BOARD = "Flip board"
    const val MOVES = "Moves"
    const val NO_MOVES = "No Moves yet"
    const val GAMES = "Games"
    const val NO_GAMES = "No finished Games yet"
    const val THINK_TIME = "Think Time"
    const val LEVEL = "Level"
    const val PLAY_AS = "Play as"
    const val WHITE = "White"
    const val BLACK = "Black"
    const val RANDOM = "Random"
    const val START = "Start"
    const val REPLACE_CONFIRM = "Tap again to replace"
    const val REPLACE_NOTE = "The Game in progress is saved as unfinished."

    /** The Tool's version; `ToolMetadataTest` holds it equal to `versionName` in `tool/lighttool.toml`. */
    const val VERSION = "0.3.1"
    const val SOURCE = "github.com/yarosz/light-chess"

    /**
     * The one-line privacy statement (D9) while the Relay URL is empty (W8): no Correspondence Game can
     * start, so nothing leaves the phone. Not "no network permission": Light's SDK libraries merge
     * INTERNET into every Tool's manifest, and Chess declares it too for the Relay (ADR 0004).
     */
    const val PRIVACY = "Chess never uses the network. Nothing leaves this phone."

    const val ABOUT_COPYRIGHT = "Copyright 2026 Nicolas Yarosz."
    const val ABOUT_LICENCE = "Free software under the GNU General Public License, version 3 or later, with no warranty."

    /** The engine's credit (D7, R1.3). */
    const val ABOUT_ENGINE = "Engine: Pirarucu by Raoni Campos (ratosh), GPL-3.0."

    /** The opening Book's credit (book ruling 1, docs/book.md). */
    const val ABOUT_BOOK = "Opening book: the Lichess games database (lichess.org), CC0, January 2018."

    /** The legal notices About shows after its own copy: verbatim licence text, kept out of code. */
    const val NOTICES_ASSET = "about/notices.txt"

    /**
     * The About page (D7), one plain-text paragraph per entry. [packDate] is the Lichess dump the Pack
     * was built from (`source.date` in the Pack manifest); [notices] is [NOTICES_ASSET]'s text, whose
     * paragraphs (split at blank lines) follow: the pieces' CC0 credit and the release APK's libraries.
     * [friends] is whether Play a friend is on (the Relay URL is set, W1/W8), which changes the privacy line.
     */
    fun about(packDate: String?, notices: String, friends: Boolean = false): List<String> = listOf(
        "Chess $VERSION",
        ABOUT_COPYRIGHT,
        ABOUT_LICENCE,
        "Source: $SOURCE",
        if (friends) PRIVACY_FRIENDS else PRIVACY,
        if (packDate == null) "Puzzles: the Lichess puzzle database (lichess.org), CC0."
        else "Puzzles: the Lichess puzzle database (lichess.org), CC0, from the dump of $packDate.",
        ABOUT_ENGINE,
        ABOUT_BOOK,
    ) + notices.split(Regex("\\n\\s*\\n")).map { it.trim().replace(Regex("\\s*\\n\\s*"), " ") }.filter { it.isNotEmpty() }

    // Playing a friend (v3 PR 2: W1, W2, W4, W6, W10). Shown only once the Relay URL is set (W8).

    /** The privacy line once the Relay URL is set (W1, ADR 0004; approved 2026-09-28, docs/privacy.md). */
    const val PRIVACY_FRIENDS = "Chess uses the network only for Games with a friend: it sends their Moves to its Relay, " +
        "with no name or account, and the Relay deletes each Game within 30 days of the last thing either phone sent it. " +
        "Puzzles and Games against the computer never leave this phone."
    const val PLAY_FRIEND = "Play a friend"
    const val ENTER_CODE = "Enter code"
    const val DAYS_PER_MOVE = "Days per move"
    const val CREATE_CODE = "Create code"
    const val CODE_NOTE = "Tell your friend this code. It works once, for 48 hours."
    const val JOIN = "Join"
    const val NO_FRIEND_GAMES = "No games yet. Create a code for a friend, or enter theirs."
    const val SEND = "Send"
    const val SEND_DESCRIPTION = "Send this Move"
    const val UNDO = "Undo"
    const val UNDO_DESCRIPTION = "Take this Move back before it is sent"
    const val SENDING = "Sending"
    const val NOT_SENT = "Not sent"
    const val RETRY = "Retry"
    const val RETRY_DESCRIPTION = "Send it again"
    const val DRAW_OFFERED = "Draw offered"
    const val ACCEPT = "Accept"
    const val DECLINE = "Decline"
    const val TIME_IS_UP = "Time is up"
    const val CLAIM_WIN = "Claim win"
    const val CLAIM_WIN_DESCRIPTION = "Claim win on time"
    const val OUT_OF_SYNC = "Out of sync"
    const val OUT_OF_SYNC_ROW = "This game stopped: the two phones disagree."
    const val UPDATE_CHESS = "Update Chess"
    const val UPDATE_CHESS_ROW = "Update Chess to continue this game"
    const val GAME_DELETED = "Game deleted"
    const val GAME_DELETED_ROW = "This game was deleted. Forget it to free its place."
    const val SEAT_LOST = "Seat lost"
    const val CANCEL = "Cancel"
    const val CANCEL_DESCRIPTION = "Cancel this invite"
    const val CANCEL_CONFIRM = "Tap again to cancel"
    const val REMATCH = "Rematch"
    const val REMATCH_DESCRIPTION = "Offer a rematch, Sides swapped"
    const val REMATCH_SENT = "Rematch sent"
    const val REMATCH_OFFERED = "Rematch?"
    const val NOT_YET = "Not yet"
    const val OFFER_NOT_SENT = "Offer not sent"
    const val SEND_AND_OFFER_DRAW = "Send and offer draw"
    const val OFFER_DRAW_AFTER_MOVE = "Offer draw after your move"
    const val RENAME = "Rename"
    const val FORGET_GAME = "Forget game"
    const val FORGET_CONFIRM = "Tap again to forget"
    const val INVITE = "Invite"
    const val SAVE = "Save"

    /** W4: a Result's copy in a Correspondence Game. Next to Rematch and Menu, "Draw: insufficient material" needs three lines. */
    fun friendResult(result: Result?, userSide: Side): String =
        if (result is Result.Draw && result.by == DrawReason.INSUFFICIENT_MATERIAL) "Draw: dead position" else gameResult(result, userSide)

    /**
     * Time Left in one unit (W4, W12): hours to the nearest while under 48 are left ("47h", "5h"), days
     * to the nearest above ("2d" from 47h 30m, "3d"), and the last hour in minutes rounded up ("40m"),
     * so "0m" shows only once the Deadline has passed, as "Time is up" does. A Deadline falls a whole
     * number of days after a Move, so a fresh Move's Time Left sits in the middle of its rounding, and
     * both phones read the same through minutes of clock skew.
     */
    fun timeLeft(ms: Long): String = oneUnit(ms, days = true)

    fun yourMoveLeft(ms: Long) = "$YOUR_MOVE · ${timeLeft(ms)}"

    fun theirMoveLeft(ms: Long) = "$THEIR_MOVE · ${timeLeft(ms)}"

    const val THEIR_MOVE = "Their move"

    /** The invite's strip (W4, W12): "Expires in 48h", in hours (an invite lasts 48) to the nearest, then minutes rounded up. */
    fun expiresIn(ms: Long) = "Expires in " + oneUnit(ms, days = false)

    /**
     * W12's rounding: under an hour, minutes rounded up; then hours to the nearest, half up, while
     * under 48 would show; above that, with [days], days to the nearest, half up. Clamped to a year
     * first, so a nonsense Deadline can't overflow the sums.
     */
    private fun oneUnit(ms: Long, days: Boolean): String {
        if (ms <= 0) return "0m"
        val left = ms.coerceAtMost(365 * DAY_MS)
        val minutes = (left + MINUTE_MS - 1) / MINUTE_MS
        if (minutes < 60) return "${minutes}m"
        val hours = (left + HOUR_MS / 2) / HOUR_MS
        if (!days || hours < 48) return "${hours}h"
        val wholeDays = (left + DAY_MS / 2) / DAY_MS
        return "${wholeDays}d"
    }

    /** The Menu entry (W6): "Play a friend", or "Play a friend · Your move: 2" when that count is above 0. */
    fun playFriend(yourMove: Int) = if (yourMove > 0) "$PLAY_FRIEND · Your move: $yourMove" else PLAY_FRIEND

    /** A Play a friend row (W6): "ABCD · Your move · 2d". */
    fun friendRow(label: String, state: String) = "$label · $state"

    /** A Games row for a Correspondence Game (W5): "2026.09.28 · ABCD · Won". */
    fun friendGamesRow(date: String, label: String, result: Result?, userSide: Side): String =
        listOf(date, label, outcome(result, userSide)).joinToString(" · ")

    /**
     * What each [com.yarosz.chess.correspondence.Refusal] says (W10), in the strip for 5 s or on the page
     * that asked. Enter code's errors are W6's.
     */
    fun refusal(reason: Refusal): String = when (reason) {
        Refusal.NOT_ALLOWED -> "Not allowed now"
        Refusal.CAP_REACHED -> "Finish a game first"
        Refusal.NO_SUCH_GAME -> GAME_DELETED
        Refusal.HALTED -> "This game stopped"
        Refusal.BAD_CODE -> "Not a code"
        Refusal.INVITE_NOT_FOUND -> "No such code"
        Refusal.INVITE_USED -> "Code already used"
        Refusal.ALREADY_REDEEMED -> "Your friend joined"
        Refusal.RATE_LIMITED -> "Try again in a minute"
        Refusal.NEEDS_UPDATE -> UPDATE_CHESS
        Refusal.OFFLINE -> "No connection"
        Refusal.ROLLED_BACK -> "The game moved on"
        Refusal.TOO_EARLY -> NOT_YET
        Refusal.NOT_SAVED -> "Couldn't save"
    }

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L

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

    /** The strip's Result in a Game against the computer, from the user's view (F8: the computer never resigns). */
    fun gameResult(result: Result?, userSide: Side): String = when (result) {
        null -> UNFINISHED
        is Result.Win -> when {
            result.by == WinReason.RESIGNATION -> if (result.winner == userSide) "You won by resignation" else "You resigned"
            // A Game against the computer has no clock; a Correspondence Game's timeout has its copy below (v3 PR 2).
            result.by == WinReason.TIME -> if (result.winner == userSide) "You won on time" else "You lost on time"
            result.winner == userSide -> "You won by checkmate"
            else -> "You lost by checkmate"
        }
        is Result.Draw -> when (result.by) {
            DrawReason.AGREEMENT -> DRAW_AGREED
            DrawReason.STALEMATE -> "Draw by stalemate"
            DrawReason.REPETITION -> "Draw by repetition"
            DrawReason.FIFTY_MOVE_RULE -> "Draw by the 50-move rule"
            DrawReason.INSUFFICIENT_MATERIAL -> "Draw: insufficient material"
        }
    }

    /** A Games row (F11): "2026.09.28 · Level 3 · Won". */
    fun gamesRow(date: String, level: Int?, result: Result?, userSide: Side): String =
        listOfNotNull(date, level?.let { "$LEVEL $it" }, outcome(result, userSide)).joinToString(" · ")

    /** Won, Lost, Draw or Unfinished, from the user's view. */
    fun outcome(result: Result?, userSide: Side): String = when (result) {
        null -> UNFINISHED
        is Result.Draw -> "Draw"
        is Result.Win -> if (result.winner == userSide) "Won" else "Lost"
    }

    /** Level 8's Think Time: "3 s". */
    fun seconds(seconds: Int) = "$seconds s"

    fun thinkTimeRow(seconds: Int) = "$THINK_TIME · ${seconds(seconds)}"

    /** After an offer, the Move from which the next may come (G1). */
    fun offerDrawFrom(move: Int) = "Offer draw again at move $move"

    /** A Moves row's number: "12.". */
    fun moveNumber(number: Int) = "$number."

    fun ratingRow(text: String) = "$PLAYER_RATING · $text"

    fun missedCount(count: Int) = "$MISSED · $count"

    /** The Piece Set's name in the Menu (P2). */
    fun pieceSetName(set: PieceSet) = when (set) {
        PieceSet.GEOMETRIC -> "Geometric"
        PieceSet.ROUNDED -> "Rounded"
    }

    /** The Menu's Pieces row (P2): the Piece Set in use; a tap moves to the next one. */
    fun piecesRow(set: PieceSet) = "$PIECES · ${pieceSetName(set)}"

    /**
     * The Menu's last row (A9 with D7, "v1 smoke fixes"): the Puzzle on screen by its Lichess id, then
     * its page on lichess.org on a line of its own, since Android would otherwise break the address
     * at a slash. Text, not a link: the phone has no browser and Chess never uses the network (D5).
     */
    fun puzzleRow(id: String) = "Puzzle $id\nlichess.org/training/$id"

    /**
     * The captured-pieces row's accessibility label (P3), in the row's order, the Side at the
     * [bottom] first: "Captured by White: two pawns, a queen. Captured by Black: a knight. White is
     * ahead by 7." A Side that has taken nothing is left out; "Material is even." when neither leads.
     */
    fun capturedPieces(captured: CapturedPieces, bottom: Side): String {
        val sentences = mutableListOf<String>()
        for (side in listOf(bottom, bottom.opponent)) {
            val taken = captured.by(side)
            if (taken.isEmpty()) continue
            val kinds = CapturedPieces.ORDER.mapNotNull { kind -> taken.count { it == kind }.takeIf { it > 0 }?.let { pieceCount(it, kind) } }
            sentences += "Captured by ${sideName(side)}: ${kinds.joinToString(", ")}."
        }
        val leader = captured.leader
        sentences += if (leader == null) MATERIAL_EVEN else "${sideName(leader)} is ahead by ${kotlin.math.abs(captured.lead)}."
        return sentences.joinToString(" ")
    }

    const val MATERIAL_EVEN = "Material is even."

    fun sideName(side: Side) = if (side == Side.WHITE) WHITE else BLACK

    /** "a pawn", "two pawns", up to fifteen in words (a Side can take at most fifteen pieces). */
    private fun pieceCount(n: Int, kind: PieceType): String {
        val name = kind.name.lowercase()
        return if (n == 1) "a $name" else "${COUNT_WORDS.getOrElse(n - 1) { n.toString() }} ${name}s"
    }

    private val COUNT_WORDS = listOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
        "twelve", "thirteen", "fourteen", "fifteen",
    )
}
