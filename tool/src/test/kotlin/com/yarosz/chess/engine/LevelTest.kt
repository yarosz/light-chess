package com.yarosz.chess.engine

import com.yarosz.chess.rules.Game
import com.yarosz.chess.rules.Position
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LevelTest {

    private fun legalIn(fen: String, moves: List<String>, uci: String): Boolean {
        var position = Position.fromFen(fen)
        for (m in moves) position = position.play(position.moveFromUci(m)!!)
        return position.moveFromUci(uci) != null
    }

    /** A Game between two Levels on fresh engines, [plies] long or until it ends. */
    private fun playOut(white: Level, black: Level, seed: Long, plies: Int): List<String> {
        val players = mapOf(true to LevelPlayer(PirarucuEngine()), false to LevelPlayer(PirarucuEngine()))
        var game = Game.of()
        val moves = mutableListOf<String>()
        while (moves.size < plies && !game.isOver) {
            val whiteToMove = moves.size % 2 == 0
            val level = if (whiteToMove) white else black
            val chosen = players.getValue(whiteToMove).play(LevelRequest(Position.START_FEN, moves, level, seed))
            game += game.position.moveFromUci(chosen.move)!!
            moves += chosen.move
        }
        return moves
    }

    @Test
    fun `Levels are numbered 1 to 8 and only Level 8 plays the best Move alone`() {
        assertEquals((1..8).toList(), Level.entries.map { it.number })
        for (level in Level.entries) assertEquals(level, Level.of(level.number))
        assertEquals(1, Level.EIGHT.settings().topN)
        assertTrue(Level.entries.dropLast(1).all { it.settings().topN > 1 && it.settings().wallMs == null })
    }

    @Test
    fun `node budgets grow and the pick narrows toward Level 8`() {
        val below = Level.entries.dropLast(1).map { it.settings() }
        assertTrue(below.zipWithNext().all { (a, b) -> a.nodes < b.nodes && a.maxNodes < b.maxNodes && a.topN >= b.topN })
        assertTrue(below.drop(1).zipWithNext().all { (a, b) -> a.marginCp > b.marginCp })
        assertTrue(below.last().maxNodes < Level.EIGHT.settings().nodes)
    }

    @Test
    fun `every Level below 8 costs under a second of the LP3's search speed`() {
        for (level in Level.entries.dropLast(1)) {
            val ms = level.settings().maxNodes * 1000 / LP3_NODES_PER_SECOND
            assertTrue(ms < 1000, "$level: ~$ms ms on the LP3")
        }
    }

    @Test
    fun `Think time sets Level 8's wall cap and node budget`() {
        for (think in ThinkTime.entries) {
            val settings = Level.EIGHT.settings(think)
            assertEquals(think.ms, settings.wallMs)
            assertEquals(think.ms * ThinkTime.NODES_PER_MS, settings.nodes)
        }
        assertEquals(ThinkTime.THREE_SECONDS, ThinkTime.DEFAULT)
        // Below Level 8, Think time changes nothing.
        assertEquals(Level.FOUR.settings(ThinkTime.THREE_SECONDS), Level.FOUR.settings(ThinkTime.THIRTY_SECONDS))
    }

    @Test
    fun `a Game replays Move for Move from the same seed, and another seed changes it`() {
        for ((white, black) in listOf(Level.ONE to Level.TWO, Level.THREE to Level.FIVE)) {
            val a = playOut(white, black, seed = 42, plies = 40)
            val b = playOut(white, black, seed = 42, plies = 40)
            assertEquals(a, b, "$white vs $black")
            val c = playOut(white, black, seed = 43, plies = 40)
            assertNotEquals(a, c, "$white vs $black: seeds 42 and 43 played the same Game")
        }
    }

    @Test
    fun `each Level's Move is legal, and within its margin of the best`() {
        for (level in Level.entries.dropLast(1)) {
            val player = LevelPlayer(PirarucuEngine())
            val settings = level.settings()
            for ((i, fen) in POSITIONS.withIndex()) {
                val chosen = player.play(LevelRequest(fen, emptyList(), level, gameSeed = i.toLong()))
                assertTrue(legalIn(fen, emptyList(), chosen.move), "$level $fen ${chosen.move}")
                assertTrue(chosen.candidates.any { it.move == chosen.move }, "$chosen")
                assertEquals(chosen.bestMove, chosen.candidates.first().move)
                assertEquals(chosen.trueScore, chosen.candidates.first().score)
                assertTrue(chosen.candidates.size <= settings.topN)
                assertEquals(chosen.candidates.size, chosen.candidates.map { it.move }.toSet().size)
                assertTrue(chosen.candidates.all { it.score >= chosen.trueScore - settings.marginCp }, "$level $chosen")
            }
        }
    }

    @Test
    fun `sampling never picks outside the margin, over many seeds`() {
        val settings = LevelSettings(nodes = 3_000, depth = null, topN = 5, marginCp = 40, wallMs = null)
        val picked = mutableSetOf<String>()
        for (seed in 0L until 40L) {
            val chosen = LevelPlayer(PirarucuEngine()).play(MIDDLEGAME, emptyList(), settings, seed)
            assertTrue(chosen.candidates.all { it.score >= chosen.trueScore - 40 }, "$chosen")
            assertTrue(chosen.move in chosen.candidates.map { it.move })
            picked += chosen.move
        }
        // The candidates are the same on every run; the seed picks among them.
        assertTrue(picked.size > 1, "one Move for 40 seeds: $picked")
    }

    @Test
    fun `a Move far below the best is never a candidate`() {
        // White mates at once on the back rank with Qd8; every other Move is merely a queen up.
        val mate = "6k1/5ppp/8/8/8/8/5PPP/3Q2K1 w - - 0 1"
        val settings = LevelSettings(nodes = 20_000, depth = null, topN = 6, marginCp = 300, wallMs = null)
        for (seed in 0L until 10L) {
            val chosen = LevelPlayer(PirarucuEngine()).play(mate, emptyList(), settings, seed)
            assertEquals(listOf("d1d8"), chosen.candidates.map { it.move }, "$chosen")
            assertEquals("d1d8", chosen.move)
        }
    }

    @Test
    fun `Level 8 plays the plain best Move, with its true score`() {
        // Level 8's settings with a node budget in place of the clock, so the test is deterministic.
        val settings = Level.EIGHT.settings().copy(nodes = 300_000, wallMs = null)
        for (fen in POSITIONS) {
            val chosen = LevelPlayer(PirarucuEngine()).play(fen, emptyList(), settings, gameSeed = 7)
            val plain = PirarucuEngine().search(SearchRequest(fen, limits = SearchLimits(nodes = 300_000)))
            assertEquals(plain.bestMove, chosen.move, fen)
            assertEquals(plain.score, chosen.trueScore, fen)
            assertEquals(plain.depth, chosen.trueDepth, fen)
            assertEquals(1, chosen.searches)
        }
    }

    @Test
    fun `node budgets are honoured by every search of a choice`() {
        for (level in Level.entries.dropLast(1)) {
            val settings = level.settings()
            val chosen = LevelPlayer(PirarucuEngine()).play(LevelRequest(MIDDLEGAME, emptyList(), level, 1))
            // Each search may overshoot by a few quiescence nodes (decision log, v2 PR 1).
            assertTrue(chosen.nodes <= chosen.searches * (settings.nodes + 100), "$level: ${chosen.nodes} nodes")
            assertTrue(chosen.searches <= settings.topN)
        }
    }

    @Test
    fun `a Level with a depth cap stops at it`() {
        val settings = LevelSettings(nodes = 1_000_000, depth = 3, topN = 2, marginCp = 500, wallMs = null)
        val chosen = LevelPlayer(PirarucuEngine()).play(MIDDLEGAME, emptyList(), settings, 1)
        assertTrue(chosen.candidates.all { it.depth <= 3 }, "$chosen")
        assertTrue(chosen.nodes < 100_000, "$chosen")
    }

    @Test
    fun `Think time caps Level 8 through the host`() = runBlocking {
        val engine = RecordingEngine()
        val host = EngineHost { engine }
        val chosen = host.play(LevelRequest(Position.START_FEN, emptyList(), Level.EIGHT, 1, ThinkTime.THREE_SECONDS))
        assertEquals(1, engine.requests.size)
        val limits = engine.requests.single().limits
        assertEquals(3_000L, limits.wallMs)
        assertEquals(3_000_000L, limits.nodes)
        assertTrue(chosen.elapsedMs in 2_950L..3_300L, "took ${chosen.elapsedMs} ms")
        assertTrue(chosen.moveNow)
        assertEquals("e2e4", chosen.move)
    }

    @Test
    fun `Think time caps a real Level 8 search`() = runBlocking {
        // 3 s at the LP3's speed is ~1.7M nodes; a Mac reaches the 3M budget first. A 400 ms wall cap
        // with the same budget shows the cap ends the real search on time.
        val settings = Level.EIGHT.settings().copy(wallMs = 400)
        val host = EngineHost { PirarucuEngine() }
        val moveNow = MoveNow()
        val search = async {
            kotlinx.coroutines.withContext(host.dispatcher) {
                LevelPlayer(PirarucuEngine()).play(MIDDLEGAME, emptyList(), settings, 1, moveNow)
            }
        }
        delay(400)
        moveNow.request()
        val chosen = search.await()
        assertTrue(chosen.elapsedMs in 380L..500L, "took ${chosen.elapsedMs} ms")
        assertTrue(chosen.trueDepth > 5 && legalIn(MIDDLEGAME, emptyList(), chosen.move), "$chosen")
    }

    @Test
    fun `Move now at every Level plays a legal Move from what was found`() = runBlocking {
        val host = EngineHost { PirarucuEngine() }
        for (level in Level.entries) {
            val request = LevelRequest(MIDDLEGAME, emptyList(), level, 3, ThinkTime.THIRTY_SECONDS)
            val search = async { host.play(request) }
            delay(if (level == Level.EIGHT) 500 else 0)
            val stoppedAt = System.nanoTime()
            host.stop()
            val chosen = search.await()
            val latencyMs = (System.nanoTime() - stoppedAt) / 1_000_000
            assertTrue(legalIn(MIDDLEGAME, emptyList(), chosen.move), "$level $chosen")
            assertTrue(chosen.move in chosen.candidates.map { it.move })
            if (level == Level.EIGHT) {
                // Level 8 plays the last completed iteration's Move, well short of 30 s.
                assertTrue(latencyMs < 50, "Move now took $latencyMs ms")
                assertTrue(chosen.moveNow && chosen.trueDepth > 5 && chosen.elapsedMs < 1_000, "$chosen")
                assertEquals(chosen.bestMove, chosen.move)
            }
        }
    }

    @Test
    fun `Move now during sampling starts no further search`() {
        val engine = RecordingEngine(stopAfterSearches = 2)
        val moveNow = MoveNow()
        engine.onSearch = { if (engine.requests.size == 2) moveNow.request() }
        val settings = LevelSettings(nodes = 1_000, depth = null, topN = 5, marginCp = 1_000, wallMs = null)
        val chosen = LevelPlayer(engine).play(Position.START_FEN, emptyList(), settings, 1, moveNow)
        assertEquals(2, chosen.searches)
        assertEquals(listOf("e2e4", "d2d4"), chosen.candidates.map { it.move })
        assertTrue(chosen.moveNow)
    }

    @Test
    fun `Move now before the first search completes plays the fallback Move`() {
        val moveNow = MoveNow().apply { request() }
        val settings = Level.FIVE.settings()
        val chosen = LevelPlayer(PirarucuEngine()).play(MIDDLEGAME, emptyList(), settings, 1, moveNow)
        assertEquals(1, chosen.searches)
        assertEquals(Position.fromFen(MIDDLEGAME).legalMoves.first().uci, chosen.move)
    }

    @Test
    fun `the true score for draw offers comes from the unrestricted search, not the pick`() {
        // Black is a queen down: every candidate scores far below 0, and the true score is the best.
        val fen = "4k3/8/8/8/8/8/3Q4/4K3 b - - 0 1"
        val settings = LevelSettings(nodes = 5_000, depth = null, topN = 4, marginCp = 500, wallMs = null)
        val chosen = LevelPlayer(PirarucuEngine()).play(fen, emptyList(), settings, 5)
        assertTrue(chosen.trueScore < -500, "$chosen")
        assertEquals(chosen.candidates.maxOf { it.score }, chosen.trueScore)
        val plain = PirarucuEngine().search(SearchRequest(fen, limits = SearchLimits(nodes = 5_000)))
        assertEquals(plain.score, chosen.trueScore)
        assertEquals(plain.bestMove, chosen.bestMove)
    }

    @Test
    fun `when fewer legal Moves exist than the Level samples, every one may be a candidate`() {
        val two = "7k/8/8/8/8/8/8/K6q w - - 0 1" // White's king has Ka2 and Kb2
        assertEquals(setOf("a1a2", "a1b2"), Position.fromFen(two).legalMoves.map { it.uci }.toSet())
        val a = LevelPlayer(PirarucuEngine()).play(LevelRequest(two, emptyList(), Level.ONE, 1))
        assertTrue(a.searches <= 2 && a.move in setOf("a1a2", "a1b2"), "$a")

        val one = "7k/8/8/8/8/8/r7/K1r5 w - - 0 1" // only Kxa2
        assertEquals(listOf("a1a2"), Position.fromFen(one).legalMoves.map { it.uci })
        val b = LevelPlayer(PirarucuEngine()).play(LevelRequest(one, emptyList(), Level.ONE, 1))
        assertEquals("a1a2", b.move)
        assertEquals(1, b.searches)
    }

    /** Answers every search with the next Move of the start Position's list, and waits out a wall limit. */
    private class RecordingEngine(val stopAfterSearches: Int = Int.MAX_VALUE) : Engine {
        val requests = mutableListOf<SearchRequest>()
        var onSearch: () -> Unit = {}
        private val order = listOf("e2e4", "d2d4", "g1f3", "c2c4", "b1c3")

        override fun search(request: SearchRequest, handle: StopHandle, progress: (SearchProgress) -> Unit): SearchResult {
            requests += request
            onSearch()
            val wall = request.limits.wallMs
            if (wall != null) {
                val until = System.nanoTime() + (wall + 2_000) * 1_000_000
                while (!handle.stopped && System.nanoTime() < until) Thread.sleep(1)
            }
            val move = order.first { it !in request.limits.excludedRootMoves }
            return SearchResult(move, -10 * requests.size, 10, 1_000, 0, fallback = false)
        }

        override fun stop(handle: StopHandle) {
            handle.stopped = true
        }

        override fun newGame() {}
    }

    companion object {
        const val LP3_NODES_PER_SECOND = 558_000L
        const val MIDDLEGAME = "r1bq1rk1/2p1bppp/p1np1n2/1p2p3/4P3/1BPP1N2/PP3PPP/RNBQR1K1 w - - 0 9"
        val POSITIONS = listOf(
            Position.START_FEN,
            MIDDLEGAME,
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4",
        )
    }
}
