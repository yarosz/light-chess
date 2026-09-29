package com.yarosz.chess

import com.yarosz.chess.board.CapturedRowLayout
import com.yarosz.chess.board.CapturedRowLayout.Placed
import com.yarosz.chess.board.CapturedRowState
import com.yarosz.chess.board.POSITION_VIEW_SIZE
import com.yarosz.chess.board.PieceSet
import com.yarosz.chess.board.StripLayout
import com.yarosz.chess.rules.CapturedPieces
import com.yarosz.chess.rules.Game
import com.yarosz.chess.rules.Piece
import com.yarosz.chess.rules.PieceType
import com.yarosz.chess.rules.PieceType.BISHOP
import com.yarosz.chess.rules.PieceType.KNIGHT
import com.yarosz.chess.rules.PieceType.PAWN
import com.yarosz.chess.rules.PieceType.QUEEN
import com.yarosz.chess.rules.PieceType.ROOK
import com.yarosz.chess.rules.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The captured-pieces row (P3): where each end's pieces and the Material Lead go, that the widest
 * row fits the board without its two ends meeting, its accessibility label, and the strip's room.
 */
class CapturedRowTest {

    private val board = POSITION_VIEW_SIZE.value

    /** The lead's width as the LP3 draws it: LightOS Superfine (16 design px), in the Akkurat stand-in. */
    private fun leadWidth(text: String) = AkkuratProxy.width(text, SUPERFINE_DESIGN_PX)

    private fun place(captured: CapturedPieces, bottom: Side) = CapturedRowLayout.place(captured, bottom, board, ::leadWidth)

    private fun material(pieces: List<PieceType>) = pieces.sumOf(CapturedPieces::value)

    private fun captured(byWhite: List<PieceType>, byBlack: List<PieceType>) =
        CapturedPieces(byWhite, byBlack, material(byWhite) - material(byBlack))

    private val sample = captured(listOf(PAWN, PAWN, KNIGHT, QUEEN), listOf(PAWN, ROOK))

    @Test
    fun theBottomSidesCapturesStartAtTheLeftEdgeAndTheOthersAtTheRight() {
        val row = place(sample, Side.WHITE)
        val s = CapturedRowLayout.SAME_STEP
        val k = CapturedRowLayout.KIND_STEP
        val right = board - CapturedRowLayout.SIZE
        assertEquals(
            listOf(
                // Left, from the edge inwards: pawns outermost, grouped by kind, each kind fanned.
                Placed(Piece.BLACK_PAWN, 0f), Placed(Piece.BLACK_PAWN, s), Placed(Piece.BLACK_KNIGHT, s + k),
                Placed(Piece.BLACK_QUEEN, s + 2 * k),
                // Right, from the edge inwards: the heaviest kind outermost, so it reads pawn to queen too.
                Placed(Piece.WHITE_ROOK, right), Placed(Piece.WHITE_PAWN, right - k),
            ),
            row.pieces,
        )
        // White is ahead by 8: the lead sits just inside White's end, at the left.
        assertEquals("+8", row.lead)
        assertEquals(s + 2 * k + CapturedRowLayout.SIZE + CapturedRowLayout.LEAD_GAP, row.leadX)
        assertEquals(row.leadX + leadWidth("+8"), row.leftEnd)
        assertEquals(right - k, row.rightStart)
    }

    @Test
    fun aFlippedBoardSwapsTheEnds() {
        val row = place(sample, Side.BLACK)
        val k = CapturedRowLayout.KIND_STEP
        val s = CapturedRowLayout.SAME_STEP
        val right = board - CapturedRowLayout.SIZE
        assertEquals(
            listOf(
                Placed(Piece.WHITE_PAWN, 0f), Placed(Piece.WHITE_ROOK, k),
                Placed(Piece.BLACK_QUEEN, right), Placed(Piece.BLACK_KNIGHT, right - k),
                Placed(Piece.BLACK_PAWN, right - 2 * k), Placed(Piece.BLACK_PAWN, right - 2 * k - s),
            ),
            row.pieces,
        )
        // White, now at the top, leads: "+8" just inside the right end.
        val inner = right - 2 * k - s
        assertEquals(inner - CapturedRowLayout.LEAD_GAP - leadWidth("+8"), row.leadX)
        assertEquals(row.leadX, row.rightStart)
        assertEquals(k + CapturedRowLayout.SIZE, row.leftEnd)
    }

    @Test
    fun noLeadWhenLevelAndALeadAtTheEdgeWhenItsSideTookNothing() {
        val level = place(captured(listOf(KNIGHT), listOf(BISHOP)), Side.WHITE)
        assertNull(level.lead)
        // Only a promotion gives Black the lead: its "+4" stands at the right edge.
        val promoted = place(CapturedPieces(emptyList(), emptyList(), -4), Side.WHITE)
        assertEquals("+4", promoted.lead)
        assertEquals(board - leadWidth("+4"), promoted.leadX)
        assertEquals(emptyList(), promoted.pieces)
    }

    /**
     * The widest row: fifteen pieces taken at each end, every kind among them (a kind more makes an
     * end wider, a piece more of a kind already there by only [CapturedRowLayout.SAME_STEP]), and the
     * widest lead a Game can reach (a Side with nine queens against a bare king: +103) on either end.
     * The two ends never come closer than [CapturedRowLayout.MIN_GAP]: the row doesn't wrap.
     */
    @Test
    fun theWidestRowFitsTheBoardWithoutItsEndsMeeting() {
        val fifteen = List(8) { PAWN } + List(2) { KNIGHT } + List(2) { BISHOP } + List(2) { ROOK } + QUEEN
        val promoted = List(5) { PAWN } + List(2) { KNIGHT } + List(2) { BISHOP } + List(2) { ROOK } + List(4) { QUEEN }
        for (white in listOf(fifteen, promoted)) for (black in listOf(fifteen, promoted)) {
            for (lead in listOf(-103, -39, -9, 0, 9, 39, 103)) for (bottom in Side.entries) {
                val row = place(CapturedPieces(white, black, lead), bottom)
                assertEquals(30, row.pieces.size, "every captured piece is drawn")
                assertTrue(row.pieces.all { it.x >= 0f && it.x + CapturedRowLayout.SIZE <= board }, "inside the board")
                assertTrue(row.leftEnd + CapturedRowLayout.MIN_GAP <= row.rightStart, "ends ${row.leftEnd} and ${row.rightStart}, lead $lead")
            }
        }
        // The widest end is fifteen pieces of five kinds: well under half the board.
        val end = 10 * CapturedRowLayout.SAME_STEP + 4 * CapturedRowLayout.KIND_STEP + CapturedRowLayout.SIZE
        assertEquals(end, place(CapturedPieces(fifteen, emptyList(), 0), Side.WHITE).leftEnd)
        assertTrue(end < board / 2)
    }

    @Test
    fun theDrawingsMatchTheOwnersMockUp() {
        // The mock-up, in LP3 px (3 per dp): drawings 52 px, 17 px within a kind, 50 px to the next.
        assertTrue(kotlin.math.abs(CapturedRowLayout.SIZE * 3 - 52) <= 1.5f)
        assertTrue(kotlin.math.abs(CapturedRowLayout.SAME_STEP * 3 - 17) <= 1.5f)
        assertTrue(kotlin.math.abs(CapturedRowLayout.KIND_STEP * 3 - 50) <= 1.5f)
        // Kinds don't overlap; pieces of one kind do.
        assertTrue(CapturedRowLayout.KIND_STEP < CapturedRowLayout.SIZE && CapturedRowLayout.SAME_STEP < CapturedRowLayout.SIZE / 2)
    }

    /**
     * The strip's text below the row (P3): its lines are set to fill the room under the row, which
     * holds [StripLayout.STATUS_MAX_LINES] lines of LightOS Copy at no less than 0.98 of the font size,
     * so a two-line Result fits without the row moving the board or the strip.
     */
    @Test
    fun theStripsTextFitsBelowTheRow() {
        val strip = AkkuratProxy.size(StripLayout.COPY_DESIGN_PX * StripLayout.COPY_LINE_HEIGHT * StripLayout.STATUS_MAX_LINES)
        val below = strip - CapturedRowLayout.BOTTOM
        val copy = AkkuratProxy.size(StripLayout.COPY_DESIGN_PX)
        assertTrue(below / StripLayout.STATUS_MAX_LINES >= 0.98f * copy, "$below dp below the row for two lines of $copy sp")
        // The mock-up's shift: a one-line status centres 10 dp (30 px) lower than without the row.
        assertEquals(10f, CapturedRowLayout.BOTTOM / 2, 0.5f)
    }

    /**
     * The buttons below the row (P3, PR review): a label's line (Copy's line height) plus its padding
     * above and below fits the room under the row, so no label (descenders included) is clipped, and
     * the target stays at least 36 dp tall. Without the row, the padding is as before.
     */
    @Test
    fun theStripsButtonsFitBelowTheRow() {
        val strip = AkkuratProxy.size(StripLayout.COPY_DESIGN_PX * StripLayout.COPY_LINE_HEIGHT * StripLayout.STATUS_MAX_LINES)
        val below = strip - CapturedRowLayout.BOTTOM
        val line = AkkuratProxy.size(StripLayout.COPY_DESIGN_PX * StripLayout.COPY_LINE_HEIGHT)
        val button = line + 2 * StripLayout.BUTTON_VERTICAL_PADDING_BELOW_ROW.value
        assertTrue(button <= below, "a button is $button dp tall in $below dp below the row")
        assertTrue(button >= 36f, "a $button dp tap target")
        // The regular padding would not fit: this is why the row needs its own.
        assertTrue(line + 2 * StripLayout.BUTTON_VERTICAL_PADDING.value > below)
        assertEquals(8f, StripLayout.BUTTON_VERTICAL_PADDING.value)
    }

    /**
     * P3, the owner's choice: every Game board reserves the row's band from its first Position, so
     * the strip's text sits in the same lowered place before and after the first capture (and as
     * Review steps across it); a Puzzle's strip, which passes no row, is as before.
     */
    @Test
    fun aGameStripIsLoweredFromTheStartAndAPuzzleStripIsNot() {
        val start = capturedRow(Game.of(), 0, Side.WHITE, PieceSet.DEFAULT)
        val later = CapturedRowState(sample, Side.WHITE, PieceSet.DEFAULT, "")
        assertFalse(start.shown, "no pieces at Ply 0")
        assertEquals(CapturedRowLayout.BOTTOM, StripLayout.textTop(start).value, "a Game strip at Ply 0 is lowered")
        assertEquals(StripLayout.textTop(start), StripLayout.textTop(later), "the same place with and without captures")
        assertEquals(StripLayout.buttonVerticalPadding(start), StripLayout.buttonVerticalPadding(later))
        assertEquals(StripLayout.BUTTON_VERTICAL_PADDING_BELOW_ROW, StripLayout.buttonVerticalPadding(start))
        // A Puzzle: no row, today's strip.
        assertEquals(0f, StripLayout.textTop(null).value)
        assertEquals(StripLayout.BUTTON_VERTICAL_PADDING, StripLayout.buttonVerticalPadding(null))
    }

    @Test
    fun theRowShowsOnceSomethingIsCaptured() {
        val start = capturedRow(Game.of(), 0, Side.WHITE, PieceSet.DEFAULT)
        assertFalse(start.shown)
        assertTrue(CapturedRowState(sample, Side.WHITE, PieceSet.DEFAULT, "").shown)
    }

    @Test
    fun theRowsLabelReadsBothEndsAndTheLead() {
        assertEquals(
            "Captured by White: two pawns, a knight, a queen. Captured by Black: a pawn, a rook. White is ahead by 8.",
            UiCopy.capturedPieces(sample, Side.WHITE),
        )
        // The bottom Side's end first, as the row reads.
        assertEquals(
            "Captured by Black: a pawn, a rook. Captured by White: two pawns, a knight, a queen. White is ahead by 8.",
            UiCopy.capturedPieces(sample, Side.BLACK),
        )
        assertEquals(
            "Captured by White: a knight. Captured by Black: a bishop. ${UiCopy.MATERIAL_EVEN}",
            UiCopy.capturedPieces(captured(listOf(KNIGHT), listOf(BISHOP)), Side.WHITE),
        )
        assertEquals(
            "Captured by Black: eight pawns, a queen. Black is ahead by 17.",
            UiCopy.capturedPieces(captured(emptyList(), List(8) { PAWN } + QUEEN), Side.WHITE),
        )
    }

    private companion object {
        /** LightOS Superfine: 16 design px (light-sdk `LightTheme.kt`). */
        const val SUPERFINE_DESIGN_PX = 16f
    }
}
