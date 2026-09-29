package com.yarosz.chess.engine

import com.yarosz.chess.rules.Position
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EngineTest {

    private fun legalIn(fen: String, moves: List<String>, uci: String): Boolean {
        var position = Position.fromFen(fen)
        for (m in moves) position = position.play(position.moveFromUci(m)!!)
        return position.moveFromUci(uci) != null
    }

    @Test
    fun `stop from another thread returns within 50 ms with a legal move`() = runBlocking {
        val host = EngineHost.shared
        val request = SearchRequest(MIDDLEGAME, limits = SearchLimits(depth = SearchLimits.MAX_DEPTH))
        val search = async { host.search(request) }
        delay(500)
        val stoppedAt = System.nanoTime()
        host.stop()
        val result = search.await()
        val latencyMs = (System.nanoTime() - stoppedAt) / 1_000_000
        println("stop latency: $latencyMs ms at depth ${result.depth}, ${result.nodes} nodes")
        assertTrue(latencyMs < 50, "stop took $latencyMs ms")
        assertTrue(!result.fallback && result.depth > 1, "$result")
        assertTrue(legalIn(MIDDLEGAME, emptyList(), result.bestMove))
    }

    @Test
    fun `a search stopped before depth 1 plays our core's first legal move`() {
        val engine = PirarucuEngine()
        val handle = StopHandle()
        engine.stop(handle)
        val result = engine.search(SearchRequest(MIDDLEGAME, limits = SearchLimits(depth = 20)), handle)
        assertTrue(result.fallback)
        assertEquals(0, result.depth)
        assertEquals(Position.fromFen(MIDDLEGAME).legalMoves.first().uci, result.bestMove)
    }

    @Test
    fun `a late stop does not reach the next search`() {
        val engine = PirarucuEngine()
        val first = StopHandle()
        engine.search(SearchRequest(Position.START_FEN, limits = SearchLimits(depth = 4)), first)
        engine.stop(first)
        val result = engine.search(SearchRequest(Position.START_FEN, limits = SearchLimits(depth = 6)))
        assertEquals(6, result.depth)
    }

    @Test
    fun `the node limit is honoured and gives the same move on every run`() {
        for (limit in listOf(1_000L, 20_000L, 200_000L)) {
            val request = SearchRequest(MIDDLEGAME, limits = SearchLimits(nodes = limit))
            val a = PirarucuEngine().search(request)
            val b = PirarucuEngine().search(request)
            println("node limit $limit: ${a.nodes} nodes, depth ${a.depth}, ${a.bestMove}")
            // The budget is checked at every main-search node; quiescence below the last one may add a few.
            assertTrue(a.nodes in limit..limit + 100, "$limit -> ${a.nodes}")
            assertEquals(a, b.copy(elapsedMs = a.elapsedMs))
            assertTrue(legalIn(MIDDLEGAME, emptyList(), a.bestMove))
        }
    }

    @Test
    fun `excluded root moves are never played`() {
        val engine = PirarucuEngine()
        val best = engine.search(SearchRequest(Position.START_FEN, limits = SearchLimits(depth = 8))).bestMove
        val excluded = mutableSetOf(best)
        repeat(3) {
            engine.newGame()
            val next = engine.search(
                SearchRequest(Position.START_FEN, limits = SearchLimits(depth = 8, excludedRootMoves = excluded)),
            )
            assertTrue(next.bestMove !in excluded, "$next excluded $excluded")
            assertTrue(!next.fallback && legalIn(Position.START_FEN, emptyList(), next.bestMove))
            excluded += next.bestMove
        }
        assertEquals(4, excluded.size)
    }

    @Test
    fun `excluding every legal move is refused`() {
        val fen = "7k/8/8/8/8/8/8/K6q w - - 0 1" // White's king has only Kb2
        val only = Position.fromFen(fen).legalMoves.map { it.uci }.toSet()
        assertFailsWith<IllegalArgumentException> {
            PirarucuEngine().search(SearchRequest(fen, limits = SearchLimits(depth = 4, excludedRootMoves = only)))
        }
    }

    @Test
    fun `a wall-time limit ends the search on time`() = runBlocking {
        val result = EngineHost.shared.search(SearchRequest(MIDDLEGAME, limits = SearchLimits(wallMs = 300)))
        println("wall 300 ms: took ${result.elapsedMs} ms, depth ${result.depth}")
        assertTrue(result.elapsedMs in 250..400, "$result")
        assertTrue(!result.fallback && legalIn(MIDDLEGAME, emptyList(), result.bestMove))
    }

    @Test
    fun `a start Position plus Moves is searched, and repetitions are seen`() {
        val moves = listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5a4", "g8f6", "e1h1")
        val result = PirarucuEngine().search(SearchRequest(Position.START_FEN, moves, SearchLimits(depth = 8)))
        assertTrue(legalIn(Position.START_FEN, moves, result.bestMove), "$result")

        // Black, a queen down, can repeat the start Position a third time with ...Ng8: a draw, so the
        // engine takes it. It only sees the repetition because the Moves reach it with their history.
        val shuffle = listOf("g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1")
        val down = "rnb1kbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val black = PirarucuEngine().search(SearchRequest(down, shuffle, SearchLimits(depth = 8)))
        assertEquals("f6g8", black.bestMove, "$black")
        assertEquals(0, black.score)
    }

    @Test
    fun `illegal input and finished Positions are refused`() {
        val engine = PirarucuEngine()
        assertFailsWith<IllegalArgumentException> {
            engine.search(SearchRequest(Position.START_FEN, listOf("e2e5"), SearchLimits(depth = 2)))
        }
        val mated = "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3"
        assertFailsWith<IllegalArgumentException> { engine.search(SearchRequest(mated, limits = SearchLimits(depth = 2))) }
        assertFailsWith<IllegalArgumentException> { SearchLimits() }
    }

    @Test
    fun `the engine finds a mate and says so`() {
        val mateInOne = "6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1"
        val result = PirarucuEngine().search(SearchRequest(mateInOne, limits = SearchLimits(depth = 6)))
        assertEquals("d1d8", result.bestMove)
        assertTrue(result.score > 10_000, "$result")
    }

    @Test
    fun `every attach gets the same engine thread`() = runBlocking {
        // A relaunched Tool builds new view models in the same process (PLATFORM.md); each one must
        // reach this one host, never a second engine thread.
        val threads = mutableSetOf<String>()
        repeat(3) {
            val host = EngineHost.shared
            assertTrue(host === EngineHost.shared)
            host.search(SearchRequest(Position.START_FEN, limits = SearchLimits(depth = 3))) {
                threads += Thread.currentThread().name + "#" + Thread.currentThread().id
            }
        }
        assertEquals(1, threads.size, "$threads")
        val engineThreads = Thread.getAllStackTraces().keys.count { it.name == EngineHost.THREAD_NAME && it.isAlive }
        assertEquals(1, engineThreads)
    }

    private companion object {
        const val MIDDLEGAME = "r1bq1rk1/2p1bppp/p1np1n2/1p2p3/4P3/1BPP1N2/PP3PPP/RNBQR1K1 w - - 0 9"
    }
}
