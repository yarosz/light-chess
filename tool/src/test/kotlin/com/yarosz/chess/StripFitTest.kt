package com.yarosz.chess

import com.yarosz.chess.board.POSITION_VIEW_SIZE
import com.yarosz.chess.board.PieceSet
import com.yarosz.chess.board.StripLayout
import com.yarosz.chess.correspondence.HaltReason
import com.yarosz.chess.correspondence.Refusal
import com.yarosz.chess.games.GameChoices
import com.yarosz.chess.games.GameData
import com.yarosz.chess.games.GameFlow
import com.yarosz.chess.games.GameRecord
import com.yarosz.chess.games.GameState
import com.yarosz.chess.games.SideChoice
import com.yarosz.chess.relay.Protocol
import com.yarosz.chess.puzzles.AttemptState
import com.yarosz.chess.rules.DrawAcceptance
import com.yarosz.chess.rules.DrawOffer
import com.yarosz.chess.rules.DrawReason
import com.yarosz.chess.rules.Game
import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.Resignation
import com.yarosz.chess.rules.Result
import com.yarosz.chess.rules.Side
import com.yarosz.chess.rules.TimeoutClaim
import com.yarosz.chess.rules.WinReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every strip the puzzle and game screens can show fits the LP3: each button's label on one line, and
 * the status in the room the buttons leave, in at most [StripLayout.STATUS_MAX_LINES] lines on the
 * puzzle screen and in [GameStrip.statusLines] on the game screen (one line but for a Result, R4.16).
 * The cases follow DESIGN.md's "Buttons by context" tables; a new strip state or string belongs here.
 *
 * The LightOS font (Akkurat) can't ship with the repo, so text is measured with [AkkuratProxy]:
 * Helvetica's advance widths with the narrow letters widened, scaled up until no string is narrower
 * than the same string on LP3 screencaps ([proxyIsNeverNarrowerThanTheLp3]) and no room wider
 * ([roomIsNeverWiderThanOnTheLp3]); it wraps where the LP3 wrapped ([proxyWrapsWhereTheLp3Did]).
 */
class StripFitTest {

    private val menu = UiCopy.MENU
    private val solving = listOf(UiCopy.HINT, UiCopy.SOLUTION, menu)
    private val result = listOf(UiCopy.NEXT, menu)

    /** A strip to check: its status, its buttons' labels, and the lines the status may take. */
    private data class Case(val status: String, val buttons: List<String>, val lines: Int = StripLayout.STATUS_MAX_LINES)

    /** Every state in DESIGN.md's "Buttons by context" tables, with the widest numbers. */
    private val strips: List<Case> by lazy { stripCases() }

    private fun stripCases(): List<Case> = buildList {
        for (side in Side.entries) add(Case(UiCopy.toMove(side), solving))
        add(Case(UiCopy.TRY_AGAIN, solving))
        add(Case(UiCopy.CORRECT, solving))
        add(Case(UiCopy.FIRST_PUZZLE, listOf(menu)))
        add(Case(UiCopy.SOLUTION_PLAYING, listOf(menu)))
        for (state in AttemptState.entries) for (rated in listOf(true, false)) for (shown in listOf(true, false)) {
            for (delta in listOf(-999, 999)) add(Case(UiCopy.result(state, rated, delta, shown), result))
        }
        add(Case(UiCopy.review(99, 99), listOf(UiCopy.LATEST, UiCopy.NEXT, menu)))
        add(Case(UiCopy.review(99, 99), listOf(UiCopy.LATEST, menu)))
        add(Case(UiCopy.PACK_FINISHED, listOf(menu)))
        for (strip in gameStrips()) add(Case(strip.status, strip.buttons.map { it.label }, strip.statusLines))
        for (strip in friendStrips) add(Case(strip.status, strip.buttons.map { it.label }, strip.statusLines))
        // The Play a friend page's buttons (W6), which leave no room for a status or for Menu.
        add(Case("", listOf(UiCopy.NEW_GAME, UiCopy.ENTER_CODE), 1))
    }

    /**
     * Every strip a Correspondence Game and its invite can show (W4, W10), from [FriendStrip] itself on
     * Games played against the fake Relay, then with the widest values: Time Left in each unit, the
     * widest SAN, every Result for both Sides, every notice and every Refusal's copy.
     */
    private val friendStrips: List<FriendStrip> by lazy {
        val scenes = FriendScenes()
        try {
            val now = scenes.now
            buildList {
                val yours = scenes.yourMove()
                val theirs = scenes.theirMove()
                add(FriendStrip.of(yours, now))
                add(FriendStrip.of(theirs, now))
                add(FriendStrip.of(theirs, now, reviewPly = 0))
                add(FriendStrip.of(yours, now, sending = true))
                add(FriendStrip.of(scenes.notSent(), now))
                add(FriendStrip.of(scenes.drawOffered(), now))
                add(FriendStrip.of(scenes.timeUp(), scenes.now))
                for (reason in HaltReason.entries) add(FriendStrip.of(scenes.stopped(reason), now))
                add(FriendStrip.of(scenes.over(), now))
                val (sent, offered) = scenes.rematch()
                add(FriendStrip.of(sent, now))
                add(FriendStrip.of(offered, now))
                val invite = scenes.waiting()
                add(FriendStrip.invite(invite, scenes.now))
                add(FriendStrip.invite(invite, scenes.now, confirming = true))
                add(FriendStrip.invite(invite.copy(invite = invite.invite?.copy(cancelling = true)), scenes.now))
                add(FriendStrip.of(invite, scenes.now))
                // A chosen Move: its SAN with Send and Undo (F11), for the widest SANs.
                val chosen = FriendStrip.of(yours, now, chosen = yours.log!!.game.position.moveFromUci("e2e4"))
                add(chosen)
                for (san in listOf("Qa1xh8#", "exd8=Q#", "Nbxd7+", "O-O-O+")) add(chosen.copy(status = san))
                val menu = listOf(FriendButton.MENU)
                // Time Left at its widest in each unit (W4, W12): "59m", "47h", "7d", and a fresh invite's "48h".
                for (left in listOf(59 * 60_000L, 47 * 3_600_000L + 29 * 60_000L, 48 * 3_600_000L, 7 * Protocol.DAY_MS)) {
                    add(FriendStrip(UiCopy.yourMoveLeft(left), menu))
                    add(FriendStrip(UiCopy.theirMoveLeft(left), menu))
                    if (left <= 48 * 3_600_000L) add(FriendStrip(UiCopy.expiresIn(left), listOf(FriendButton.CANCEL, FriendButton.MENU)))
                }
                add(FriendStrip(UiCopy.expiresIn(48 * 3_600_000L), listOf(FriendButton.CANCEL, FriendButton.MENU)))
                for (side in Side.entries) for (result in results) {
                    add(FriendStrip(UiCopy.friendResult(result, side), listOf(FriendButton.REMATCH, FriendButton.MENU), StripLayout.STATUS_MAX_LINES))
                }
                for (text in listOf(UiCopy.NOT_YET, UiCopy.OFFER_NOT_SENT) + Refusal.entries.map(UiCopy::refusal)) {
                    add(FriendStrip.of(yours, now, notice = text))
                }
            }
        } finally {
            scenes.clean()
        }
    }

    /** W4: every Correspondence Game strip holds one line, but a Result (R4.16). */
    @Test
    fun friendStripsHoldOneLineButForAResult() {
        val resultCopy = results.flatMap { r -> Side.entries.map { UiCopy.friendResult(r, it) } }.toSet()
        for (strip in friendStrips) {
            assertEquals(if (strip.status in resultCopy) StripLayout.STATUS_MAX_LINES else 1, strip.statusLines, "\"${strip.status}\"")
        }
        assertTrue(friendStrips.none { s -> s.buttons.any { it.label == UiCopy.HINT || it.label == UiCopy.TAKEBACK } }, "no Game Hint or Takeback (W10)")
    }

    /** The game strips must hold one line wherever [GameStrip] says so: all but the Results. */
    @Test
    fun gameStripsHoldOneLineButForAResult() {
        val games = gameStrips()
        for (strip in games) {
            val isResult = results.any { r -> Side.entries.any { UiCopy.gameResult(r, it) == strip.status } } ||
                strip.status == UiCopy.UNFINISHED
            assertEquals(if (isResult) StripLayout.STATUS_MAX_LINES else 1, strip.statusLines, "\"${strip.status}\"")
        }
        assertTrue(games.count { it.statusLines == 1 } >= 6)
    }


    /**
     * The game screen's strips (DESIGN.md "The game screen"), from [GameStrip] itself: the user's Move
     * before and after a Takeback is possible, a Game Hint being found, the computer thinking, every
     * Result, Review (in play, at the Result, in a replay) with the widest Ply numbers, and a replayed
     * Game from the Games page.
     */
    private fun gameStrips(): List<GameStrip> = gameStripsAsShown().map { strip ->
        if (GameButton.LATEST in strip.buttons) strip.copy(status = UiCopy.review(9999, 9999)) else strip
    }

    private fun gameStripsAsShown(): List<GameStrip> = buildList {
        val fresh = GameFlow.start(GameState(), GameChoices(level = 8, side = SideChoice.WHITE), 1L, "2026.09.28")
        add(GameStrip.of(fresh, null))
        val thinking = GameFlow.play(fresh, fresh.record!!.game.position.moveFromUci("e2e4")!!)
        add(GameStrip.of(thinking, null))
        add(GameStrip.of(thinking, 0))
        val turn = GameFlow.computerReply(thinking, null)!!
        val userAgain = GameFlow.computerMoved(thinking, turn, "e7e5", 0)
        add(GameStrip.of(userAgain, null))
        add(GameStrip.of(userAgain, 1))
        add(GameStrip.of(GameFlow.askHint(userAgain), null))
        add(GameStrip.of(GameState(), null))
        for (side in Side.entries) for (result in results) {
            val record = GameRecord(Game.of(), side)
            add(GameStrip.of(GameState(GameData(), recordWith(record, result)), null))
            add(GameStrip.of(GameState(GameData(), recordWith(record, result)), 99))
            add(GameStrip.replay(recordWith(record, result), null))
        }
        add(GameStrip.replay(GameRecord(Game.of(), Side.WHITE), null))
        add(GameStrip.replay(GameRecord(Game.of(), Side.WHITE), 99))
    }

    /** [record] with a Game that [result] ends. */
    private fun recordWith(record: GameRecord, result: Result): GameRecord = record.copy(game = endedBy(result).also {
        assertEquals(result, it.result)
    })

    private fun play(fen: String?, vararg uci: String): Game =
        uci.fold(Game.of(fen?.let(Position::fromFen) ?: Position.START)) { g, m -> g + g.position.moveFromUci(m)!! }

    private fun endedBy(result: Result): Game = when (result) {
        is Result.Win -> when (result.by) {
            WinReason.RESIGNATION -> Game.of() + Resignation(result.winner.opponent)
            // The side not to move claims: after one Move for White, after two for Black.
            WinReason.TIME -> (if (result.winner == Side.WHITE) play(null, "e2e4") else play(null, "e2e4", "e7e5")) + TimeoutClaim(result.winner)
            WinReason.CHECKMATE -> if (result.winner == Side.BLACK) play(null, "f2f3", "e7e5", "g2g4", "d8h4")
            else play(null, "e2e4", "e7e5", "f1c4", "b8c6", "d1h5", "g8f6", "h5f7")
        }
        is Result.Draw -> when (result.by) {
            DrawReason.AGREEMENT -> Game.of() + DrawOffer(Side.WHITE) + DrawAcceptance(Side.BLACK)
            DrawReason.STALEMATE -> play("k7/8/8/2Q5/8/8/8/7K w - - 0 1", "c5b6")
            DrawReason.REPETITION -> play(null, "g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1", "f6g8")
            DrawReason.FIFTY_MOVE_RULE -> play("8/8/8/4k3/8/8/4K3/R7 w - - 99 60", "a1a2")
            DrawReason.INSUFFICIENT_MATERIAL -> play("8/8/8/4k3/8/8/3pK3/8 w - - 0 1", "e2d2")
        }
    }

    /** The room left for the status, in dp, next to [buttons]. */
    private fun statusRoom(buttons: List<String>): Float =
        POSITION_VIEW_SIZE.value - StripLayout.STATUS_GAP.value -
            buttons.sumOf { (AkkuratProxy.width(it) + 2 * StripLayout.BUTTON_PADDING.value).toDouble() }.toFloat()

    /** Greedy word wrap, as Android breaks a line without hyphens. */
    private fun wrap(text: String, room: Float): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        for (word in text.split(' ')) {
            val longer = if (line.isEmpty()) word else "$line $word"
            if (line.isEmpty() || AkkuratProxy.width(longer) <= room) line = longer else {
                lines += line
                line = word
            }
        }
        return lines + line
    }

    @Test
    fun everyStatusFitsNextToItsButtons() {
        for ((status, buttons, maxLines) in strips) {
            val room = statusRoom(buttons)
            val lines = wrap(status, room)
            assertTrue(lines.size <= maxLines, "\"$status\" next to $buttons needs ${lines.size} lines, $maxLines allowed")
            for (line in lines) {
                assertTrue(AkkuratProxy.width(line) <= room, "\"$line\" (${AkkuratProxy.width(line)} dp) in $room dp next to $buttons")
            }
        }
    }

    @Test
    fun theStripCoversEveryStripString() {
        val used = strips.map { it.status }.toSet() + strips.flatMap { it.buttons }
        for (copy in listOf(
            UiCopy.WHITE_TO_MOVE, UiCopy.BLACK_TO_MOVE, UiCopy.LATEST, UiCopy.FIRST_PUZZLE, UiCopy.TRY_AGAIN,
            UiCopy.CORRECT, UiCopy.SOLUTION_PLAYING, UiCopy.HINT, UiCopy.SOLUTION, UiCopy.NEXT, UiCopy.MENU,
            UiCopy.UNRATED_SOLVED, UiCopy.UNRATED_HINTED, UiCopy.UNRATED_FAILED, UiCopy.PACK_FINISHED,
            UiCopy.YOUR_MOVE, UiCopy.THINKING, UiCopy.FINDING_HINT, UiCopy.MOVE_NOW,
            UiCopy.BACK, UiCopy.DRAW_AGREED, UiCopy.UNFINISHED, UiCopy.NEW_GAME,
            UiCopy.ENTER_CODE, UiCopy.SEND, UiCopy.UNDO, UiCopy.SENDING, UiCopy.NOT_SENT, UiCopy.RETRY, UiCopy.DRAW_OFFERED,
            UiCopy.ACCEPT, UiCopy.DECLINE, UiCopy.TIME_IS_UP, UiCopy.CLAIM_WIN, UiCopy.OUT_OF_SYNC, UiCopy.UPDATE_CHESS,
            UiCopy.GAME_DELETED, UiCopy.SEAT_LOST, UiCopy.CANCEL, UiCopy.CANCEL_CONFIRM, UiCopy.REMATCH, UiCopy.REMATCH_SENT,
            UiCopy.REMATCH_OFFERED, UiCopy.NOT_YET, UiCopy.OFFER_NOT_SENT,
        )) assertTrue(copy in used, "\"$copy\" is not checked")
        for (reason in Refusal.entries) assertTrue(UiCopy.refusal(reason) in used, "\"${UiCopy.refusal(reason)}\" is not checked")
        for (result in results) for (side in Side.entries) {
            assertTrue(UiCopy.friendResult(result, side) in used, "\"${UiCopy.friendResult(result, side)}\" is not checked")
        }
        for (result in results) for (side in Side.entries) {
            assertTrue(UiCopy.gameResult(result, side) in used, "\"${UiCopy.gameResult(result, side)}\" is not checked")
        }
    }

    /**
     * The Menu's Puzzle row (A9 with D7): a Menu line is the LP3's 360 dp less 24 dp either side. With
     * the widest 5-character Lichess id, each of its two lines fits whole, so Android never breaks the
     * address (it would at a slash: seen on the emulator when the address shared a line).
     */
    @Test
    fun theMenuPuzzleRowFits() {
        val room = MENU_WIDTH_DP - 2 * MENU_PADDING_DP
        for (id in listOf("WWWWW", "mmmmm", "00sHx")) {
            val lines = UiCopy.puzzleRow(id).split('\n')
            assertEquals(listOf("Puzzle $id", "lichess.org/training/$id"), lines)
            for (l in lines) assertTrue(AkkuratProxy.width(l) <= room, "\"$l\" (${AkkuratProxy.width(l)} dp) in $room dp")
        }
    }

    /**
     * A stopped Correspondence Game's Menu opens with why it stopped (W4, W13): each sentence fits in
     * two Menu lines, the room the out-of-sync line takes.
     */
    @Test
    fun theMenuStopLinesFitTwoLines() {
        val room = MENU_WIDTH_DP - 2 * MENU_PADDING_DP
        for (line in listOf(UiCopy.OUT_OF_SYNC_ROW, UiCopy.UPDATE_CHESS_ROW, UiCopy.GAME_DELETED_ROW, UiCopy.SEAT_LOST)) {
            val lines = wrap(line, room)
            assertTrue(lines.size <= 2, "\"$line\" needs ${lines.size} Menu lines: $lines")
        }
    }

    /** The Menu's Pieces row (P2): "Pieces · <set>" for every Piece Set, each on one Menu line. */
    @Test
    fun theMenuPiecesRowNamesEverySetOnOneLine() {
        val room = MENU_WIDTH_DP - 2 * MENU_PADDING_DP
        assertEquals("Pieces · Geometric", UiCopy.piecesRow(PieceSet.GEOMETRIC))
        assertEquals("Pieces · Rounded", UiCopy.piecesRow(PieceSet.ROUNDED))
        assertEquals(PieceSet.entries.size, PieceSet.entries.map(UiCopy::pieceSetName).toSet().size, "every set has its own name")
        for (set in PieceSet.entries) {
            val row = UiCopy.piecesRow(set)
            assertTrue(AkkuratProxy.width(row) <= room, "\"$row\" (${AkkuratProxy.width(row)} dp) in $room dp")
        }
        // A tap moves to the next set; from the last it wraps to the first, so every set is reachable.
        assertEquals(PieceSet.ROUNDED, PieceSet.GEOMETRIC.next)
        assertEquals(PieceSet.GEOMETRIC, PieceSet.ROUNDED.next)
        assertEquals(PieceSet.GEOMETRIC, PieceSet.DEFAULT)
    }

    /** The bug seen on the LP3: on one line, the first Puzzle's status lost its end next to Menu. */
    @Test
    fun oneLineWasTooShortForTheFirstPuzzle() {
        assertEquals(2, wrap(UiCopy.FIRST_PUZZLE, statusRoom(listOf(menu))).size)
        assertFalse(AkkuratProxy.width(UiCopy.FIRST_PUZZLE) <= statusRoom(listOf(menu)))
    }

    /**
     * Ink widths in px (/ 3 = dp) of strip text on LP3 screencaps, LightOS at 480 dpi: v1 PR 5's
     * six, and the game screen's (v2 PR 4 check). The proxy's advance width must never be narrower
     * than the ink plus [LP3_BEARINGS_DP]. Helvetica × 1.15 fell short on "Hint" and "Latest" (narrow
     * letters), hence the widened ones.
     */
    @Test
    fun proxyIsNeverNarrowerThanTheLp3() {
        val lp3InkPx = mapOf(
            "Menu" to 148, "Latest" to 174, "Restart" to 206, "White to move" to 399,
            "Review · 0 of 1" to 382, "Tap a piece, then a squ…" to 694,
            "Takeback" to 266, "Hint" to 113, "Move now" to 276, "Your move" to 289, "Computer" to 282,
            "Review · 4 of 6" to 396,
        )
        for ((text, px) in lp3InkPx) {
            val lp3 = px / 3f + LP3_BEARINGS_DP
            assertTrue(AkkuratProxy.width(text) >= lp3, "\"$text\": proxy ${AkkuratProxy.width(text)} dp, LP3 $lp3 dp")
        }
    }

    /**
     * The room the LP3 left for the status, from where the first button's ink starts on the screencap:
     * less the strip's left edge (72 px), [StripLayout.STATUS_GAP], the button's padding and 1 dp of
     * its left bearing. The proxy's room must never be wider.
     */
    @Test
    fun roomIsNeverWiderThanOnTheLp3() {
        val firstButtonInkPx = mapOf(
            listOf(UiCopy.TAKEBACK, UiCopy.HINT, menu) to 341,
            listOf(UiCopy.MOVE_NOW, menu) to 500,
            listOf(UiCopy.LATEST, menu) to 601,
            listOf(UiCopy.HINT, menu) to 663,
            listOf(menu) to 831,
        )
        for ((buttons, px) in firstButtonInkPx) {
            val lp3 = (px - 72) / 3f - StripLayout.STATUS_GAP.value - StripLayout.BUTTON_PADDING.value - 1f
            assertTrue(statusRoom(buttons) <= lp3, "next to $buttons: proxy ${statusRoom(buttons)} dp, LP3 $lp3 dp")
        }
    }

    /**
     * What the LP3 showed, which the proxy must reproduce: "Your move" wrapped next to Takeback, Hint
     * and Menu, and "Computer thinking" next to Move now and Menu (the game strips before R4.16);
     * "Your move" next to Hint and Menu and "Review · 4 of 6" next to Latest and Menu held one line;
     * the first Puzzle's status took two lines next to Menu.
     */
    @Test
    fun proxyWrapsWhereTheLp3Did() {
        assertEquals(2, wrap(UiCopy.YOUR_MOVE, statusRoom(listOf(UiCopy.TAKEBACK, UiCopy.HINT, menu))).size)
        assertEquals(2, wrap("Computer thinking", statusRoom(listOf(UiCopy.MOVE_NOW, menu))).size)
        assertEquals(1, wrap(UiCopy.YOUR_MOVE, statusRoom(listOf(UiCopy.HINT, menu))).size)
        assertEquals(1, wrap(UiCopy.review(4, 6), statusRoom(listOf(UiCopy.LATEST, menu))).size)
        assertEquals(2, wrap(UiCopy.FIRST_PUZZLE, statusRoom(listOf(menu))).size)
    }

    private companion object {
        /** Side bearings, both ends of a string: the ink gap between neighbouring buttons ran 8-9 px past their padding. */
        const val LP3_BEARINGS_DP = 3f

        /** The LP3's app area (DESIGN.md "Layout") and a Menu line's side padding (`MenuScreen.Line`). */
        const val MENU_WIDTH_DP = 360f
        const val MENU_PADDING_DP = 24f
    }
}

/** Every result the game screen and a replay can show. */
private val results: List<Result> =
    Side.entries.flatMap { side -> WinReason.entries.map { Result.Win(side, it) } } + DrawReason.entries.map { Result.Draw(it) }

/** A conservative stand-in for Akkurat at LightOS `Copy` size on the LP3. */
object AkkuratProxy {
    /** The LP3's app area is 1168 px tall at 480 dpi: Configuration.screenHeightDp = 389. */
    private const val LP3_SCREEN_HEIGHT_DP = 389f

    /**
     * Akkurat against Helvetica (with [NARROW] widened), with margin: every measured string clears its
     * LP3 width by at least 1 dp at this scale (proxyIsNeverNarrowerThanTheLp3). Wider would wrap
     * "White to move" to three lines next to Hint, Solution and Menu, and "Draw: insufficient material"
     * next to Next and Menu.
     */
    private const val SCALE = 1.14f

    /** Akkurat's i, j and t are wider than Helvetica's (222, 222, 278): widened to these, in 1/1000 em. */
    private val NARROW = mapOf('i' to 280, 'j' to 280, 't' to 325)

    /** Helvetica advance widths, in 1/1000 em (the Adobe core font metrics). */
    private val WIDTHS: Map<Char, Int> = buildMap {
        val upper = intArrayOf(
            667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833,
            722, 778, 667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611,
        )
        val lower = intArrayOf(
            556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833,
            556, 556, 556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500,
        )
        for (i in 0 until 26) {
            put('A' + i, upper[i])
            put('a' + i, lower[i])
        }
        for (d in '0'..'9') put(d, 556)
        put(' ', 278); put(',', 278); put('.', 278); put(':', 278); put('\'', 191); put('’', 222); put('?', 556)
        put('·', 278); put('+', 584); put('−', 584); put('-', 333); put('…', 1000); put('=', 584); put('#', 556)
        put('/', 278)
        putAll(NARROW)
    }

    /** The advance width of [text] in dp. A character without a width fails loudly: add it to the table. */
    fun width(text: String): Float = width(text, StripLayout.COPY_DESIGN_PX)

    /** The same at another LightOS size, in design px (Superfine is 16: the captured-pieces row's lead, P3). */
    fun width(text: String, designPx: Float): Float =
        text.sumOf { c -> requireNotNull(WIDTHS[c]) { "no width for '$c' in \"$text\"" } } / 1000f * size(designPx) * SCALE

    /** LightOS scales design px by screenHeightDp / 600 (light-sdk `designVerticalPxToSp`); sp = dp at font scale 1. */
    fun size(designPx: Float): Float = designPx * LP3_SCREEN_HEIGHT_DP / 600f
}
