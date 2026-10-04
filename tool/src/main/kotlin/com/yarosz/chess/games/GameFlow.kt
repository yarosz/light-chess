package com.yarosz.chess.games

import com.yarosz.chess.book.Book
import com.yarosz.chess.book.BookPolicy
import com.yarosz.chess.engine.Level
import com.yarosz.chess.engine.LevelRequest
import com.yarosz.chess.engine.SearchLimits
import com.yarosz.chess.engine.SearchRequest
import com.yarosz.chess.engine.ThinkTime
import com.yarosz.chess.rules.DrawAcceptance
import com.yarosz.chess.rules.DrawOffer
import com.yarosz.chess.rules.DrawRefusal
import com.yarosz.chess.rules.Game
import com.yarosz.chess.rules.Move
import com.yarosz.chess.rules.PieceType
import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.Resignation
import com.yarosz.chess.rules.Side
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.serialization.Serializable

/** The new-game page's Side choice (F11: v2 settings are Level, Think Time and the Side to play). */
enum class SideChoice { WHITE, BLACK, RANDOM }

/** What the new-game page offers, remembered between Games in `games.json` (v2 PR 4). */
@Serializable
data class GameChoices(
    val level: Int = 1,
    val side: SideChoice = SideChoice.WHITE,
    /** Level 8's Think Time in seconds: 3, 10 or 30 (E2). */
    val thinkTimeSeconds: Int = 3,
) {
    /** These choices with anything out of range (an edited or newer file) put back to a default. */
    fun normalized(): GameChoices = GameChoices(
        level = level.takeIf { it in 1..8 } ?: 1,
        side = side,
        thinkTimeSeconds = thinkTimeSeconds.takeIf { s -> ThinkTime.entries.any { it.ms == s * 1000L } } ?: 3,
    )
}

/** Whose Move it is in the Game on screen, or that it is over. */
enum class Phase { USER, COMPUTER, OVER }

/** The computer's answer to the user's draw offer (G1), shown until the next Move. */
enum class DrawResponse { AGREED, DECLINED }

/** An action waiting for its second tap: Resign (B5) and replacing the Game in progress (F7, B5). */
enum class Confirm { RESIGN, REPLACE }

/**
 * Everything the game mode shows and saves, as one immutable value. [data] is `games.json`; [record]
 * is the Game on screen: the one in progress, or the last finished one with its Result, or null when
 * no Game was ever played. [hint] is a Game Hint on show and [hintPending] a search for one.
 */
data class GameState(
    val data: GameData = GameData(),
    val record: GameRecord? = null,
    val hint: Move? = null,
    val hintPending: Boolean = false,
    val drawResponse: DrawResponse? = null,
    val confirming: Confirm? = null,
) {
    val phase: Phase?
        get() = record?.let {
            when {
                it.game.isOver -> Phase.OVER
                it.game.position.sideToMove == it.userSide -> Phase.USER
                else -> Phase.COMPUTER
            }
        }

    /** A Game is in progress (B5: one at a time): starting another needs a second tap. */
    val inProgress: Boolean get() = data.current != null

    /** The Side drawn at the bottom: the user's, unless the board is flipped (D10, v2). */
    val bottom: Side get() = (record?.userSide ?: Side.WHITE).let { if (data.flipped) it.opponent else it }

    /**
     * A Takeback is possible: after the user's first Move while the Game goes on, and at a Result
     * the computer's own Move made, for the Game that ended last (X1, amending "Takeback rule
     * corrected").
     */
    val canTakeBack: Boolean
        get() {
            val record = record ?: return false
            return record.canTakeBack && (phase != Phase.OVER || data.endedLast(record))
        }

    /**
     * The Ply from which the user may offer a draw again (G1: 10 more Moves after an offer, counted as
     * 20 Plies), or 0 when no offer was made.
     */
    val drawOfferFromPly: Int
        get() {
            val record = record ?: return 0
            var ply = 0
            var from = 0
            for (event in record.game.events) {
                if (event is Move) ply++
                if (event is DrawOffer && event.side == record.userSide) from = ply + DRAW_REOFFER_PLIES
            }
            return from
        }

    /** The Move number of [drawOfferFromPly], for the Menu. */
    val drawOfferFromMove: Int
        get() {
            val start = record?.game?.start ?: Position.START
            val offset = if (start.sideToMove == Side.BLACK) 1 else 0
            return start.fullmoveNumber + (drawOfferFromPly + offset) / 2
        }

    /** The user may offer a draw: on their own Move, with the re-offer gate open (G1). */
    val canOfferDraw: Boolean
        get() = phase == Phase.USER && record!!.game.openDrawOffer == null && record.game.ply >= drawOfferFromPly

    /**
     * Keep the screen on (contradiction 4, B6): while the computer thinks, and on the user's Move while
     * the last touch or wheel event was under 5 minutes ago ([recentInput]).
     */
    fun awake(recentInput: Boolean): Boolean = phase == Phase.COMPUTER || (phase == Phase.USER && recentInput)

    companion object {
        const val DRAW_REOFFER_PLIES = 20
    }
}

/**
 * What the computer does for one Move of [game]: play [bookMove] from the Book, or search per [request].
 * [freshEngine]: forget what earlier searches learned first, so the Move depends only on the Game, its
 * Level and its seed, and a resume or a Takeback replays it exactly (Levels 1-7; Level 8 ends on the
 * clock and can't replay exactly anyway).
 */
data class ComputerReply(val game: Game, val bookMove: Move?, val request: LevelRequest, val freshEngine: Boolean)

/**
 * G1: when the computer accepts the user's draw offer, judged on the true evaluations of its own
 * searches (from its own view, oldest first), whatever Level it plays at:
 * (a) the latest is -50 cp or worse and the Game is past move 30; or
 * (b) the last three are all within 15 cp of level, the Game is at move 40 or later, and neither side
 *     has more than a rook and a minor piece besides pawns (no queens).
 * With no evaluation yet (only book Moves so far), it declines.
 */
object DrawJudge {
    fun accepts(position: Position, evals: List<Int>): Boolean {
        val last = evals.lastOrNull() ?: return false
        if (position.fullmoveNumber > 30 && last <= -50) return true
        return position.fullmoveNumber >= 40 && evals.size >= 3 && evals.takeLast(3).all { abs(it) <= 15 } &&
            Side.entries.all { material(position, it) <= ROOK + MINOR } &&
            position.pieces.none { it.second.type == PieceType.QUEEN }
    }

    /** Non-pawn material of [side], minors 3 and rooks 5; a queen counts as more than any allowance. */
    private fun material(position: Position, side: Side): Int = position.pieces.filter { it.second.side == side }.sumOf {
        when (it.second.type) {
            PieceType.KNIGHT, PieceType.BISHOP -> MINOR
            PieceType.ROOK -> ROOK
            PieceType.QUEEN -> 100
            else -> 0
        }
    }

    private const val MINOR = 3
    private const val ROOK = 5
}

/**
 * The game mode's rules over a [GameState] (B5, B6, G1, F7, contradictions 2, 3 and 6): starting a
 * Game, the turn flow, Takeback, Game Hints, draw offers, Resign, and what the computer must do next.
 * Pure: every call returns the next GameState, and the process-wide owner runs the engine and the
 * clock. A call that isn't allowed in the current state returns it unchanged.
 */
object GameFlow {

    /** The GameState for [saved] (null: none yet): the Game in progress, else the last finished one. */
    fun open(saved: GameData?): GameState {
        val data = (saved ?: GameData()).let { it.copy(choices = it.choices.normalized()) }
        val record = data.resume() ?: data.finished.firstOrNull()?.let { runCatching { Pgn.read(it.pgn) }.getOrNull() }
        return GameState(data, record)
    }

    /**
     * A new Game from [choices], its seed [seed] (Random resolves to a Side from it) and [date] (PGN
     * form). While a Game is in progress the first call only asks for the second tap ("Tap again to
     * replace"); the second saves the old Game as unfinished (*) and starts the new one (F7, B5).
     */
    fun start(state: GameState, choices: GameChoices, seed: Long, date: String): GameState {
        if (state.inProgress && state.confirming != Confirm.REPLACE) return state.copy(confirming = Confirm.REPLACE)
        val side = when (choices.side) {
            SideChoice.WHITE -> Side.WHITE
            SideChoice.BLACK -> Side.BLACK
            SideChoice.RANDOM -> if (Random(seed).nextBoolean()) Side.WHITE else Side.BLACK
        }
        val record = GameRecord(
            game = Game.of(),
            userSide = side,
            level = choices.level,
            thinkTimeSeconds = choices.thinkTimeSeconds.takeIf { choices.level == 8 },
            date = date,
            seed = seed,
        )
        return GameState(state.data.copy(choices = choices).startNew(record), record)
    }

    /** The user's [move], on the user's turn only (contradiction 3: locked while the computer thinks). */
    fun play(state: GameState, move: Move): GameState {
        val record = state.record ?: return state
        if (state.phase != Phase.USER || move !in record.game.position.legalMoves) return state
        return saved(state, record + move).copy(hint = null, hintPending = false, drawResponse = null, confirming = null)
    }

    /** What the computer must do now, or null when it isn't its Move. [book] is null when it didn't load. */
    fun computerReply(state: GameState, book: Book?): ComputerReply? {
        val record = state.record ?: return null
        if (state.phase != Phase.COMPUTER) return null
        val level = Level.of(record.level ?: 1)
        val seed = record.seed ?: 0L
        val game = record.game
        val bookMove = book?.let { BookPolicy(level.number).pick(it, game.start, game.moves, seed) }
        val thinkTime = ThinkTime.entries.firstOrNull { it.ms == (record.thinkTimeSeconds ?: 0) * 1000L } ?: ThinkTime.DEFAULT
        val request = LevelRequest(game.start.fen, game.moves.map(Move::uci), level, seed, thinkTime)
        return ComputerReply(game, bookMove, request, freshEngine = level != Level.EIGHT)
    }

    /**
     * The computer's Move [uci] for [turn], with the true evaluation [trueScore] (its own view) when it
     * searched. Ignored when the Game moved on meanwhile (a Takeback, Resign, a new Game).
     */
    fun computerMoved(state: GameState, turn: ComputerReply, uci: String, trueScore: Int?): GameState {
        val record = state.record ?: return state
        if (state.phase != Phase.COMPUTER || record.game != turn.game) return state
        val move = record.game.position.moveFromUci(uci) ?: return state
        val white = trueScore?.let { if (record.userSide == Side.BLACK) it else -it }
        return saved(state, record.withComputerMove(move, white))
    }

    /**
     * A Takeback ("Takeback rule corrected", X1): the owner stops any search first. At a Result the
     * finished Game comes off the Games page and is in progress again.
     */
    fun takeback(state: GameState): GameState {
        val record = state.record ?: return state
        if (!state.canTakeBack) return state
        val back = record.takeback()
        val data = if (state.phase == Phase.OVER) state.data.reopen(record, back) else state.data.save(back)
        return state.copy(data = data, record = back, hint = null, hintPending = false, drawResponse = null, confirming = null)
    }

    /** Asks for a Game Hint on the user's turn; the owner then searches [hintRequest]. */
    fun askHint(state: GameState): GameState =
        if (state.phase != Phase.USER || state.hintPending) state else state.copy(hint = null, hintPending = true, confirming = null)

    /** The Game Hint's search: Level 8 at the default Think Time, the engine's best Move even in the Book (B5). */
    fun hintRequest(state: GameState): SearchRequest? {
        val game = state.record?.game ?: return null
        if (!state.hintPending) return null
        val limits = SearchLimits(nodes = ThinkTime.DEFAULT.nodes, wallMs = ThinkTime.DEFAULT.ms)
        return SearchRequest(game.start.fen, game.moves.map(Move::uci), limits)
    }

    /** The Game Hint [uci] found for [game]: shown, and counted in the record (B5). */
    fun hintFound(state: GameState, game: Game, uci: String): GameState {
        val record = state.record ?: return state
        if (!state.hintPending || record.game != game) return state
        val move = game.position.moveFromUci(uci) ?: return state.copy(hintPending = false)
        return saved(state, record.withGameHint()).copy(hint = move, hintPending = false)
    }

    /** The Game Hint's time on screen is over. */
    fun hideHint(state: GameState): GameState = state.copy(hint = null)

    /** The Game Hint's search was stopped (the board left, B6): none pending, none on show, Hint offered again. */
    fun dropHint(state: GameState): GameState =
        if (state.hint == null && !state.hintPending) state else state.copy(hint = null, hintPending = false)

    /**
     * The user's draw offer, answered at once by the computer (G1): an offer and its acceptance or
     * refusal go into the Game. Only on the user's Move, and 10 Moves after an earlier offer.
     */
    fun offerDraw(state: GameState): GameState {
        val record = state.record ?: return state
        if (!state.canOfferDraw) return state
        val computer = record.userSide.opponent
        val accepted = DrawJudge.accepts(record.game.position, computerEvals(record))
        val offered = record + DrawOffer(record.userSide)
        val answered = offered + if (accepted) DrawAcceptance(computer) else DrawRefusal(computer)
        return saved(state, answered).copy(
            drawResponse = if (accepted) DrawResponse.AGREED else DrawResponse.DECLINED,
            confirming = null,
            hint = null,
        )
    }

    /** The computer's true evaluations before each Move it searched, from its own view, oldest first. */
    fun computerEvals(record: GameRecord): List<Int> {
        val sign = if (record.userSide == Side.BLACK) 1 else -1
        return record.evals.toSortedMap().values.map { it * sign }
    }

    /** Resign (B5): the first tap asks for the second, which ends the Game. */
    fun resign(state: GameState): GameState {
        val record = state.record ?: return state
        if (state.phase == Phase.OVER) return state
        if (state.confirming != Confirm.RESIGN) return state.copy(confirming = Confirm.RESIGN)
        return saved(state, record + Resignation(record.userSide)).copy(confirming = null, hint = null, hintPending = false)
    }

    /** Any other tap: a pending second tap is forgotten. */
    fun cancelConfirm(state: GameState): GameState = if (state.confirming == null) state else state.copy(confirming = null)

    /** Level 8's Think Time: remembered for new Games, and used for the Game in progress from its next Move. */
    fun setThinkTime(state: GameState, seconds: Int): GameState {
        val data = state.data.copy(choices = state.data.choices.copy(thinkTimeSeconds = seconds).normalized())
        val record = state.record
        val next = state.copy(data = data)
        return if (record != null && record.level == 8 && state.phase != Phase.OVER) {
            saved(next, record.copy(thinkTimeSeconds = data.choices.thinkTimeSeconds))
        } else next
    }

    /** The board flip in the Menu (D10, v2), kept in the file. */
    fun flip(state: GameState): GameState = state.copy(data = state.data.copy(flipped = !state.data.flipped))

    private fun saved(state: GameState, record: GameRecord): GameState = state.copy(data = state.data.save(record), record = record)
}
