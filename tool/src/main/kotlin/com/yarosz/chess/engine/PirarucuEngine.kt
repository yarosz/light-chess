package com.yarosz.chess.engine

import com.yarosz.chess.rules.Position
import pirarucu.board.Board
import pirarucu.board.factory.BoardFactory
import pirarucu.cache.PawnEvaluationCache
import pirarucu.hash.TranspositionTable
import pirarucu.search.History
import pirarucu.search.MainSearch
import pirarucu.search.SearchInfo
import pirarucu.search.SearchInfoListener
import pirarucu.search.SearchOptions
import com.yarosz.chess.rules.Move as CoreMove
import pirarucu.move.Move as EngineMove

/**
 * [Engine] over the vendored Pirarucu (tool/src/main/kotlin/vendor/pirarucu, GPL-3.0). One instance
 * keeps its hash tables between searches; use it from one thread at a time (plus [stop] from any).
 * Hash sizes are always explicit: Pirarucu's defaults are 256 MB and 32 MB.
 */
class PirarucuEngine(ttMb: Int = TT_MB, pawnCacheMb: Int = PAWN_CACHE_MB) : Engine {

    init {
        require(ttMb in 1..64 && pawnCacheMb in 1..16) { "hash sizes out of range: $ttMb MB, $pawnCacheMb MB" }
    }

    private val transpositionTable = TranspositionTable(ttMb)
    private val pawnCache = PawnEvaluationCache(pawnCacheMb)
    private val history = History()
    private val options = SearchOptions()
    private val listener = Listener()
    private val mainSearch = MainSearch(options, listener, transpositionTable, pawnCache, history)

    // Guards `active` and the hand-over of `options.stop` between stop() and a search starting or ending.
    private val lock = Any()
    private var active: StopHandle? = null

    override fun search(request: SearchRequest, handle: StopHandle, progress: (SearchProgress) -> Unit): SearchResult {
        val limits = request.limits
        val line = replay(request)
        val position = line.positions.last()
        val legal = position.legalMoves
        require(legal.isNotEmpty()) { "no legal moves in ${position.fen}" }
        val excluded = limits.excludedRootMoves.map {
            requireNotNull(position.moveFromUci(it)) { "excluded move $it is not legal in ${position.fen}" }
        }.toSet()
        val allowed = legal.filter { it !in excluded }
        require(allowed.isNotEmpty()) { "every legal move is excluded" }

        val board = engineBoard(line)
        val excludedInts = excluded.map { EngineMove.getMove(board, it.uci) }.toIntArray()
        val started = System.nanoTime()
        listener.start(started, progress)
        synchronized(lock) {
            options.hasTimeLimit = limits.wallMs != null
            options.hasFixedTime = limits.wallMs != null
            options.minSearchTime = limits.wallMs ?: 0L
            options.maxSearchTime = limits.wallMs ?: 0L
            options.depth = limits.depth ?: SearchLimits.MAX_DEPTH
            options.nodeLimit = limits.nodes ?: 0L
            options.excludedRootMoves = excludedInts
            options.startControl()
            active = handle
            if (handle.stopped) options.stop = true
        }
        try {
            mainSearch.search(board)
        } finally {
            synchronized(lock) { active = null }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val nodes = mainSearch.searchInfo.searchNodes

        // ADR 0001: nothing the engine says is trusted until our rules core agrees it is legal here.
        val found = mainSearch.completedMove.takeIf { it != EngineMove.NONE }
            ?.let { position.moveFromUci(EngineMove.toString(it)) }
            ?.takeIf { it in allowed }
        return if (found != null) {
            SearchResult(found.uci, mainSearch.completedScore, mainSearch.completedDepth, nodes, elapsedMs, fallback = false)
        } else {
            SearchResult(allowed.first().uci, 0, 0, nodes, elapsedMs, fallback = true)
        }
    }

    override fun stop(handle: StopHandle) {
        synchronized(lock) {
            handle.stopped = true
            if (active === handle) options.stop = true
        }
    }

    override fun newGame() {
        transpositionTable.reset()
        pawnCache.reset()
        history.reset()
    }

    /** The Positions of a request's line, from its start Position on, and the legal Moves between them. */
    private class Line(val positions: List<Position>, val moves: List<CoreMove>)

    /** Throws when a Move of the request is not legal. */
    private fun replay(request: SearchRequest): Line {
        val positions = ArrayList<Position>(request.moves.size + 1)
        val moves = ArrayList<CoreMove>(request.moves.size)
        positions += Position.fromFen(request.startFen)
        for (uci in request.moves) {
            val position = positions.last()
            val move = requireNotNull(position.moveFromUci(uci)) { "move $uci is not legal in ${position.fen}" }
            moves += move
            positions += position.play(move)
        }
        return Line(positions, moves)
    }

    /**
     * Pirarucu's board for the searched Position. It starts from the last Position where the 50-move
     * count was reset (nothing before it can repeat) and replays the Moves after it, so the engine
     * still sees repetitions while its 1,024-entry game history never fills.
     */
    private fun engineBoard(line: Line): Board {
        val positions = line.positions
        val from = positions.indices.last { it == 0 || positions[it].halfmoveClock == 0 }
        val board = BoardFactory.getBoard(positions[from].fen)
        for (move in line.moves.subList(from, line.moves.size)) {
            board.doMove(EngineMove.getMove(board, move.uci))
        }
        return board
    }

    private inner class Listener : SearchInfoListener {
        private var started = 0L
        private var reported = 0
        private var progress: (SearchProgress) -> Unit = {}

        fun start(startedNanos: Long, onProgress: (SearchProgress) -> Unit) {
            started = startedNanos
            reported = 0
            progress = onProgress
        }

        override fun searchInfo(depth: Int, elapsedTime: Long, searchInfo: SearchInfo) {
            val completed = mainSearch.completedDepth
            if (completed > reported) {
                reported = completed
                progress(SearchProgress(completed, searchInfo.searchNodes, (System.nanoTime() - started) / 1_000_000))
            }
        }

        override fun bestMove(searchInfo: SearchInfo) {}
    }

    companion object {
        /** Transposition table size: 16 MB is about a million entries. */
        const val TT_MB = 16

        /** Pawn evaluation cache size. */
        const val PAWN_CACHE_MB = 2
    }
}
