package com.yarosz.chess.board

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.VectorPath
import com.yarosz.chess.rules.Piece
import com.yarosz.chess.rules.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PieceVectorsTest {

    @Test
    fun everyPieceBuildsOnThe45Viewport() {
        for (piece in Piece.entries) {
            val vector = PieceVectors.vector(piece)
            assertEquals(piece.name, vector.name)
            assertEquals(45f, vector.viewportWidth)
            assertEquals(45f, vector.viewportHeight)
            assertTrue(vector.root.size > 0, "$piece has no paths")
            assertSame(vector, PieceVectors.vector(piece), "$piece is built once")
        }
    }

    private fun VectorPath.fillColor(): Color? = (fill as? SolidColor)?.value

    @Test
    fun whitePiecesAreOutlinedTwinsOfTheBlackSilhouette() {
        // P1: a white piece draws a wide black outline first, then its white body over it, so it keeps
        // an outer line on the light square. A black piece starts with its solid black silhouette.
        for (piece in Piece.entries) {
            val paths = PieceVectors.vector(piece).root.map { it as VectorPath }
            val first = paths.first()
            assertEquals(Color.Black, first.fillColor(), "$piece starts with a black silhouette")
            if (piece.side == Side.WHITE) {
                val body = paths.first { it.fillColor() == Color.White }
                assertTrue(paths.indexOf(body) > 0, "$piece draws its outline under its body")
                assertTrue(first.strokeLineWidth > body.strokeLineWidth, "$piece's outline is wider than its body's stroke")
            }
        }
    }
}
