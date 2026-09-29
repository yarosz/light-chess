package com.yarosz.chess.engine

import com.yarosz.chess.rules.Position
import kotlin.random.Random

/**
 * The computer playing at a [Level] (decisions B2, E2, E3). One choice is a normal search, then up to
 * `topN - 1` more, each excluding the root Moves found so far; the Moves scoring within the Level's
 * margin of the best are the candidates, and one is picked at random. The first search is never
 * altered, so its score is the true evaluation that draw offers are judged on (G1), whatever Move is
 * picked.
 *
 * The random pick is seeded by the Game's seed and the Ply, so a Game replays exactly from its Moves
 * on a fresh engine. Runs on the engine thread, like [Engine.search].
 */
class LevelPlayer(private val engine: Engine) {

    fun play(request: LevelRequest, moveNow: MoveNow = MoveNow()): LevelMove =
        play(request.startFen, request.moves, request.level.settings(request.thinkTime), request.gameSeed, moveNow)

    /** [play] with explicit [settings]: for tests and calibration. */
    fun play(
        startFen: String,
        moves: List<String>,
        settings: LevelSettings,
        gameSeed: Long,
        moveNow: MoveNow = MoveNow(),
    ): LevelMove {
        val started = System.nanoTime()
        val maxSearches = minOf(settings.topN, legalMoveCount(startFen, moves))
        require(maxSearches > 0) { "no legal moves: the Game is over" }
        val candidates = ArrayList<Candidate>(maxSearches)
        var first: SearchResult? = null
        var nodes = 0L
        var searches = 0
        while (searches < maxSearches) {
            if (searches > 0 && moveNow.isRequested) break
            val limits = SearchLimits(
                nodes = settings.nodes,
                depth = settings.depth,
                wallMs = settings.wallMs,
                excludedRootMoves = candidates.mapTo(HashSet()) { it.move },
            )
            val handle = StopHandle()
            moveNow.attach(engine, handle)
            val result = try {
                engine.search(SearchRequest(startFen, moves, limits), handle)
            } finally {
                moveNow.detach()
            }
            searches++
            nodes += result.nodes
            if (first == null) {
                // Even a fallback (stopped before depth 1) is the Move to play when nothing else exists.
                first = result
            } else if (result.fallback || result.score < first.score - settings.marginCp) {
                break
            }
            candidates += Candidate(result.bestMove, result.score, result.depth)
        }
        val best = checkNotNull(first)
        val picked = if (candidates.size == 1) candidates[0] else candidates[random(gameSeed, moves.size).nextInt(candidates.size)]
        return LevelMove(
            move = picked.move,
            trueScore = best.score,
            trueDepth = best.depth,
            bestMove = best.bestMove,
            candidates = candidates,
            nodes = nodes,
            searches = searches,
            elapsedMs = (System.nanoTime() - started) / 1_000_000,
            moveNow = moveNow.isRequested,
        )
    }

    private fun legalMoveCount(startFen: String, moves: List<String>): Int {
        var position = Position.fromFen(startFen)
        for (uci in moves) {
            position = position.play(requireNotNull(position.moveFromUci(uci)) { "move $uci is not legal in ${position.fen}" })
        }
        return position.legalMoves.size
    }

    companion object {
        /** The pick for one Ply of one Game: independent of what was searched before it. */
        internal fun random(gameSeed: Long, ply: Int): Random = Random(gameSeed xor (ply + 1L) * -0x61c8864680b583ebL)
    }
}

/** The computer's move to find: the Game so far, its Level and Think time, and the Game's seed. */
data class LevelRequest(
    val startFen: String,
    val moves: List<String>,
    val level: Level,
    val gameSeed: Long,
    val thinkTime: ThinkTime = ThinkTime.DEFAULT,
)

/**
 * What the computer chose. [move] is the Move to play (UCI, checked by our core). [trueScore] and
 * [trueDepth] come from the first, unrestricted search, from the side to move's view: G1 judges draw
 * offers on them. [bestMove] is that search's Move, which [move] equals at Level 8. [candidates] are
 * the Moves [move] was picked from, the best first. [moveNow] is true when Move now or the Think
 * time ended the choice early.
 */
data class LevelMove(
    val move: String,
    val trueScore: Int,
    val trueDepth: Int,
    val bestMove: String,
    val candidates: List<Candidate>,
    val nodes: Long,
    val searches: Int,
    val elapsedMs: Long,
    val moveNow: Boolean,
)

/** One near-best root Move and what its search found. */
data class Candidate(val move: String, val score: Int, val depth: Int)

/**
 * "Move now" for one choice (E2): ends the search in progress, which keeps the Move of its last
 * completed iteration, and starts no further search. The computer then picks among what it has found.
 * [request] may be called from any thread, before or during the choice.
 */
class MoveNow {
    private val lock = Any()
    private var requested = false
    private var engine: Engine? = null
    private var search: StopHandle? = null

    val isRequested: Boolean get() = synchronized(lock) { requested }

    fun request() {
        synchronized(lock) {
            requested = true
            search?.let { engine?.stop(it) }
        }
    }

    internal fun attach(engine: Engine, handle: StopHandle) {
        synchronized(lock) {
            this.engine = engine
            search = handle
            if (requested) engine.stop(handle)
        }
    }

    internal fun detach() {
        synchronized(lock) { search = null }
    }
}
