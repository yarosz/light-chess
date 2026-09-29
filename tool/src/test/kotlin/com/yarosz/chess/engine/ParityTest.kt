package com.yarosz.chess.engine

import pirarucu.board.factory.BoardFactory
import pirarucu.cache.PawnEvaluationCache
import pirarucu.hash.TranspositionTable
import pirarucu.move.Move
import pirarucu.search.History
import pirarucu.search.MainSearch
import pirarucu.search.SearchInfo
import pirarucu.search.SearchInfoListener
import pirarucu.search.SearchOptions
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The vendored search is upstream's search (ADR 0001, B8): with no node budget and no excluded root
 * moves, fixed-depth searches visit exactly as many nodes, and find the same move and score, as
 * upstream Pirarucu 987dd02. The table was written from upstream by `scripts/vendor-pirarucu.py
 * --parity`, which also runs this suite on upstream and the vendored copy side by side.
 */
class ParityTest {

    private data class Row(val position: String, val depth: Int, val nodes: Long, val bestMove: String, val score: Int)

    private val rows: List<Row> = File("src/test/resources/engine/parity.txt").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val (position, depth, nodes, bestMove, score) = line.split(" ")
            Row(position, depth.toInt(), nodes.toLong(), bestMove, score.toInt())
        }

    private object Quiet : SearchInfoListener {
        override fun searchInfo(depth: Int, elapsedTime: Long, searchInfo: SearchInfo) {}
        override fun bestMove(searchInfo: SearchInfo) {}
    }

    @Test
    fun `the table covers depths 8 to 10 on five positions`() {
        assertEquals(POSITIONS.keys, rows.map { it.position }.toSet())
        assertTrue(rows.map { it.depth }.containsAll(listOf(8, 9, 10)))
    }

    @Test
    fun `the vendored search matches upstream node for node`() {
        for (row in rows) {
            val options = SearchOptions()
            options.hasTimeLimit = false
            options.depth = row.depth
            val search = MainSearch(options, Quiet, TranspositionTable(16), PawnEvaluationCache(2), History())
            options.startControl()
            search.search(BoardFactory.getBoard(POSITIONS.getValue(row.position)))
            val info = search.searchInfo
            assertEquals(row, row.copy(nodes = info.searchNodes, bestMove = Move.toString(info.bestMove), score = info.bestScore))
            // The root best move recorded directly agrees with the one upstream reads from the table.
            assertEquals(row.bestMove, Move.toString(search.completedMove), "$row")
            assertEquals(row.depth, search.completedDepth, "$row")
        }
    }

    @Test
    fun `the engine interface returns the same search`() {
        for (row in rows) {
            val result = PirarucuEngine().search(
                SearchRequest(POSITIONS.getValue(row.position), limits = SearchLimits(depth = row.depth)),
            )
            assertEquals(row, row.copy(nodes = result.nodes, bestMove = result.bestMove, score = result.score))
            assertEquals(row.depth, result.depth)
        }
    }

    private companion object {
        val POSITIONS = mapOf(
            "start" to "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "kiwipete" to "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "ruylopez" to "r1bq1rk1/2p1bppp/p1np1n2/1p2p3/4P3/1BPP1N2/PP3PPP/RNBQR1K1 w - - 0 9",
            "endgame" to "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            "position4" to "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
        )
    }
}
