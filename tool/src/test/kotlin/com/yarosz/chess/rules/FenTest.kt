package com.yarosz.chess.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FenTest {

    @Test
    fun `known FENs round-trip exactly`() {
        for (fen in listOf(
            Position.START_FEN,
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
            "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2",
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
            "8/8/8/8/8/8/8/K6k b - - 99 120",
        )) {
            assertEquals(fen, Position.fromFen(fen).fen)
        }
    }

    @Test
    fun `four fields mean clocks 0 and 1`() {
        assertEquals(Position.START, Position.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -"))
    }

    @Test
    fun `castling rights are written in KQkq order whatever order they were read in`() {
        assertEquals(Position.START_FEN, Position.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w qkQK - 0 1").fen)
    }

    @Test
    fun `malformed or impossible FENs are rejected`() {
        for (fen in listOf(
            "",
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP w KQkq - 0 1", // 7 ranks
            "rnbqkbnr/pppppppp/9/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", // 9 squares
            "rnbqkbnr/ppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", // 7 squares
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR x KQkq - 0 1", // side
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkqK - 0 1", // right twice
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNX w KQkq - 0 1", // unknown piece
            "rnbq1bnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQ - 0 1", // no black king
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq e6 0 1", // no pawn skipped e6
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBN1 w KQkq - 0 1", // K right, no h1 rook
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - -1 1", // clock
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 0", // move number
            "P3k3/8/8/8/8/8/8/4K3 w - - 0 1", // pawn on the last rank
            "4k2R/8/8/8/8/8/8/4K3 w - - 0 1", // black in check, white to move
        )) {
            assertFailsWith<FenException>(fen) { Position.fromFen(fen) }
        }
    }

    @Test
    fun `the en passant square is kept as written`() {
        val p = Position.fromFen("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2")
        assertEquals(Square.parse("e6"), p.enPassant)
        assertNull(Position.START.enPassant)
    }
}
