package com.yarosz.chess

import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.yarosz.chess.board.Motion
import com.yarosz.chess.puzzles.Pack
import com.yarosz.chess.puzzles.PuzzleFlow
import com.yarosz.chess.puzzles.PuzzleStore
import com.yarosz.chess.puzzles.PuzzleState
import com.yarosz.chess.puzzles.Stage
import com.yarosz.chess.rules.Move
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Logcat tag for timings (F10: cold start to the first Puzzle). */
const val PERF_TAG = "ChessPerf"
private const val TAG = "Chess"

/**
 * The one owner of the puzzle mode in this process: the [PuzzleState], its clock, and the save file.
 * LightOS relaunches the activity in the same process without clearing old view models
 * (PLATFORM.md), so no view model may own this: [of] returns the process's owner, and the screens'
 * view models are views onto it, as Reader's `ShelfOwner` is (its ADR 0008).
 *
 * Every change happens on the main thread. The file is written on [io], one save at a time, after
 * every change to what it keeps except the Moves of the Attempt on screen; [flush] (onAppPause)
 * writes everything at once on the calling thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PuzzleOwner(filesDir: File, private val readAsset: (String) -> ByteArray) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val pack = Pack(readAsset)
    private val flow = PuzzleFlow(pack)
    private val store = PuzzleStore(filesDir)

    private val sessions = MutableStateFlow<PuzzleState?>(null)

    /** Null until the save file and the first Puzzle are read. */
    val session: StateFlow<PuzzleState?> = sessions

    private val motions = MutableStateFlow<Motion?>(null)

    /** The Move the board animates as it lands: the setup Move, each reply and each Solution Move. */
    val motion: StateFlow<Motion?> = motions
    private var motionId = 0

    private val awakeFlow = MutableStateFlow(false)

    /** Keep the screen on: an Attempt is under way and the last touch or wheel event was < 5 min ago (D3). */
    val awake: StateFlow<Boolean> = awakeFlow
    private var recentInput = true
    private var idle: Job? = null
    private var timer: Job? = null
    private var firstDrawLogged = false
    private var seedShown = false

    init {
        scope.launch {
            val started = SystemClock.uptimeMillis()
            val opened = withContext(Dispatchers.Default) {
                flow.open(store.load()).also { it.attempt?.positions }
            }
            Log.i(PERF_TAG, "session loaded ms=${SystemClock.uptimeMillis() - started}")
            set(opened, save = true)
            touched()
        }
    }

    fun seed(rating: Double) = act { flow.seed(it, rating) }

    fun play(move: Move) = act { flow.play(it, move) }

    fun hint() = act(flow::hint)

    fun showSolution() = act(flow::showSolution)

    fun next() = act(flow::next)

    fun replayMissed(id: String) = act { flow.replayMissed(it, id) }

    fun resetRating() = act(flow::resetRating)

    /** A touch or a wheel event: restarts D3's five minutes. */
    fun touched() {
        recentInput = true
        idle?.cancel()
        idle = scope.launch {
            delay(AWAKE_MS)
            recentInput = false
            updateAwake()
        }
        updateAwake()
    }

    /** Writes the file now, on this thread: onAppPause may be the last chance before a kill (PLATFORM.md). */
    fun flush() {
        val session = sessions.value ?: return
        runCatching { store.save(session.toData()) }.onFailure { Log.w(TAG, "puzzles.json save failed", it) }
    }

    /** The first Puzzle drawn in this process: F10's cold start, logged once. */
    fun firstPuzzleDrawn(id: String) {
        if (firstDrawLogged) return
        firstDrawLogged = true
        val ms = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
        Log.i(PERF_TAG, "first puzzle drawn ms=$ms since process start, id=$id, afterSeedScreen=$seedShown")
    }

    /** The Lichess dump the Pack comes from, for About (D7). The manifest is already read by the time the Menu opens. */
    val packDate: String? get() = runCatching { pack.manifest.source?.date }.getOrNull()

    /** About's legal notices (a 4 KB asset), read once. */
    val notices: String by lazy { runCatching { readAsset(UiCopy.NOTICES_ASSET).decodeToString() }.getOrDefault("") }

    fun seedScreenShown() {
        seedShown = true
    }

    private fun act(change: (PuzzleState) -> PuzzleState) {
        val session = sessions.value ?: return
        touched()
        set(change(session))
    }

    private fun set(next: PuzzleState, save: Boolean = false) {
        val before = sessions.value
        sessions.value = next
        val was = before?.attempt
        val now = next.attempt
        if (now != null && was != null && now.puzzle == was.puzzle && now.moves.size == was.moves.size + 1 && was.stage.auto) {
            motions.value = Motion(now.moves.last(), ++motionId)
        } else if (now?.puzzle != was?.puzzle) {
            motions.value = null
        }
        timer?.cancel()
        now?.delayMs?.let { ms ->
            timer = scope.launch {
                delay(ms)
                set(flow.advance(sessions.value ?: return@launch))
            }
        }
        if (next.upNext != null && next.upNext != before?.upNext) {
            val upNext = next.upNext
            scope.launch(Dispatchers.Default) { upNext.position }
        }
        if (save || next.data != before?.data || next.current?.puzzle != before?.current?.puzzle) {
            scope.launch(io) {
                val latest = sessions.value ?: return@launch
                runCatching { store.save(latest.toData()) }.onFailure { Log.w(TAG, "puzzles.json save failed", it) }
            }
        }
        updateAwake()
    }

    /** A stage whose Moves play themselves, so the board animates them. */
    private val Stage.auto: Boolean get() = this != Stage.PLAY && this != Stage.DONE

    private fun updateAwake() {
        awakeFlow.value = recentInput && sessions.value?.attempt?.underWay == true
    }

    companion object {
        /** D3 and contradiction 4: five minutes since the last touch or wheel event. */
        const val AWAKE_MS = 5 * 60 * 1000L

        private val owners = HashMap<String, PuzzleOwner>()

        /** The process's owner of [filesDir], made the first time it is asked for. */
        fun of(filesDir: File, readAsset: (String) -> ByteArray): PuzzleOwner =
            synchronized(owners) { owners.getOrPut(filesDir.canonicalPath) { PuzzleOwner(filesDir, readAsset) } }
    }
}
