package com.yarosz.chess

import com.yarosz.chess.board.CapturedRowLayout
import com.yarosz.chess.board.CapturedRowLayout.Placed
import com.yarosz.chess.board.CapturedRowState
import com.yarosz.chess.board.POSITION_VIEW_SIZE
import com.yarosz.chess.board.PieceSet
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
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The captured-pieces row (P3): where each end's pieces and the Material Lead go, that the widest
 * row fits the board without its two ends meeting, and its accessibility label. Its room in the
 * action row, across the board's width above the buttons' line, is `StripFitTest`'s (E12, E13).
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
    fun theDrawingsKeepTheOwnersMockUpsProportions() {
        // E13: 15 dp drawings (45 px on the LP3), where the mock-up drew 52 px and P3 had 17 dp; the steps
        // keep the mock-up's proportions, 17 and 50 px beside its 52 px drawing, to half a dp.
        assertEquals(15f, CapturedRowLayout.SIZE)
        assertEquals(CapturedRowLayout.SIZE * 17f / 52f, CapturedRowLayout.SAME_STEP, 0.25f)
        assertEquals(CapturedRowLayout.SIZE * 50f / 52f, CapturedRowLayout.KIND_STEP, 0.25f)
        // Kinds don't overlap; pieces of one kind do.
        assertTrue(CapturedRowLayout.KIND_STEP < CapturedRowLayout.SIZE && CapturedRowLayout.SAME_STEP < CapturedRowLayout.SIZE / 2)
    }

    /**
     * E13: every drawing's ink stops at [CapturedRowLayout.INK_BOTTOM_UNITS] of its 45 units, 2.1 dp
     * above the 15 dp box, which is where the labels' room under the pieces starts. Read from the
     * source drawings in `art/pieces` (which `PieceVectors` is generated from): each path's lowest
     * point, curves and arcs sampled, plus half its outline (every join and cap is round).
     */
    @Test
    fun theDrawingsInkStopsAboveTheirBox() {
        val files = File("../art/pieces").walk().filter { it.extension == "svg" }.toList()
        assertEquals(24, files.size, "both sets, twelve drawings each")
        val bottoms = files.associate { file ->
            val svg = file.readText()
            assertTrue("viewBox=\"0 0 45 45\"" in svg, file.name)
            file.name to Regex("""<path ([^>]*)>""").findAll(svg).maxOf { path ->
                val attrs = path.groupValues[1]
                val d = Regex("""\bd="([^"]*)"""").find(attrs)!!.groupValues[1]
                val stroke = Regex("""stroke-width="([0-9.]+)"""").find(attrs)?.groupValues?.get(1)?.toFloat() ?: 0f
                assertFalse("miter" in attrs || "square" in attrs || "transform" in attrs, "${file.name}: $attrs")
                lowestY(d) + stroke / 2
            }
        }
        assertEquals(CapturedRowLayout.INK_BOTTOM_UNITS, bottoms.values.max(), 0.01f, "the lowest ink: $bottoms")
        assertEquals(45f, CapturedRowLayout.VIEWPORT_UNITS)
        assertEquals(15.9f, CapturedRowLayout.INK_BOTTOM, 0.001f)
        assertEquals(2.1f, CapturedRowLayout.BOTTOM - CapturedRowLayout.INK_BOTTOM, 0.001f)
    }

    /** The lowest point of an SVG path of absolute M, L, H, V, C, Q, A and Z commands (the pieces' only ones). */
    private fun lowestY(d: String): Float {
        val tokens = Regex("""[A-Za-z]|-?[0-9]*\.?[0-9]+""").findAll(d).map { it.value }.toList()
        var i = 0
        fun num() = tokens[i++].toDouble()
        var x = 0.0
        var y = 0.0
        var low = Double.NEGATIVE_INFINITY
        var command = 'M'
        fun at(py: Double) { low = maxOf(low, py) }
        while (i < tokens.size) {
            if (tokens[i][0].isLetter()) command = tokens[i++][0]
            when (command) {
                'M', 'L' -> { x = num(); y = num(); at(y) }
                'H' -> x = num()
                'V' -> { y = num(); at(y) }
                'Z' -> {}
                'C', 'Q' -> {
                    val n = if (command == 'C') 3 else 2
                    val ys = DoubleArray(n + 1).also { it[0] = y }
                    for (k in 1..n) { x = num(); ys[k] = num() }
                    for (step in 0..SAMPLES) {
                        val t = step.toDouble() / SAMPLES
                        // De Casteljau on the y coordinates.
                        val b = ys.copyOf()
                        for (level in n downTo 1) for (k in 0 until level) b[k] = b[k] * (1 - t) + b[k + 1] * t
                        at(b[0])
                    }
                    y = ys[n]
                }
                'A' -> {
                    var rx = abs(num())
                    var ry = abs(num())
                    val phi = Math.toRadians(num())
                    val large = num() != 0.0
                    val sweep = num() != 0.0
                    val x2 = num()
                    val y2 = num()
                    // The endpoint-to-centre conversion of SVG 1.1's implementation notes (F.6.5).
                    val c = cos(phi)
                    val s = sin(phi)
                    val x1p = c * (x - x2) / 2 + s * (y - y2) / 2
                    val y1p = -s * (x - x2) / 2 + c * (y - y2) / 2
                    val scale = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
                    if (scale > 1) { rx *= sqrt(scale); ry *= sqrt(scale) }
                    val root = sqrt(maxOf(0.0, (rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p) / (rx * rx * y1p * y1p + ry * ry * x1p * x1p)))
                    val coef = if (large == sweep) -root else root
                    val cxp = coef * rx * y1p / ry
                    val cyp = -coef * ry * x1p / rx
                    val cy = s * cxp + c * cyp + (y + y2) / 2
                    val t1 = atan2((y1p - cyp) / ry, (x1p - cxp) / rx)
                    var dt = atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx) - t1
                    if (sweep && dt < 0) dt += 2 * PI
                    if (!sweep && dt > 0) dt -= 2 * PI
                    for (step in 0..SAMPLES) {
                        val t = t1 + dt * step / SAMPLES
                        at(cy + s * rx * cos(t) + c * ry * sin(t))
                    }
                    x = x2
                    y = y2
                }
                else -> error("unexpected command '$command' in $d")
            }
        }
        return low.toFloat()
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

        /** Points sampled along each curve and arc of a drawing. */
        const val SAMPLES = 400
    }
}
