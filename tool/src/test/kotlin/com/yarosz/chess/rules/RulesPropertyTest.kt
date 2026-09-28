package com.yarosz.chess.rules

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Property tests over random Games (umbrella DECISIONS D8): thousands of Positions reached by random
 * legal play, each checked against independent oracles written here, not in the core.
 */
class RulesPropertyTest {

    private val games = 400
    private val maxPlies = 300

    /** A random Game from the start, with draw offers and refusals mixed in, played to its end or [maxPlies]. */
    private fun randomGame(seed: Int): Game {
        val random = Random(seed)
        var game = Game.of()
        while (game.result == null && game.ply < maxPlies) {
            val toMove = game.position.sideToMove
            game = when {
                game.openDrawOffer == toMove.opponent && random.nextInt(4) == 0 -> game + DrawRefusal(toMove)
                game.openDrawOffer == null && random.nextInt(40) == 0 -> game + DrawOffer(toMove)
                else -> game + game.position.legalMoves.random(random)
            }
        }
        return game
    }

    private fun forEachPosition(count: Int = games, check: (Position, Int) -> Unit) {
        for (seed in 0 until count) for (p in randomGame(seed).positions) check(p, seed)
    }

    @Test
    fun `every generated Move leaves the mover's king safe, by an independent attack oracle`() {
        forEachPosition { p, seed ->
            val us = p.sideToMove
            for (m in p.legalMoves) {
                val after = p.play(m)
                val king = after.pieces.single { it.second == Piece.of(us, PieceType.KING) }.first
                assertFalse(naiveAttacked(after, king, us.opponent), "seed $seed: ${m.uci} in ${p.fen} leaves the king attacked")
            }
            val king = p.pieces.single { it.second == Piece.of(us, PieceType.KING) }.first
            assertEquals(naiveAttacked(p, king, us.opponent), p.inCheck, "seed $seed: check in ${p.fen}")
        }
    }

    @Test
    fun `FEN round-trips and canonical FEN keeps the digest`() {
        forEachPosition { p, seed ->
            val back = Position.fromFen(p.fen)
            assertEquals(p, back, "seed $seed")
            assertEquals(p.fen, back.fen)
            assertEquals(p.digest, Position.fromFen(p.canonicalFen).digest, "seed $seed: ${p.fen}")
            assertEquals(p.legalMoves, back.legalMoves)
        }
    }

    @Test
    fun `SAN and UCI name exactly one legal Move and read back to it`() {
        // Reading SAN writes it for every legal Move, so this oracle runs on fewer Games.
        forEachPosition(count = 100) { p, seed ->
            val sans = p.legalMoves.map { p.san(it) }
            assertEquals(sans.size, sans.toSet().size, "seed $seed: duplicate SAN in ${p.fen}")
            for ((m, san) in p.legalMoves.zip(sans)) {
                assertEquals(m, p.moveFromSan(san), "seed $seed: SAN $san in ${p.fen}")
                assertEquals(m, p.moveFromUci(m.uci), "seed $seed: UCI ${m.uci} in ${p.fen}")
            }
        }
    }

    @Test
    fun `the colour-flipped Position has the same number of legal Moves and the same status`() {
        forEachPosition { p, seed ->
            val flipped = Position.fromFen(mirror(p.fen))
            assertEquals(p.legalMoves.size, flipped.legalMoves.size, "seed $seed: ${p.fen}")
            assertEquals(p.inCheck, flipped.inCheck)
            assertEquals(p.hasInsufficientMaterial, flipped.hasInsufficientMaterial)
        }
    }

    @Test
    fun `replay is deterministic and a Game's Result follows from its end Position`() {
        var ended = 0
        var positions = 0
        val results = sortedMapOf<String, Int>()
        for (seed in 0 until games) {
            val game = randomGame(seed)
            positions += game.positions.size
            results.merge(game.result?.toString() ?: "none after $maxPlies plies", 1, Int::plus)
            val replayed = Game.of(game.start, game.events)
            assertEquals(game, replayed)
            assertEquals(game.positions, replayed.positions)
            assertEquals(game.result, replayed.result)
            assertEquals(game.openDrawOffer, replayed.openDrawOffer)
            assertEquals(game.moves.size, game.ply)
            assertEquals(Game.of(game.start, game.events).position.digest, game.position.digest)
            val end = game.position
            when (val result = game.result) {
                null -> assertTrue(end.legalMoves.isNotEmpty() && game.ply == maxPlies, "seed $seed ended without a Result")
                is Result.Win -> assertTrue(end.isCheckmate && result.winner == end.sideToMove.opponent, "seed $seed")
                is Result.Draw -> {
                    ended++
                    val why = when (result.by) {
                        DrawReason.STALEMATE -> end.isStalemate
                        DrawReason.INSUFFICIENT_MATERIAL -> end.hasInsufficientMaterial
                        DrawReason.FIFTY_MOVE_RULE -> end.halfmoveClock >= 100
                        DrawReason.REPETITION -> game.positions.count { it.digest == end.digest } >= 3
                        DrawReason.AGREEMENT -> false
                    }
                    assertTrue(why, "seed $seed: ${result.by} at ${end.fen}")
                }
            }
        }
        assertTrue(ended > 0, "random play should reach some automatic draws")
        println("random games: $games, positions: $positions, results: $results")
    }

    @Test
    fun `digest ignores the move counters`() {
        forEachPosition { p, _ ->
            val fields = p.fen.split(' ')
            val recount = Position.fromFen((fields.take(4) + listOf("0", "1")).joinToString(" "))
            assertEquals(p.digest, recount.digest)
        }
    }

    // --- oracles, independent of the core's tables

    private fun naiveAttacked(p: Position, target: Square, by: Side): Boolean =
        p.pieces.any { (from, piece) -> piece.side == by && naiveAttacks(p, from, piece, target) }

    private fun naiveAttacks(p: Position, from: Square, piece: Piece, to: Square): Boolean {
        val df = to.file - from.file
        val dr = to.rank - from.rank
        if (df == 0 && dr == 0) return false
        fun clear(): Boolean {
            val steps = max(abs(df), abs(dr))
            return (1 until steps).all { i ->
                p.pieceAt(Square.of(from.file + i * df.sign(), from.rank + i * dr.sign())) == null
            }
        }
        return when (piece.type) {
            PieceType.PAWN -> abs(df) == 1 && dr == (if (piece.side == Side.WHITE) 1 else -1)
            PieceType.KNIGHT -> (abs(df) == 1 && abs(dr) == 2) || (abs(df) == 2 && abs(dr) == 1)
            PieceType.KING -> max(abs(df), abs(dr)) == 1
            PieceType.ROOK -> (df == 0 || dr == 0) && clear()
            PieceType.BISHOP -> abs(df) == abs(dr) && clear()
            PieceType.QUEEN -> (df == 0 || dr == 0 || abs(df) == abs(dr)) && clear()
        }
    }

    private fun Int.sign() = if (this > 0) 1 else if (this < 0) -1 else 0

    /** The same Position with the colours swapped and the board turned upside down. */
    private fun mirror(fen: String): String {
        val f = fen.split(' ')
        val placement = f[0].split('/').reversed().joinToString("/") { rank -> rank.map(::swapCase).joinToString("") }
        val side = if (f[1] == "w") "b" else "w"
        val castling = if (f[2] == "-") "-" else f[2].map(::swapCase).joinToString("")
        val ep = if (f[3] == "-") "-" else "${f[3][0]}${'9' - (f[3][1] - '0')}"
        return listOf(placement, side, castling, ep, f[4], f[5]).joinToString(" ")
    }

    private fun swapCase(c: Char) = if (c.isUpperCase()) c.lowercaseChar() else c.uppercaseChar()

    @Test
    fun `the mirror oracle is its own inverse`() {
        val fen = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
        assertEquals(fen, mirror(mirror(fen)))
        assertNotNull(Position.fromFen(mirror(fen)))
    }
}
