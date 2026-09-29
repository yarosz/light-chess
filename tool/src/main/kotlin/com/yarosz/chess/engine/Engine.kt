package com.yarosz.chess.engine

/**
 * The computer's side of the engine boundary (ADR 0001): a start Position and the Moves played from
 * it go in as FEN and UCI text, a best Move and what the search found come out. Our rules core checks
 * the input and every Move the engine returns; the engine's own board never becomes game state.
 *
 * [search] blocks the calling thread until a limit is reached or [stop] is called, so call it on the
 * engine thread ([EngineHost] owns it). [stop] may be called from any thread.
 */
interface Engine {

    fun search(request: SearchRequest, handle: StopHandle = StopHandle(), progress: (SearchProgress) -> Unit = {}): SearchResult

    /**
     * Ends the search [handle] belongs to, whether it is running or not yet started. The search then
     * returns the best Move of its last completed iteration.
     */
    fun stop(handle: StopHandle)

    /** Forgets what earlier searches learned (transposition table, pawn cache, move history). */
    fun newGame()
}

/** The Position to search: [startFen] followed by [moves] (UCI), all legal. */
data class SearchRequest(
    val startFen: String,
    val moves: List<String> = emptyList(),
    val limits: SearchLimits,
)

/**
 * When a search ends, whichever comes first. At least one of [nodes], [depth] and [wallMs] is set.
 * [excludedRootMoves] (UCI) are never played; at least one legal Move must remain.
 */
data class SearchLimits(
    val nodes: Long? = null,
    val depth: Int? = null,
    val wallMs: Long? = null,
    val excludedRootMoves: Set<String> = emptySet(),
) {
    init {
        require(nodes != null || depth != null || wallMs != null) { "a search needs a node, depth or time limit" }
        require(nodes == null || nodes > 0) { "nodes must be positive" }
        require(depth == null || depth in 1..MAX_DEPTH) { "depth must be in 1..$MAX_DEPTH" }
        require(wallMs == null || wallMs > 0) { "wallMs must be positive" }
    }

    companion object {
        const val MAX_DEPTH = 100
    }
}

/**
 * [bestMove] is legal in the searched Position (our rules core checked it), in UCI. [score] is in
 * centipawns from the side to move's view. [depth] is the last completed iteration, 0 when the search
 * stopped before depth 1 and [fallback] is true: [bestMove] is then our core's first legal Move.
 */
data class SearchResult(
    val bestMove: String,
    val score: Int,
    val depth: Int,
    val nodes: Long,
    val elapsedMs: Long,
    val fallback: Boolean,
)

/** One completed iteration, reported while the search runs (on the engine thread). */
data class SearchProgress(val depth: Int, val nodes: Long, val elapsedMs: Long)

/** Stops one search: see [Engine.stop]. */
class StopHandle {
    @Volatile
    var stopped = false
        internal set
}
