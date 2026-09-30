package com.yarosz.chess

import com.yarosz.chess.board.PieceSet
import com.yarosz.chess.correspondence.CorrespondenceGame
import com.yarosz.chess.correspondence.HaltReason
import com.yarosz.chess.correspondence.Stage
import com.yarosz.chess.games.Confirm
import com.yarosz.chess.games.DrawResponse
import com.yarosz.chess.games.GameState
import com.yarosz.chess.games.Phase
import com.yarosz.chess.relay.EntryKind

/** Home's rows (N1), in their order. */
enum class HomeEntry { PUZZLES, PLAYER_RATING, MISSED, PLAY_COMPUTER, PLAY_FRIEND, GAMES, PIECES, ABOUT }

/** One row of Home: its text, with its status ("Missed · 3"), and what it is. */
data class HomeRow(val text: String, val entry: HomeEntry)

/**
 * Home's rows (N1), as pure data so `HomeTest` checks their text and order: Puzzles, then the Player
 * Rating on its own row, Missed, Play the computer, Play a friend (only while the Relay URL is set,
 * W8), Games, Pieces and About. [rating] and [missed] are null until `puzzles.json` is read, when the
 * rows show no status yet; [pieceSet] null hides Pieces until then, as before (M4).
 */
object HomeRows {
    fun of(rating: String?, missed: Int?, yourMove: Int?, pieceSet: PieceSet?): List<HomeRow> = buildList {
        add(HomeRow(UiCopy.PUZZLES, HomeEntry.PUZZLES))
        add(HomeRow(rating?.let(UiCopy::ratingRow) ?: UiCopy.PLAYER_RATING, HomeEntry.PLAYER_RATING))
        add(HomeRow(missed?.let(UiCopy::missedCount) ?: UiCopy.MISSED, HomeEntry.MISSED))
        add(HomeRow(UiCopy.PLAY_COMPUTER, HomeEntry.PLAY_COMPUTER))
        if (yourMove != null) add(HomeRow(UiCopy.playFriend(yourMove), HomeEntry.PLAY_FRIEND))
        add(HomeRow(UiCopy.GAMES, HomeEntry.GAMES))
        if (pieceSet != null) add(HomeRow(UiCopy.piecesRow(pieceSet), HomeEntry.PIECES))
        add(HomeRow(UiCopy.ABOUT, HomeEntry.ABOUT))
    }
}

/**
 * The places and pages of Chess (N2): Home is the root; a place is one step from it; a detail (a
 * replayed Game, a Correspondence Game's board, a board's Menu) one step further.
 */
enum class Place { HOME, PUZZLE, COMPUTER, NEW_GAME, PLAY_FRIEND, PLAYER_RATING, MISSED, GAMES, ABOUT }

/** Where Chess opens and where Home's rows lead (N1, N2), as pure data for `HomeTest`. */
object Navigation {
    /**
     * The back stack Chess opens on: Home, and on top of it the place last used (`mode.txt`, R4.10), so
     * back from it goes to Home. The friend mode shows the Puzzle while the Relay URL is empty (Y6).
     */
    fun launch(mode: Mode, friendsOn: Boolean): List<Place> = listOf(
        Place.HOME,
        when (mode) {
            Mode.PUZZLES -> Place.PUZZLE
            Mode.GAME -> Place.COMPUTER
            Mode.FRIEND -> if (friendsOn) Place.PLAY_FRIEND else Place.PUZZLE
        },
    )

    /**
     * Where a Home row leads; null for Pieces, which changes the Piece Set in place (P2). Play the
     * computer returns to the Game in progress, else opens the new-game page (R4.11).
     */
    fun open(entry: HomeEntry, gameInProgress: Boolean): Place? = when (entry) {
        HomeEntry.PUZZLES -> Place.PUZZLE
        HomeEntry.PLAYER_RATING -> Place.PLAYER_RATING
        HomeEntry.MISSED -> Place.MISSED
        HomeEntry.PLAY_COMPUTER -> if (gameInProgress) Place.COMPUTER else Place.NEW_GAME
        HomeEntry.PLAY_FRIEND -> Place.PLAY_FRIEND
        HomeEntry.GAMES -> Place.GAMES
        HomeEntry.PIECES -> null
        HomeEntry.ABOUT -> Place.ABOUT
    }

    /** The mode a place writes to `mode.txt` as it opens (N2); the pages write none. */
    fun mode(place: Place): Mode? = when (place) {
        Place.PUZZLE -> Mode.PUZZLES
        Place.COMPUTER -> Mode.GAME
        Place.PLAY_FRIEND -> Mode.FRIEND
        else -> null
    }

    /**
     * The place that takes a page's place once it has done its job (N2): Start puts the computer's
     * board where the new-game page was, a Missed replay and Reset rating the Puzzle board. Null: the
     * page only goes back.
     */
    fun replacedBy(place: Place): Place? = when (place) {
        Place.NEW_GAME -> Place.COMPUTER
        Place.PLAYER_RATING, Place.MISSED -> Place.PUZZLE
        else -> null
    }
}

/**
 * Home's side of the back stack (N2), apart from the screens so `HomeTest` drives it on a stand-in
 * stack. [push] puts a place's screen over the top one, with what to run once that page goes back
 * with a result (null: nothing); [setMode] writes `mode.txt`.
 */
class HomeNavigator(private val setMode: (Mode) -> Unit, private val push: (Place, onDone: (() -> Unit)?) -> Unit) {
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

/** One row of a Menu page: tappable when it has an [entry], else a plain line ([lighten]ed when it can't act now). */
data class MenuItem<A>(val text: String, val entry: A? = null, val lighten: Boolean = false)

/** The computer's Menu rows (N5). */
enum class GameMenuEntry { OFFER_DRAW, RESIGN, TAKEBACK, FLIP, MOVES, THINK_TIME, NEW_GAME }

/**
 * The Menu of a Game against the computer (B5, D10, contradiction 2, N5): only this board's actions,
 * top to bottom Offer draw (or why not now), Resign, Takeback, Flip board, Moves, Think Time at Level
 * 8, New game. No place and no setting: those are on Home.
 */
object GameMenu {
    fun of(state: GameState): List<MenuItem<GameMenuEntry>> = buildList {
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
    }

    /** Level 8's Think Time for this Game: its own, else the new-game choice. */
    fun thinkTimeSeconds(state: GameState): Int = state.record?.thinkTimeSeconds ?: state.data.choices.thinkTimeSeconds
}

/** A Correspondence Game's Menu rows (N5). */
enum class FriendMenuEntry { SEND_AND_OFFER_DRAW, OFFER_DRAW, RESIGN, MOVES, RENAME, FORGET }

/**
 * A Correspondence Game's Menu, or an invite's (W2, W4, W6, W13, N5): why it stopped, then the Game's
 * actions (Send and offer draw, or Offer draw, Resign), Moves, Rename, and Forget game once it is over
 * or stopped. No Game Hint, no Takeback, no flip (W10, G3), and no place or setting (N5). [confirming]
 * is the second tap the page waits for.
 */
object FriendMenu {
    fun of(game: CorrespondenceGame, state: FriendState, confirming: FriendConfirm?): List<MenuItem<FriendMenuEntry>> = buildList {
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
    }
}
