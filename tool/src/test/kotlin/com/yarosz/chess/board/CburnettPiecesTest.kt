package com.yarosz.chess.board

import com.yarosz.chess.rules.Piece
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CburnettPiecesTest {

    @Test
    fun everyPieceBuildsOnTheCburnettViewport() {
        for (piece in Piece.entries) {
            val vector = CburnettPieces.vector(piece)
            assertEquals(piece.name, vector.name)
            assertEquals(45f, vector.viewportWidth)
            assertEquals(45f, vector.viewportHeight)
            assertTrue(vector.root.size > 0, "$piece has no paths")
            assertSame(vector, CburnettPieces.vector(piece), "$piece is built once")
        }
    }
}
