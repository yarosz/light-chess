package com.yarosz.chess.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * The one engine of the process and the one thread it runs on. LightOS never clears an old
 * LightViewModel when it reopens a Tool (umbrella PLATFORM.md, "SDK lifecycle"), so a view model must
 * not own an engine: each one attaches to [shared], and the engine thread, its transposition table and
 * what it learned survive a new view model.
 *
 * Searches run one at a time, in order, on a single daemon thread at normal priority (decision E5).
 */
class EngineHost(newEngine: () -> Engine) {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD_NAME).apply { isDaemon = true }
    }

    /** The engine thread. Work that must not overlap a search (the benchmark) can run here too. */
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    private val engine: Engine by lazy(newEngine)

    @Volatile
    private var current: StopHandle? = null

    @Volatile
    private var currentPlay: MoveNow? = null

    /**
     * Searches on the engine thread and returns its result. A wall-time limit is enforced to the
     * millisecond by a timer here; Pirarucu itself only looks at the clock every 65,536 nodes.
     */
    suspend fun search(request: SearchRequest, progress: (SearchProgress) -> Unit = {}): SearchResult {
        val handle = StopHandle()
        current = handle
        return coroutineScope {
            val timer = request.limits.wallMs?.let { ms ->
                launch(Dispatchers.Default) {
                    delay(ms)
                    engine.stop(handle)
                }
            }
            try {
                withContext(dispatcher) { engine.search(request, handle, progress) }
            } finally {
                timer?.cancel()
                if (current === handle) current = null
            }
        }
    }

    /**
     * The computer's Move at a Level, found on the engine thread. The Level's wall-time cap (Think time,
     * at Level 8) is enforced here to the millisecond, as a Move now.
     */
    suspend fun play(request: LevelRequest): LevelMove {
        val moveNow = MoveNow()
        currentPlay = moveNow
        val settings = request.level.settings(request.thinkTime)
        return coroutineScope {
            val timer = settings.wallMs?.let { ms ->
                launch(Dispatchers.Default) {
                    delay(ms)
                    moveNow.request()
                }
            }
            try {
                withContext(dispatcher) {
                    LevelPlayer(engine).play(request.startFen, request.moves, settings, request.gameSeed, moveNow)
                }
            } finally {
                timer?.cancel()
                if (currentPlay === moveNow) currentPlay = null
            }
        }
    }

    /**
     * "Move now": ends the search or [play] in progress (or the one waiting to start). A search returns
     * the best Move of its last completed iteration; [play] picks among the Moves found so far.
     */
    fun stop() {
        current?.let { engine.stop(it) }
        currentPlay?.request()
    }

    /** Forgets what earlier searches learned, on the engine thread. */
    suspend fun newGame() = withContext(dispatcher) { engine.newGame() }

    companion object {
        const val THREAD_NAME = "chess-engine"

        /** The process-wide host every view model attaches to. */
        val shared: EngineHost by lazy { EngineHost { PirarucuEngine() } }
    }
}
