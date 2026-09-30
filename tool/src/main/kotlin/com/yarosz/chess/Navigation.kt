package com.yarosz.chess

import com.yarosz.chess.board.PieceSet
import com.yarosz.chess.correspondence.CorrespondenceGame
import com.yarosz.chess.correspondence.HaltReason
import com.yarosz.chess.correspondence.Stage
import com.yarosz.chess.games.Confirm
import com.yarosz.chess.games.DrawResponse
import com.yarosz.chess.games.GameState
import com.yarosz.chess.games.Phase
import com.yarosz.chess.puzzles.Attempt
import com.yarosz.chess.puzzles.Glicko
import com.yarosz.chess.puzzles.HistoryEntry
import com.yarosz.chess.puzzles.MissedEntry
import com.yarosz.chess.puzzles.PuzzleState
import com.yarosz.chess.relay.EntryKind

/** Home's rows (N1, N11), in their order. */
enum class HomeEntry { PUZZLES, PLAY_COMPUTER, PLAY_FRIEND, GAMES, ABOUT }

/** One row of Home: its text, with its status ("Puzzles · 1176?"), and what it is. */
data class HomeRow(val text: String, val entry: HomeEntry)

/**
 * Home's rows (N11), as pure data so `HomeTest` checks their text and order: Puzzles with the Player
 * Rating, Play the computer, Play a friend (only while the Relay URL is set, W8), Games and About.
 * [rating] is null until `puzzles.json` is read, when the Puzzles row shows no status yet.
 */
object HomeRows {
    fun of(rating: String?, yourMove: Int?): List<HomeRow> = buildList {
        add(HomeRow(rating?.let(UiCopy::puzzlesRow) ?: UiCopy.PUZZLES, HomeEntry.PUZZLES))
        add(HomeRow(UiCopy.PLAY_COMPUTER, HomeEntry.PLAY_COMPUTER))
        if (yourMove != null) add(HomeRow(UiCopy.playFriend(yourMove), HomeEntry.PLAY_FRIEND))
        add(HomeRow(UiCopy.GAMES, HomeEntry.GAMES))
        add(HomeRow(UiCopy.ABOUT, HomeEntry.ABOUT))
    }
}

/**
 * What the Puzzles page's first row does (N12), from the Puzzle flow's state: it names the action,
 * never a Puzzle, so it can't show a wrong id.
 */
enum class PuzzlesStart {
    /** An Attempt under way on the rated Puzzle, Try Mode included: the board opens on it. */
    CONTINUE,

    /** The rated Attempt is at its Result: the next Puzzle, then the board on it. */
    NEXT,

    /** A Missed replay is on screen: it ends, and the board opens on the rated Puzzle. */
    BACK_TO_RATED,

    /**
     * Before the seed screen is answered, or after Reset rating: the board, which asks it (D4). A Missed
     * replay started meanwhile ends first.
     */
    START,

    /** The Pack is used up: a plain line, a Missed replay or not. */
    FINISHED,
}

/** The Puzzles page's rows after the first (N12), in their order. */
enum class PuzzlesEntry { START, MISSED, PAST_PUZZLES, PLAYER_RATING }

/** One row of the Puzzles page: its text, what it is, and whether it can be tapped. */
data class PuzzlesRow(val text: String, val entry: PuzzlesEntry, val tappable: Boolean = true)

/**
 * The Puzzles page (N12), as pure data for `PuzzlesPageTest`: the first row from [start], then
 * "Missed · 3", "Past Puzzles" and "Player Rating · 1176?". [session] is null until `puzzles.json` is
 * read: the first row then reads "Continue Puzzle" (the board waits for the file) and the others
 * show no status.
 */
object PuzzlesRows {
    /**
     * The first row's action. Before the seed is answered it is Start even over a Missed replay: the
     * tap ends the replay and the board asks the seed, which a replay (unrated) doesn't wait for. With
     * the Pack used up it is the finished line even over a replay: there is no rated Puzzle to go
     * back to, and the replay, never saved, is left where it is (a Missed tap replaces it).
     */
    fun start(session: PuzzleState?): PuzzlesStart {
        if (session == null) return PuzzlesStart.CONTINUE
        val current = session.current
        return when {
            session.needsSeed -> PuzzlesStart.START
            current == null -> PuzzlesStart.FINISHED
            session.replay != null -> PuzzlesStart.BACK_TO_RATED
            current.underWay -> PuzzlesStart.CONTINUE
            else -> PuzzlesStart.NEXT
        }
    }

    /**
     * The page's rows. [gone]: the Missed Puzzles the Pack no longer has ([PuzzleOwner.missedGone]),
     * which Missed's count leaves out, as it counts only the rows that replay (N13).
     */
    fun of(session: PuzzleState?, gone: Set<String> = emptySet()): List<PuzzlesRow> {
        val start = start(session)
        val data = session?.data
        return listOf(
            PuzzlesRow(UiCopy.puzzlesStart(start), PuzzlesEntry.START, tappable = start != PuzzlesStart.FINISHED),
            PuzzlesRow(data?.missed?.count { it.id !in gone }?.let(UiCopy::missedCount) ?: UiCopy.MISSED, PuzzlesEntry.MISSED),
            PuzzlesRow(UiCopy.PAST_PUZZLES, PuzzlesEntry.PAST_PUZZLES),
            PuzzlesRow(data?.player?.text?.let(UiCopy::ratingRow) ?: UiCopy.PLAYER_RATING, PuzzlesEntry.PLAYER_RATING),
        )
    }

    /** Where a row leads (N16): the first row to the Puzzle board, the others to their pages. */
    fun open(entry: PuzzlesEntry): Place = when (entry) {
        PuzzlesEntry.START -> Place.PUZZLE
        PuzzlesEntry.MISSED -> Place.MISSED
        PuzzlesEntry.PAST_PUZZLES -> Place.PAST_PUZZLES
        PuzzlesEntry.PLAYER_RATING -> Place.PLAYER_RATING
    }
}

/** The Player Rating page's one tappable row (N15). */
enum class PlayerRatingEntry { RESET }

/**
 * The pages under the Puzzles page (N13-N15), as pure data for `PuzzlesPageTest`.
 */
object PuzzlesPages {
    /**
     * The Player Rating page (N15, F5): the rating, the static line while it is provisional, and Reset
     * rating, which asks for a second tap in place ([confirming]).
     */
    fun rating(player: Glicko, confirming: Boolean): List<MenuItem<PlayerRatingEntry>> = buildList {
        add(MenuItem(player.text))
        if (player.provisional) add(MenuItem(UiCopy.PROVISIONAL_NOTE, lighten = true))
        add(MenuItem(if (confirming) UiCopy.RESET_CONFIRM else UiCopy.RESET_RATING, PlayerRatingEntry.RESET))
    }

    /**
     * Missed (D2, N13): one row per Puzzle, newest first, its entry the Lichess id to replay. A Puzzle
     * the Pack no longer has ([gone]) is a lightened line with no entry.
     */
    fun missed(missed: List<MissedEntry>, gone: Set<String>): List<MenuItem<String>> =
        if (missed.isEmpty()) listOf(MenuItem(UiCopy.NO_MISSED, lighten = true))
        else missed.map { entry ->
            val text = UiCopy.missedRow(entry.puzzleRating, entry.state)
            if (entry.id in gone) MenuItem(text, lighten = true) else MenuItem(text, entry.id)
        }

    /** Past Puzzles (N14): the rated Attempts, newest first, as lightened lines; nothing to tap. */
    fun past(history: List<HistoryEntry>): List<MenuItem<Nothing>> =
        if (history.isEmpty()) listOf(MenuItem(UiCopy.NO_HISTORY, lighten = true))
        else history.map { MenuItem(UiCopy.historyRow(it.puzzleRating, it.state, it.delta, it.solutionShown), lighten = true) }
}

/**
 * The places and pages of Chess (N2, N16): Home is the root; a place is one step from it (the Puzzles
 * page, the computer's board, Play a friend, Games, About); a page or board one step further (the
 * Puzzle board, Missed, Past Puzzles, Player Rating, a replayed Game, a Correspondence Game's board);
 * and a detail over that (a board's Menu and its pages).
 */
enum class Place { HOME, PUZZLES, PUZZLE, COMPUTER, NEW_GAME, PLAY_FRIEND, PLAYER_RATING, MISSED, PAST_PUZZLES, GAMES, ABOUT }

/** Where Chess opens and where Home's rows lead (N1, N2, N11, N16), as pure data for `HomeTest`. */
object Navigation {
    /**
     * The back stack Chess opens on: Home, and on top of it the place last used (`mode.txt`, R4.10), so
     * back walks down to Home. Puzzles opens the Puzzles page with the board over it, so a cold start
     * still lands on the board (A10) and back goes to the page, then Home (N16). The friend mode shows
     * the Puzzle while the Relay URL is empty (Y6).
     */
    fun launch(mode: Mode, friendsOn: Boolean): List<Place> = when {
        mode == Mode.GAME -> listOf(Place.HOME, Place.COMPUTER)
        mode == Mode.FRIEND && friendsOn -> listOf(Place.HOME, Place.PLAY_FRIEND)
        else -> listOf(Place.HOME, Place.PUZZLES, Place.PUZZLE)
    }

    /**
     * Where a Home row leads (N11): Puzzles to its page, not the board. Play the computer returns to
     * the Game in progress, else opens the new-game page (R4.11).
     */
    fun open(entry: HomeEntry, gameInProgress: Boolean): Place = when (entry) {
        HomeEntry.PUZZLES -> Place.PUZZLES
        HomeEntry.PLAY_COMPUTER -> if (gameInProgress) Place.COMPUTER else Place.NEW_GAME
        HomeEntry.PLAY_FRIEND -> Place.PLAY_FRIEND
        HomeEntry.GAMES -> Place.GAMES
        HomeEntry.ABOUT -> Place.ABOUT
    }

    /**
     * The mode a place writes to `mode.txt` as it opens (N2, N16): the Puzzles page and the Puzzle
     * board write Puzzles, the computer's board and Play a friend theirs; the pages write none.
     */
    fun mode(place: Place): Mode? = when (place) {
        Place.PUZZLES, Place.PUZZLE -> Mode.PUZZLES
        Place.COMPUTER -> Mode.GAME
        Place.PLAY_FRIEND -> Mode.FRIEND
        else -> null
    }

    /**
     * The place that takes a page's place once it has done its job (N2, N16): Start puts the
     * computer's board where the new-game page was, a Missed replay and Reset rating the Puzzle board
     * where their page was. Null: the page only goes back.
     */
    fun replacedBy(place: Place): Place? = when (place) {
        Place.NEW_GAME -> Place.COMPUTER
        Place.PLAYER_RATING, Place.MISSED -> Place.PUZZLE
        else -> null
    }
}

/**
 * One level's side of the back stack (N2, N16), apart from the screens so `HomeTest` drives it on a
 * stand-in stack. Home has one, and so does the Puzzles page: each opens its places over itself.
 * [push] puts a place's screen over the top one, with what to run once that page goes back with a
 * result (null: nothing); [setMode] writes `mode.txt`. Nothing either opens sits over the computer's
 * board, so none of their pages lets the computer think (N2): [pushPlace] opens them all that way.
 */
class HomeNavigator(
    private val setMode: (Mode) -> Unit,
    private val push: (Place, onDone: (() -> Unit)?) -> Unit,
) {
    /** The launch stack over Home, as it stands: read from `mode.txt`, never written back. */
    fun launch(mode: Mode, friendsOn: Boolean) {
        for (place in Navigation.launch(mode, friendsOn).drop(1)) open(place, writeMode = false)
    }

    /** Opens [place] over the top screen, writing its mode unless [writeMode] is false (the launch's). */
    fun open(place: Place, writeMode: Boolean = true) {
        if (place == Place.HOME) return
        if (writeMode) Navigation.mode(place)?.let(setMode)
        val next = Navigation.replacedBy(place)
        push(place, next?.let { { open(it) } })
    }
}

/**
 * One row of a Menu page: tappable when it has an [entry], else a plain line ([lighten]ed when it can't
 * act now). [gap]: set apart from the row above by the Menu's section spacing (N17's Pieces row).
 * [pieces]: the Pieces row, which draws that set beside its text (N19).
 */
data class MenuItem<A>(
    val text: String,
    val entry: A? = null,
    val lighten: Boolean = false,
    val gap: Boolean = false,
    val pieces: PieceSet? = null,
)

/** The Pieces row (N17, P2): "Pieces · Geometric", with the set drawn beside it (N19). */
fun <A> piecesItem(set: PieceSet, entry: A, gap: Boolean = false) = MenuItem(UiCopy.piecesRow(set), entry, gap = gap, pieces = set)

/** The Puzzle board's and a replay's Menu rows (N17): only Pieces is tappable. */
enum class PiecesMenuEntry { PIECES }

/**
 * The Puzzle board's Menu (N17, N18): Pieces, then the Puzzle on screen as two grey lines, its id and
 * its lichess.org address, text only (D5). [attempt] is the Attempt on screen, a Missed replay's own;
 * [pieceSet] null (not read yet, M4) leaves Pieces out.
 */
object PuzzleMenu {
    fun of(attempt: Attempt?, pieceSet: PieceSet?): List<MenuItem<PiecesMenuEntry>> = buildList {
        if (pieceSet != null) add(piecesItem(pieceSet, PiecesMenuEntry.PIECES))
        attempt?.puzzle?.id?.let { id -> for (line in UiCopy.puzzleLines(id)) add(MenuItem<PiecesMenuEntry>(line, lighten = true)) }
    }
}

/** A replayed Game's Menu (N17): Pieces alone. */
object GameReviewMenu {
    fun of(pieceSet: PieceSet?): List<MenuItem<PiecesMenuEntry>> = listOfNotNull(pieceSet?.let { piecesItem(it, PiecesMenuEntry.PIECES) })
}

/** The computer's Menu rows (N5, N17). */
enum class GameMenuEntry { OFFER_DRAW, RESIGN, TAKEBACK, FLIP, MOVES, THINK_TIME, NEW_GAME, PIECES }

/**
 * The Menu of a Game against the computer (B5, D10, contradiction 2, N5, N17): this board's actions,
 * top to bottom Offer draw (or why not now), Resign, Takeback, Flip board, Moves, Think Time at Level
 * 8, New game, then how it is drawn: Pieces last, set apart, so a wheel over-scroll lands on it and
 * not on an action. [pieceSet] null (not read yet, M4) leaves Pieces out. No place: those are on Home.
 */
object GameMenu {
    fun of(state: GameState, pieceSet: PieceSet? = null): List<MenuItem<GameMenuEntry>> = buildList {
        val record = state.record ?: return@buildList
        if (state.phase != Phase.OVER) {
            add(
                when {
                    state.canOfferDraw -> MenuItem(UiCopy.OFFER_DRAW, GameMenuEntry.OFFER_DRAW)
                    state.drawResponse == DrawResponse.DECLINED -> MenuItem(UiCopy.DRAW_DECLINED)
                    state.phase == Phase.COMPUTER -> MenuItem(UiCopy.OFFER_DRAW_ON_YOUR_MOVE, lighten = true)
                    else -> MenuItem(UiCopy.offerDrawFrom(state.drawOfferFromMove), lighten = true)
                },
            )
            add(MenuItem(if (state.confirming == Confirm.RESIGN) UiCopy.RESIGN_CONFIRM else UiCopy.RESIGN, GameMenuEntry.RESIGN))
            if (state.canTakeBack) add(MenuItem(UiCopy.TAKEBACK, GameMenuEntry.TAKEBACK))
        }
        add(MenuItem(UiCopy.FLIP_BOARD, GameMenuEntry.FLIP))
        add(MenuItem(UiCopy.MOVES, GameMenuEntry.MOVES))
        if (record.level == 8) add(MenuItem(UiCopy.thinkTimeRow(thinkTimeSeconds(state)), GameMenuEntry.THINK_TIME))
        add(MenuItem(UiCopy.NEW_GAME, GameMenuEntry.NEW_GAME))
        if (pieceSet != null) add(piecesItem(pieceSet, GameMenuEntry.PIECES, gap = true))
    }

    /** Level 8's Think Time for this Game: its own, else the new-game choice. */
    fun thinkTimeSeconds(state: GameState): Int = state.record?.thinkTimeSeconds ?: state.data.choices.thinkTimeSeconds
}

/** A Correspondence Game's Menu rows (N5, N17). */
enum class FriendMenuEntry { SEND_AND_OFFER_DRAW, OFFER_DRAW, RESIGN, MOVES, RENAME, FORGET, PIECES }

/**
 * A Correspondence Game's Menu, or an invite's (W2, W4, W6, W13, N5, N17): why it stopped, then the
 * Game's actions (Send and offer draw, or Offer draw, Resign), Moves, Rename, and Forget game once it
 * is over or stopped, then Pieces last and set apart, only over the Game's board ([pieceSet] non-null:
 * the invite page and the Play a friend list draw no board). No Game Hint, no Takeback, no flip (W10,
 * G3), and no place (N5). [confirming] is the second tap the page waits for.
 */
object FriendMenu {
    fun of(
        game: CorrespondenceGame,
        state: FriendState,
        confirming: FriendConfirm?,
        pieceSet: PieceSet? = null,
    ): List<MenuItem<FriendMenuEntry>> = buildList {
        val id = game.gameId
        val log = game.log
        when (game.halt?.reason) {
            HaltReason.OUT_OF_SYNC -> add(MenuItem(UiCopy.OUT_OF_SYNC_ROW))
            HaltReason.NEEDS_UPDATE -> add(MenuItem(UiCopy.UPDATE_CHESS_ROW))
            HaltReason.GONE -> add(MenuItem(UiCopy.GAME_DELETED_ROW))
            HaltReason.SEAT_LOST -> add(MenuItem(UiCopy.SEAT_LOST))
            null -> {}
        }
        val idle = game.halt == null && game.pending == null && id !in state.sending
        if (log != null && game.stage == Stage.ACTIVE && idle) {
            when {
                id in state.chosen -> add(MenuItem(UiCopy.SEND_AND_OFFER_DRAW, FriendMenuEntry.SEND_AND_OFFER_DRAW))
                log.draft(game.seat.side, EntryKind.DRAW_OFFER) != null -> add(MenuItem(UiCopy.OFFER_DRAW, FriendMenuEntry.OFFER_DRAW))
                game.yourMove -> add(MenuItem(UiCopy.OFFER_DRAW_AFTER_MOVE, lighten = true))
            }
            add(MenuItem(if (confirming == FriendConfirm.RESIGN) UiCopy.RESIGN_CONFIRM else UiCopy.RESIGN, FriendMenuEntry.RESIGN))
        }
        if (log != null) add(MenuItem(UiCopy.MOVES, FriendMenuEntry.MOVES))
        add(MenuItem(UiCopy.RENAME, FriendMenuEntry.RENAME))
        if (game.stage == Stage.OVER || game.halt != null) {
            add(MenuItem(if (confirming == FriendConfirm.FORGET) UiCopy.FORGET_CONFIRM else UiCopy.FORGET_GAME, FriendMenuEntry.FORGET))
        }
        if (pieceSet != null) add(piecesItem(pieceSet, FriendMenuEntry.PIECES, gap = true))
    }
}
