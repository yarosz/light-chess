package com.yarosz.chess.board

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.VectorPath
import com.yarosz.chess.rules.Piece
import com.yarosz.chess.rules.PieceType
import com.yarosz.chess.rules.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PieceVectorsTest {

    @Test
    fun everyPieceOfEverySetBuildsOnThe45Viewport() {
        for (set in PieceSet.entries) for (piece in Piece.entries) {
            val vector = PieceVectors.vector(set, piece)
            assertEquals("${set.name}_${piece.name}", vector.name)
            assertEquals(45f, vector.viewportWidth)
            assertEquals(45f, vector.viewportHeight)
            assertTrue(vector.root.size > 0, "$set $piece has no paths")
            assertSame(vector, PieceVectors.vector(set, piece), "$set $piece is built once")
        }
    }

    @Test
    fun theTwoSetsAreDifferentDrawings() {
        // P2: two sets, not one set under two names.
        for (piece in Piece.entries) {
            val geometric = PieceVectors.vector(PieceSet.GEOMETRIC, piece).root.map { (it as VectorPath).pathData }
            val rounded = PieceVectors.vector(PieceSet.ROUNDED, piece).root.map { (it as VectorPath).pathData }
            assertNotEquals(geometric, rounded, "$piece")
        }
    }

    private fun VectorPath.fillColor(): Color? = (fill as? SolidColor)?.value

    private fun VectorPath.strokeColor(): Color? = (stroke as? SolidColor)?.value

    /**
     * P3: the captured-pieces row draws a white piece as on the board, and a black one as its white
     * drawing, path for path, with the white body painted [Shades.CAPTURED_BLACK_BODY] gray: same
     * silhouette and black outer line, so overlapping pieces stay separate on the black ground.
     */
    @Test
    fun capturedBlackPiecesAreTheWhiteDrawingWithAGrayBody() {
        val gray = Color(Shades.argb(Shades.CAPTURED_BLACK_BODY))
        for (set in PieceSet.entries) for (piece in Piece.entries.filter { it.type != PieceType.KING }) {
            val captured = PieceVectors.captured(set, piece)
            assertSame(captured, PieceVectors.captured(set, piece), "$set $piece is built once")
            if (piece.side == Side.WHITE) {
                assertSame(PieceVectors.vector(set, piece), captured, "$set $piece as on the board")
                continue
            }
            assertEquals("${set.name}_CAPTURED_BLACK_${piece.type.name}", captured.name)
            val white = PieceVectors.vector(set, Piece.of(Side.WHITE, piece.type)).root.map { it as VectorPath }
            val twin = captured.root.map { it as VectorPath }
            assertEquals(white.map { it.pathData }, twin.map { it.pathData }, "$set $piece has the white silhouette")
            assertEquals(white.map { it.strokeLineWidth }, twin.map { it.strokeLineWidth })
            for ((w, t) in white.zip(twin)) {
                assertEquals(w.fillColor()?.let { if (it == Color.White) gray else it }, t.fillColor(), "$set $piece fill")
                assertEquals(w.strokeColor()?.let { if (it == Color.White) gray else it }, t.strokeColor(), "$set $piece stroke")
            }
            assertTrue(twin.any { it.fillColor() == gray }, "$set $piece has a gray body")
            assertTrue(twin.none { it.fillColor() == Color.White || it.strokeColor() == Color.White }, "$set $piece has no white left")
        }
        assertFailsWith<IllegalArgumentException> { PieceVectors.captured(PieceSet.DEFAULT, Piece.BLACK_KING) }
    }

    @Test
    fun whitePiecesAreOutlinedTwinsOfTheBlackSilhouette() {
        // P1, for both sets: a white piece draws a wide black outline first, then its white body over
        // it, so it keeps an outer line on the light square. A black piece starts with its solid black
        // silhouette.
        for (set in PieceSet.entries) for (piece in Piece.entries) {
            val paths = PieceVectors.vector(set, piece).root.map { it as VectorPath }
            val first = paths.first()
            assertEquals(Color.Black, first.fillColor(), "$set $piece starts with a black silhouette")
            if (piece.side == Side.WHITE) {
                val body = paths.first { it.fillColor() == Color.White }
                assertTrue(paths.indexOf(body) > 0, "$set $piece draws its outline under its body")
                assertTrue(first.strokeLineWidth > body.strokeLineWidth, "$set $piece's outline is wider than its body's stroke")
                // Every white part (a line part like the king's cross included) is painted after the black
                // part it sits on, so no white shape loses its outer line.
                val lastBlackOutline = paths.indexOfLast { it.strokeColor() == Color.Black && it.strokeLineWidth > 3.1f }
                val firstWhite = paths.indexOfFirst { it.fillColor() == Color.White || it.strokeColor() == Color.White }
                assertTrue(lastBlackOutline < firstWhite, "$set $piece paints every outline before any white")
            }
        }
    }
}
