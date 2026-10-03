package com.yarosz.chess

import com.thelightphone.sdk.ui.LightBarButtonDefaults
import com.yarosz.chess.board.BarLayout
import com.yarosz.chess.board.CapturedRowLayout
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
import com.yarosz.chess.puzzles.Stage
import androidx.compose.ui.unit.dp
import com.yarosz.chess.rules.CapturedPieces
import com.yarosz.chess.rules.DrawAcceptance
import com.yarosz.chess.rules.DrawOffer
import com.yarosz.chess.rules.DrawReason
import com.yarosz.chess.rules.Game
import com.yarosz.chess.rules.PieceType
import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.Resignation
import com.yarosz.chess.rules.Result
import com.yarosz.chess.rules.Side
import com.yarosz.chess.rules.TimeoutClaim
import com.yarosz.chess.rules.WinReason
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every status and button the boards and pages can show fits the LP3. On a board (layout E, decision
 * log "Layout E"), each status fits the top bar's title and each moment's buttons their line in the
 * action row under the board, below the Captured Pieces: the tests from [theTopBarIsLightOsTopBar] on. On the invite
 * page and the Play a friend list, which keep the strip under their LightOS top bars, each button's
 * label holds one line and the status fits in the room the buttons leave, in at most
 * [StripLayout.STATUS_MAX_LINES] lines. The cases follow DESIGN.md's "Buttons by context" tables; a
 * new state or string belongs here.
 *
 * The LightOS font (Akkurat) can't ship with the repo, so text is measured with [AkkuratProxy]:
 * Helvetica's advance widths with the narrow letters widened, scaled up until no string is narrower
 * than the same string on LP3 screencaps ([proxyIsNeverNarrowerThanTheLp3]) and no room wider
 * ([roomIsNeverWiderThanOnTheLp3]); it wraps where the LP3 wrapped ([proxyWrapsWhereTheLp3Did]).
 */
class StripFitTest {

    /**
     * A state to check: its status, its buttons' labels, the lines the status may take in a strip,
     * whether it is a board's (a top bar and an action row, E1, E2) or a strip's, and whether the
     * strip has the Menu mark (N4).
     */
    private data class Case(
        val status: String,
        val buttons: List<String>,
        val lines: Int = StripLayout.STATUS_MAX_LINES,
        val board: Boolean = true,
        val menu: Boolean = false,
    )

    /** Every state the boards and pages can show, from their own strip functions, with the widest numbers. */
    private val strips: List<Case> by lazy { stripCases() }

    private fun stripCases(): List<Case> = buildList {
        // N17: the Puzzle board's mark in every state; the end of the Pack draws no board and has none.
        for (strip in puzzleStrips()) add(Case(strip.status, strip.buttons.map { it.label }, menu = strip.menu))
        add(Case(PuzzleStrip.FINISHED.status, PuzzleStrip.FINISHED.buttons.map { it.label }, menu = PuzzleStrip.FINISHED.menu))
        for (strip in gameStrips()) add(Case(strip.status, strip.buttons.map { it.label }, strip.statusLines, menu = strip.menu))
        for (strip in friendStrips) add(Case(strip.status, strip.buttons.map { it.label }, strip.statusLines, menu = strip.menu))
        // The invite page: LightOS's top bar, with its back arrow, then the strip.
        for (strip in inviteStrips) add(Case(strip.status, strip.buttons.map { it.label }, strip.statusLines, board = false, menu = strip.menu))
        // The Play a friend list's buttons (W6, N7): no status, no Menu.
        add(Case("", listOf(UiCopy.NEW_GAME, UiCopy.ENTER_CODE), 1, board = false))
    }

    /** The states shown in a strip: the invite page's and the Play a friend list's. */
    private val pageStrips: List<Case> by lazy { strips.filter { !it.board } }

    /**
     * Every strip the Puzzle board can show (DESIGN.md "The strip"), from [PuzzleStrip] itself: each
     * stage, in and out of Review ("Review · 99 of 99"), "Try again", the very first Puzzle (F6), and
     * every result's copy at its widest ("Failed −999").
     */
    private fun puzzleStrips(): List<PuzzleStrip> = buildList {
        val results = buildList {
            for (state in AttemptState.entries) for (rated in listOf(true, false)) for (shown in listOf(true, false)) {
                for (delta in listOf(-999, 999)) add(UiCopy.result(state, rated, delta, shown))
            }
        }.distinct()
        for (stage in Stage.entries) for (review in listOf(null, UiCopy.review(99, 99))) for (wrong in listOf(false, true)) {
            for (first in listOf(false, true)) for (side in Side.entries) for (result in results) {
                add(PuzzleStrip.of(stage, review, result, wrong, first, side))
            }
        }
    }.distinct()

    /** Every strip the invite page can show (W4, G2): expiry, the second tap, a cancel not sent, a notice. */
    private val inviteStrips: List<FriendStrip> by lazy {
        val scenes = FriendScenes()
        try {
            val invite = scenes.waiting()
            buildList {
                add(FriendStrip.invite(invite, scenes.now))
                add(FriendStrip.invite(invite, scenes.now, confirming = true))
                add(FriendStrip.invite(invite.copy(invite = invite.invite?.copy(cancelling = true)), scenes.now))
                for (text in listOf(UiCopy.NOT_YET, UiCopy.OFFER_NOT_SENT) + Refusal.entries.map(UiCopy::refusal)) {
                    add(FriendStrip.invite(invite, scenes.now, notice = text))
                }
                for (left in listOf(59 * 60_000L, 47 * 3_600_000L + 29 * 60_000L, 48 * 3_600_000L)) {
                    add(FriendStrip(UiCopy.expiresIn(left), listOf(FriendButton.CANCEL)))
                }
            }
        } finally {
            scenes.clean()
        }
    }

    /**
     * Every strip a Correspondence Game's board can show (W4, W10), from [FriendStrip] itself on Games
     * played against the fake Relay, then with the widest values: Time Left in each unit, the widest
     * SAN, every Result for both Sides, every notice and every Refusal's copy.
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
                // Review at its widest Ply numbers, as on the computer's board (R4.16).
                add(FriendStrip.of(theirs, now, reviewPly = 0).copy(status = UiCopy.review(9999, 9999)))
                add(FriendStrip.of(yours, now, sending = true))
                add(FriendStrip.of(scenes.notSent(), now))
                add(FriendStrip.of(scenes.drawOffered(), now))
                add(FriendStrip.of(scenes.timeUp(), scenes.now))
                for (reason in HaltReason.entries) add(FriendStrip.of(scenes.stopped(reason), now))
                add(FriendStrip.of(scenes.over(), now))
                val (sent, offered) = scenes.rematch()
                add(FriendStrip.of(sent, now))
                add(FriendStrip.of(offered, now))
                // A chosen Move: its SAN with Send and Undo (F11), for the widest SANs.
                val chosen = FriendStrip.of(yours, now, chosen = yours.log!!.game.position.moveFromUci("e2e4"))
                add(chosen)
                for (san in listOf("Qa1xh8#", "exd8=Q#", "Nbxd7+", "O-O-O+")) add(chosen.copy(status = san))
                // Time Left at its widest in each unit (W4, W12): "59m", "47h", "7d".
                for (left in listOf(59 * 60_000L, 47 * 3_600_000L + 29 * 60_000L, 48 * 3_600_000L, 7 * Protocol.DAY_MS)) {
                    add(FriendStrip(UiCopy.yourMoveLeft(left), emptyList()))
                    add(FriendStrip(UiCopy.theirMoveLeft(left), emptyList()))
                }
                for (side in Side.entries) for (result in results) {
                    add(FriendStrip(UiCopy.friendResult(result, side), listOf(FriendButton.REMATCH), StripLayout.STATUS_MAX_LINES))
                    add(FriendStrip(UiCopy.friendResult(result, side), emptyList(), StripLayout.STATUS_MAX_LINES))
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

    private fun gameStripsAsShown(): List<GameStrip> = computerStripsAsShown() + replayStripsAsShown()

    /** The computer's board: [gameStripsAsShown] but the replays. */
    private fun computerStripsAsShown(): List<GameStrip> = buildList {
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
        }
    }

    /** A replayed Game from the Games page: at its end, for every Result, and in Review. */
    private fun replayStripsAsShown(): List<GameStrip> = buildList {
        for (side in Side.entries) for (result in results) add(GameStrip.gamesReview(recordWith(GameRecord(Game.of(), side), result), null))
        add(GameStrip.gamesReview(GameRecord(Game.of(), Side.WHITE), null))
        add(GameStrip.gamesReview(GameRecord(Game.of(), Side.WHITE), 99))
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

    /**
     * The room left for a strip's status, in dp, on the LP3's 360 dp: from the board's edge to the
     * first of [buttons], which end at the Menu mark's target (N4) or 16 dp from the screen's edge.
     */
    private fun statusRoom(buttons: List<String>, menu: Boolean): Float =
        LP3_WIDTH_DP - StripLayout.SIDE.value - StripLayout.buttonsEnd(menu).value -
            StripLayout.STATUS_GAP.value - buttonsWidth(buttons)

    private fun buttonsWidth(buttons: List<String>): Float =
        buttons.sumOf { (AkkuratProxy.width(it) + 2 * StripLayout.BUTTON_PADDING.value).toDouble() }.toFloat()

    /**
     * The same in the layout the calibration screencaps show (v1 and v2, before N3 and N4): the strip
     * as wide as the board, and "Menu" a text button among [buttons].
     */
    private fun calibratedRoom(buttons: List<String>): Float =
        POSITION_VIEW_SIZE.value - StripLayout.STATUS_GAP.value - buttonsWidth(buttons)

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

    /** N4: every strip (the invite page's, the Play a friend list's) fits beside the Menu mark where it has it, at the LP3's 360 dp. */
    @Test
    fun everyStatusFitsNextToItsButtons() {
        assertTrue(pageStrips.isNotEmpty() && pageStrips.any { it.menu })
        for ((status, buttons, maxLines, _, menu) in pageStrips) {
            val room = statusRoom(buttons, menu)
            val lines = wrap(status, room)
            val beside = "$buttons${if (menu) " and the Menu mark" else ""}"
            assertTrue(lines.size <= maxLines, "\"$status\" next to $beside needs ${lines.size} lines, $maxLines allowed")
            for (line in lines) {
                assertTrue(AkkuratProxy.width(line) <= room, "\"$line\" (${AkkuratProxy.width(line)} dp) in $room dp next to $beside")
            }
        }
    }

    /**
     * N9's two offers on a Correspondence Game's board: "Draw?" and "Rematch?" in the top bar with
     * the Menu mark (E1), Accept and Decline in the action row (E2), each title on one line.
     */
    @Test
    fun theOffersHoldOneLineWithTheMenu() {
        val offers = boardCases.filter { it.status == UiCopy.DRAW_QUESTION || it.status == UiCopy.REMATCH_OFFERED }
        assertEquals(2, offers.size, "both offers are checked")
        for (offer in offers) {
            assertEquals(listOf(UiCopy.ACCEPT, UiCopy.DECLINE), offer.buttons)
            assertTrue(offer.menu, "\"${offer.status}\" has the Menu mark")
            assertEquals(1, wrapBy(offer.status, BarLayout.titleMaxWidth(LP3_WIDTH_DP.dp).value, ::titleWidth).size)
        }
    }

    /**
     * N4, kept by E1: the mark's squares are 8 by 7 px, 28 px apart, their ink ending 48 px from the
     * right edge; in the strip its target is 48 dp wide.
     */
    @Test
    fun theMarkSitsWhereLightOsDrawsIt() {
        assertEquals(24f, StripLayout.SIDE.value)
        assertEquals(8f, 3 * StripLayout.DOT_WIDTH.value, 0.01f)
        assertEquals(7f, 3 * StripLayout.DOT_HEIGHT.value, 0.01f)
        assertEquals(28f, 3 * StripLayout.DOT_PITCH.value, 0.01f)
        assertEquals(48f, 3 * StripLayout.MENU_INK_END.value)
        assertTrue(StripLayout.MENU_TARGET.value >= 48f)
        // The mark's ink lies inside its target, in the strip and in the top bar's 40 dp box (E1).
        val markInk = 2 * StripLayout.DOT_PITCH.value + StripLayout.DOT_WIDTH.value
        assertTrue(StripLayout.MENU_INK_END.value + markInk <= StripLayout.MENU_TARGET.value)
        val unit = BarLayout.unit(LP3_WIDTH_DP.dp).value
        assertTrue(StripLayout.MENU_INK_END.value + markInk <= unit * (BarLayout.TOP_BAR_PADDING_UNITS + BarLayout.MARK_TARGET_UNITS))
    }

    @Test
    fun theStripCoversEveryStripString() {
        val used = strips.map { it.status }.toSet() + strips.flatMap { it.buttons }
        for (copy in listOf(
            UiCopy.WHITE_TO_MOVE, UiCopy.BLACK_TO_MOVE, UiCopy.LATEST, UiCopy.FIRST_PUZZLE, UiCopy.TRY_AGAIN,
            UiCopy.CORRECT, UiCopy.SOLUTION_PLAYING, UiCopy.HINT, UiCopy.SOLUTION, UiCopy.NEXT,
            UiCopy.UNRATED_SOLVED, UiCopy.UNRATED_HINTED, UiCopy.UNRATED_FAILED, UiCopy.PACK_FINISHED,
            UiCopy.YOUR_MOVE, UiCopy.THINKING, UiCopy.FINDING_HINT, UiCopy.MOVE_NOW,
            UiCopy.DRAW_AGREED, UiCopy.UNFINISHED, UiCopy.NEW_GAME,
            UiCopy.ENTER_CODE, UiCopy.SEND, UiCopy.UNDO, UiCopy.SENDING, UiCopy.NOT_SENT, UiCopy.RETRY, UiCopy.DRAW_QUESTION,
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
     * The Puzzle board's Menu's lines (A9 with D7, N18): a page line is the LP3's 360 dp less 24 dp
     * either side. With the widest 5-character Lichess id, each of its two lines fits whole, so Android
     * never breaks the address (it would at a slash: seen on the emulator when the address shared a
     * line). About no longer carries them.
     */
    @Test
    fun thePuzzleMenuLinesFit() {
        assertTrue(UiCopy.about(null, "").none { it.startsWith("Puzzle ") || "lichess.org/training" in it }, "About names no Puzzle (N18)")
        val room = MENU_WIDTH_DP - 2 * MENU_PADDING_DP
        for (id in listOf("WWWWW", "mmmmm", "00sHx")) {
            val lines = UiCopy.puzzleLines(id)
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

    /** A board Menu's Pieces row (P2, N17): "Pieces · <set>" for every Piece Set, each on one Menu line. */
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

    /** The bug seen on the LP3: on one line, the first Puzzle's status lost its end next to Menu (the v1 layout). */
    @Test
    fun oneLineWasTooShortForTheFirstPuzzle() {
        assertEquals(2, wrap(UiCopy.FIRST_PUZZLE, calibratedRoom(listOf(menu))).size)
        assertFalse(AkkuratProxy.width(UiCopy.FIRST_PUZZLE) <= calibratedRoom(listOf(menu)))
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
     * its left bearing. The proxy's room must never be wider. The screencaps show the v2 layout, so the
     * room is measured in it ([calibratedRoom]); layout E and today's strips are measured with the same proxy.
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
            assertTrue(calibratedRoom(buttons) <= lp3, "next to $buttons: proxy ${calibratedRoom(buttons)} dp, LP3 $lp3 dp")
        }
    }

    /**
     * What the LP3 showed, which the proxy must reproduce: "Your move" wrapped next to Takeback, Hint
     * and Menu, and "Computer thinking" next to Move now and Menu (the game strips before R4.16);
     * "Your move" next to Hint and Menu and "Review · 4 of 6" next to Latest and Menu held one line;
     * the first Puzzle's status took two lines next to Menu. All in the v2 layout, with "Menu" as text.
     */
    @Test
    fun proxyWrapsWhereTheLp3Did() {
        assertEquals(2, wrap(UiCopy.YOUR_MOVE, calibratedRoom(listOf(UiCopy.TAKEBACK, UiCopy.HINT, menu))).size)
        assertEquals(2, wrap("Computer thinking", calibratedRoom(listOf(UiCopy.MOVE_NOW, menu))).size)
        assertEquals(1, wrap(UiCopy.YOUR_MOVE, calibratedRoom(listOf(UiCopy.HINT, menu))).size)
        assertEquals(1, wrap(UiCopy.review(4, 6), calibratedRoom(listOf(UiCopy.LATEST, menu))).size)
        assertEquals(2, wrap(UiCopy.FIRST_PUZZLE, calibratedRoom(listOf(menu))).size)
    }

    // Layout E (decision log "Layout E", E1-E5): the status in the top bar's title, the buttons and the
    // Captured Pieces in the action row under the board.

    /** Every status a board can show (E3): each Puzzle, computer, replay and Correspondence Game strip's. */
    private val boardCases: List<Case> by lazy { strips.filter { it.board } }

    /** A title's width in dp: LightOS `Fine` in the stand-in, with its 0.03 em between letters. */
    private fun titleWidth(text: String): Float =
        AkkuratProxy.width(text, BarLayout.TITLE_DESIGN_PX) +
            text.length * AkkuratProxy.size(BarLayout.TITLE_DESIGN_PX * BarLayout.TITLE_LETTER_SPACING)

    /** Greedy word wrap as [wrap], measured by [measure]. */
    private fun wrapBy(text: String, room: Float, measure: (String) -> Float): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        for (word in text.split(' ')) {
            val longer = if (line.isEmpty()) word else "$line $word"
            if (line.isEmpty() || measure(longer) <= room) line = longer else {
                lines += line
                line = word
            }
        }
        return lines + line
    }

    /**
     * E1: the board's top bar is light-sdk's `LightTopBar`, and our title keeps its measures, read from
     * the SDK's own source: 3 grid units tall, 1 unit of padding, a `Fine` title at most 18 units wide
     * (`CENTER_MAX_WIDTH_UNITS`), and `Fine` at 25 design px, 1.15 line height, 0.03 em spacing.
     */
    @Test
    fun theTopBarIsLightOsTopBar() {
        val sdk = "../light-sdk/sdk/ui/src/main/kotlin/com/thelightphone/sdk/ui"
        val bar = File("$sdk/LightTopBar.kt").readText()
        fun constant(name: String) = Regex("""const val $name = ([0-9.]+)f""").find(bar)?.groupValues?.get(1)?.toFloat()
        assertEquals(BarLayout.TOP_BAR_UNITS, constant("TOPBAR_HEIGHT_UNITS"))
        assertEquals(BarLayout.TOP_BAR_PADDING_UNITS, constant("HORIZONTAL_PADDING_UNITS"))
        assertEquals(BarLayout.TITLE_MAX_WIDTH_UNITS, constant("CENTER_MAX_WIDTH_UNITS"))
        assertTrue("TOPBAR_CENTER_TEXT_VARIANT = LightTextVariant.Fine" in bar, "the SDK's title is Fine")
        assertEquals(2f, LightBarButtonDefaults.ICON_SIZE_UNITS, "the arrow is the SDK's 2-unit icon")
        val theme = File("$sdk/LightTheme.kt").readText()
        val fine = Regex("""fine = TextStyle\((.*?)\n    \)""", RegexOption.DOT_MATCHES_ALL).find(theme)!!.groupValues[1]
        assertTrue("fontSize = 25.sp" in fine, fine)
        assertTrue("letterSpacing = (25 * 0.03).sp" in fine, fine)
        assertTrue("lineHeight = (25 * 1.15).sp" in fine, fine)
        assertEquals(25f, BarLayout.TITLE_DESIGN_PX)
        assertEquals(0.03f, BarLayout.TITLE_LETTER_SPACING)
        assertEquals(1.15f, BarLayout.TITLE_LINE_HEIGHT)
        // On the LP3: a 40 dp bar, a 240 dp title, and the title's box clear of both 40 dp targets.
        val bar40 = BarLayout.topBarHeight(LP3_WIDTH_DP.dp).value
        val title = BarLayout.titleMaxWidth(LP3_WIDTH_DP.dp).value
        assertEquals(40f, bar40, 0.01f)
        assertEquals(240f, title, 0.01f)
        val target = BarLayout.unit(LP3_WIDTH_DP.dp).value * (BarLayout.TOP_BAR_PADDING_UNITS + BarLayout.MARK_TARGET_UNITS)
        assertTrue((LP3_WIDTH_DP - title) / 2 >= target, "the title starts at ${(LP3_WIDTH_DP - title) / 2} dp, the targets end at $target dp")
        // The mark's ink ends 16 dp from the edge, inside its box (which ends one grid unit in).
        assertTrue(StripLayout.MENU_INK_END.value >= BarLayout.unit(LP3_WIDTH_DP.dp).value)
    }

    /**
     * E3: every status a board can show fits the top bar's title on the LP3, in `Fine`, at most 240 dp
     * wide, in at most two lines, and two such lines fit the 40 dp bar. Most take one line.
     */
    @Test
    fun everyBoardStatusFitsTheTopBarTitle() {
        val room = BarLayout.titleMaxWidth(LP3_WIDTH_DP.dp).value
        val twoLines = mutableSetOf<String>()
        for (case in boardCases) {
            val lines = wrapBy(case.status, room, ::titleWidth)
            assertTrue(lines.size <= BarLayout.TITLE_MAX_LINES, "\"${case.status}\" needs ${lines.size} title lines: $lines")
            for (line in lines) assertTrue(titleWidth(line) <= room, "\"$line\" (${titleWidth(line)} dp) in a $room dp title")
            if (lines.size == 2) twoLines += case.status
        }
        val lines = AkkuratProxy.size(BarLayout.TITLE_DESIGN_PX * BarLayout.TITLE_LINE_HEIGHT) * BarLayout.TITLE_MAX_LINES
        assertTrue(lines <= BarLayout.topBarHeight(LP3_WIDTH_DP.dp).value, "two title lines are $lines dp")
        // Everything the owner named reads on one line: the first Puzzle, the long Results, Time Left.
        for (status in listOf(
            UiCopy.FIRST_PUZZLE, UiCopy.review(9999, 9999), UiCopy.yourMoveLeft(47 * 3_600_000L),
            UiCopy.gameResult(Result.Draw(DrawReason.FIFTY_MOVE_RULE), Side.WHITE),
            UiCopy.gameResult(Result.Win(Side.WHITE, WinReason.RESIGNATION), Side.WHITE),
        )) assertTrue(status in boardCases.map { it.status } && status !in twoLines, "\"$status\" on one title line")
        // Today every status takes one line in the stand-in (the widest, "Tap a piece, then a square", 234
        // of 240 dp): the second line is E3's margin for Akkurat and for new copy.
        assertTrue(twoLines.size * 10 < boardCases.map { it.status }.distinct().size, "two lines are rare: $twoLines")
    }

    /**
     * The action row's height on the LP3: the app area (1168 px at 3 px per dp, 389.33 dp) less the
     * top bar and the board (E2): 112 px, 37.33 dp.
     */
    private val actionRowHeight: Float
        get() = AkkuratProxy.LP3_APP_HEIGHT_PX / LP3_PX_PER_DP - BarLayout.topBarHeight(LP3_WIDTH_DP.dp).value - POSITION_VIEW_SIZE.value

    /** LightOS `Copy`'s font size on the LP3, in dp: 30 design px. */
    private val copyEm: Float get() = AkkuratProxy.size(StripLayout.COPY_DESIGN_PX)

    /** A label's baseline in the LP3's action row, from the row's top, as `ActionRow` places it (E13, E14). */
    private fun labelBaseline(captured: Boolean): Float = BarLayout.buttonLine(actionRowHeight.dp, copyEm.dp, captured).value

    /** A label's ink as `ActionRow` places it on a Game's board ([captured]) or the Puzzle board: its top and bottom, from the row's top. */
    private fun labelInk(captured: Boolean): Pair<Float, Float> {
        val baseline = labelBaseline(captured)
        return baseline - BarLayout.COPY_INK_ASCENT * copyEm to baseline + BarLayout.COPY_INK_DESCENT * copyEm
    }

    /** Fifteen pieces taken, every kind among them: the widest end (P3's `CapturedRowTest`). */
    private val fifteen = List(8) { PieceType.PAWN } + List(2) { PieceType.KNIGHT } + List(2) { PieceType.BISHOP } +
        List(2) { PieceType.ROOK } + PieceType.QUEEN

    /**
     * E13: every board's buttons fit the row, at most three, and centred as a group on the screen
     * (`ActionRow` aligns them to its centre) they stay within the board's edges.
     */
    @Test
    fun everyActionRowFitsUnderTheBoard() {
        val side = BarLayout.boardSide(LP3_WIDTH_DP.dp).value
        for (case in boardCases) {
            assertTrue(case.buttons.size <= 3)
            val width = buttonsWidth(case.buttons)
            assertTrue((LP3_WIDTH_DP - width) / 2 >= side, "${case.buttons} ($width dp) start at ${(LP3_WIDTH_DP - width) / 2} dp, the board at $side dp")
        }
        // The widest set, Accept and Decline, keeps well inside the board.
        assertTrue(boardCases.any { it.buttons == listOf(UiCopy.ACCEPT, UiCopy.DECLINE) })
    }

    /**
     * E13, E14: the labels' baselines in the LP3's 37.33 dp row, from its top: 31.83 dp on a Game's
     * board, 23.88 dp on the Puzzle board. (Each button's target, the row's full height, is by
     * construction, `fillMaxHeight` on its Box in `ActionRow`: no unit test sees it; it is checked on
     * the emulator and the LP3.)
     */
    @Test
    fun theLabelsBaselinesSitWhereTheirInkIsCentred() {
        assertEquals(31.83f, labelBaseline(captured = true), 0.01f)
        assertEquals(23.88f, labelBaseline(captured = false), 0.01f)
    }

    /**
     * E13's vertical budget: on a Game's board the Captured Pieces (3 dp, then the 15 dp drawings, whose
     * ink stops at 15.9 dp) and the labels below them share the 37.33 dp row with no overlap: a label's
     * ink, `Copy` from its tallest letter to its descender, is centred between the drawings' ink and the
     * app area's bottom edge, about 1.34 dp clear of each; the Material Lead's whole text box ends above
     * it. The drawings' size is set by their box: 15 dp is the largest whole size whose box leaves room
     * for the ink. On the Puzzle board (E14) the ink is centred in the whole row.
     */
    @Test
    fun thePiecesAndTheButtonsEachHaveTheirLine() {
        assertEquals(37.33f, actionRowHeight, 0.01f)
        assertEquals(19.45f, copyEm, 0.01f)
        assertEquals(CapturedRowLayout.TOP + CapturedRowLayout.SIZE, BarLayout.PIECES_LINE.value)
        assertEquals(18f, BarLayout.PIECES_LINE.value)
        val roomTop = BarLayout.labelRoomTop(captured = true).value
        assertEquals(CapturedRowLayout.INK_BOTTOM, roomTop, "the labels' room starts at the drawings' ink bottom")
        val (top, bottom) = labelInk(captured = true)
        assertTrue(top >= CapturedRowLayout.INK_BOTTOM, "the labels' ink starts at $top dp, the drawings' ink ends at ${CapturedRowLayout.INK_BOTTOM} dp")
        assertTrue(bottom <= actionRowHeight, "the labels' ink ends at $bottom dp in a $actionRowHeight dp row")
        // Centred: the same room above the ink and below it, about 1.34 dp each.
        assertEquals(top - roomTop, actionRowHeight - bottom, 0.01f)
        assertEquals(1.34f, top - roomTop, 0.01f)
        // Akkurat's ink may run past Roboto's by this much before it touches: ascent to 0.819 em, descent to 0.283 em.
        val baseline = labelBaseline(captured = true)
        assertEquals(0.819f, (baseline - CapturedRowLayout.INK_BOTTOM) / copyEm, 0.001f)
        assertEquals(0.283f, (actionRowHeight - baseline) / copyEm, 0.001f)
        // The Material Lead, Superfine (16 design px, a 1.2 line height) centred on the drawings: its box ends above the labels' ink.
        val leadBoxBottom = CapturedRowLayout.TOP + CapturedRowLayout.SIZE / 2 + AkkuratProxy.size(16f * 1.2f) / 2
        assertTrue(leadBoxBottom < top, "the lead's box ends at $leadBoxBottom dp, the labels' ink starts at $top dp")
        // One dp more of drawing box, and a label's ink no longer fits under the box.
        val ink = (BarLayout.COPY_INK_ASCENT + BarLayout.COPY_INK_DESCENT) * copyEm
        assertTrue(CapturedRowLayout.BOTTOM + ink <= actionRowHeight, "the ink fits under the 15 dp box")
        assertTrue(CapturedRowLayout.BOTTOM + 1f + ink > actionRowHeight, "a 16 dp box would leave room for the ink")
        // The Puzzle board: the whole row, the ink in its middle.
        assertEquals(0f, BarLayout.labelRoomTop(captured = false).value)
        val (puzzleTop, puzzleBottom) = labelInk(captured = false)
        assertEquals(puzzleTop, actionRowHeight - puzzleBottom, 0.01f)
        assertTrue(puzzleTop > 0f && puzzleBottom < actionRowHeight)
    }

    /**
     * E12: the Captured Pieces run across the board's full width, the bottom Side's end from its left
     * edge and the other's from its right edge. The crowded rows (fifteen a Side, a lead of 103 either
     * way) keep P3's own steps there, with every piece drawn and the ends apart: `fit` never has to
     * tighten on the LP3, and stays for anything wider.
     */
    @Test
    fun theCapturedPiecesRunFromEachEdgeOfTheBoard() {
        val board = POSITION_VIEW_SIZE.value
        for (lead in listOf(-103, 0, 103)) for (bottom in Side.entries) {
            val captured = CapturedPieces(fifteen, fifteen, lead)
            val row = CapturedRowLayout.fit(captured, bottom, board, ::leadWidth)
            assertEquals(1f, row.steps, "fifteen a Side, lead $lead, keep their steps")
            assertEquals(30, row.pieces.size, "every piece is drawn")
            assertTrue(row.pieces.all { it.x >= 0f && it.x + CapturedRowLayout.SIZE <= board }, "inside the board: $row")
            assertTrue(row.leftEnd + CapturedRowLayout.MIN_GAP <= row.rightStart, "ends apart: $row")
            // The bottom Side's end starts at the board's left edge (the top Side's pieces, which it
            // took), the top Side's end ends at its right edge.
            val left = row.pieces.filter { it.piece.side == bottom.opponent }
            val right = row.pieces.filter { it.piece.side == bottom }
            assertEquals(0f, left.minOf { it.x })
            assertEquals(board, right.maxOf { it.x } + CapturedRowLayout.SIZE)
            // The lead, after the leading Side's end.
            when {
                lead == 0 -> assertEquals(null, row.lead)
                (lead > 0) == (bottom == Side.WHITE) -> assertEquals(left.maxOf { it.x } + CapturedRowLayout.SIZE + CapturedRowLayout.LEAD_GAP, row.leadX)
                else -> assertEquals(right.minOf { it.x } - CapturedRowLayout.LEAD_GAP, row.leadX + leadWidth(row.lead!!))
            }
        }
    }

    /** The Material Lead's width: LightOS Superfine (16 design px), as `CapturedRowTest` measures it. */
    private fun leadWidth(text: String) = AkkuratProxy.width(text, 16f)

    private companion object {
        /** The strip's "Menu" text button on the calibration screencaps (the v2 layout, before N4's mark). */
        const val menu = "Menu"

        /** The LP3's width in dp: 1080 px at 3 px per dp. */
        const val LP3_WIDTH_DP = 360f

        /** The LP3's density: 480 dpi, 3 px per dp. */
        const val LP3_PX_PER_DP = 3f

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
    const val LP3_SCREEN_HEIGHT_DP = 389f

    /** The app area's height in px: 389.33 dp, which the board's Column fills (E2's row, E13). */
    const val LP3_APP_HEIGHT_PX = 1168f

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
