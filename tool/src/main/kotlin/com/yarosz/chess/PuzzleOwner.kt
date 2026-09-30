package com.yarosz.chess

import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.yarosz.chess.board.Motion
import com.yarosz.chess.board.PieceSet
import com.yarosz.chess.puzzles.Attempt
import com.yarosz.chess.puzzles.Pack
import com.yarosz.chess.puzzles.PuzzleData
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
import kotlinx.coroutines.cancel
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
 * every change to what it keeps except the Moves of the Attempt on screen ([PuzzleState.kept]);
 * [flush] (onAppPause) writes everything at once on the calling thread. The Band files are read
 * ahead on [Dispatchers.Default], so choosing the next Puzzle and opening a Missed one read none on
 * the main thread.
 *
 * The assets come through the latest screen that asked for the owner ([of]): the SDK reads them only
 * through a screen's activity, so holding the first screen's reader would keep the first activity
 * alive for the life of the process after LightOS relaunches a new one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PuzzleOwner(
    filesDir: File,
    @Volatile private var readAsset: (String) -> ByteArray,
    /** The main thread; a test passes an unconfined scope (`GameOwnerTest`). */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val pack = Pack { path -> readAsset(path) }
    private val flow = PuzzleFlow(pack)
    private val store = PuzzleStore(filesDir)

    private val sessions = MutableStateFlow<PuzzleState?>(null)

    /** Null until the save file and the first Puzzle are read. */
    val session: StateFlow<PuzzleState?> = sessions

    private val pieceSets = MutableStateFlow<PieceSet?>(null)

    /**
     * The Piece Set every board draws (P2, M1), null only until `puzzles.json` is read. It comes from
     * the file before any Band is read (M4), so the game screen and the game Menu have it from their
     * first frame instead of waiting for the first Puzzle.
     */
    val pieceSet: StateFlow<PieceSet?> = pieceSets

    private val motions = MutableStateFlow<Motion?>(null)

    /** The Move the board animates as it lands: the setup Move, each reply and each Solution Move. */
    val motion: StateFlow<Motion?> = motions
    private var motionId = 0
    private var slideEnd: Job? = null

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
            val saved = withContext(Dispatchers.Default) { store.load() }
            pieceSets.value = (saved ?: PuzzleData()).pieceSet
            val opened = withContext(Dispatchers.Default) {
                flow.open(saved).also { it.attempt?.positions }
            }
            Log.i(PERF_TAG, "session loaded ms=${SystemClock.uptimeMillis() - started}")
            set(withPieceSet(opened, pieceSets.value), save = true)
            touched()
        }
    }

    fun seed(rating: Double) = act { flow.seed(it, rating) }

    fun play(move: Move) = act { flow.play(it, move) }

    fun hint() = act(flow::hint)

    fun showSolution() = act(flow::showSolution)

    fun next() = act(flow::next)

    /**
     * A Missed row's tap: true when the replay started. False, and the page stays where it is, for a
     * Puzzle the Pack no longer has ([missedGone], N13) and for one the read ahead hasn't reached yet:
     * the main thread never reads a Band file, so that tap waits for [prefetchMissed], after which the
     * row either replays or shows lightened.
     */
    fun replayMissed(id: String): Boolean {
        val session = sessions.value ?: return false
        if (id in gone.value) return false
        if (!flow.readAhead(id)) {
            if (missedCheck?.isActive != true) prefetchMissed()
            return false
        }
        val next = flow.replayMissed(session, id)
        if (next === session) return false
        touched()
        set(next)
        return true
    }

    /** The Puzzles page's first row (N12): the rated Puzzle, ready for the board it opens. */
    fun toRated() = act(flow::toRated)

    private val gone = MutableStateFlow<Set<String>>(emptySet())

    /** Missed Puzzles the Pack no longer has (N13): their rows are lightened and do nothing. */
    val missedGone: StateFlow<Set<String>> = gone

    fun resetRating() = act(flow::resetRating)

    /** A tap on a Menu's Pieces row. Before the first Puzzle is read, the choice waits for it (M4). */
    fun nextPieceSet() {
        if (sessions.value == null) pieceSets.value = pieceSets.value?.next else act(flow::nextPieceSet)
    }

    /** The last [prefetchMissed], while it runs. */
    private var missedCheck: Job? = null

    /**
     * The Puzzles page or Missed is open: read the Missed Puzzles ahead, so a tap on one reads no file
     * (D2), and find the ones the Pack no longer has (N13), so their rows show lightened and the count
     * leaves them out. An id found gone is never looked for again in this process (the Pack is the
     * Tool's own assets), so once every row is known each open reads at most the Bands of the rows
     * still there, and none when the last call found them. A call while one runs does nothing.
     */
    fun prefetchMissed() {
        val session = sessions.value ?: return
        // One check at a time: the Missed list changes only on the board, never under these pages.
        if (missedCheck?.isActive == true) return
        val known = gone.value
        missedCheck = scope.launch {
            val missing = withContext(Dispatchers.Default) {
                runCatching { flow.prefetchMissed(session, known) }
                    .onFailure { Log.w(TAG, "prefetch failed", it) }
                    .getOrDefault(emptySet())
            }
            if (missing.isNotEmpty()) gone.value = gone.value + missing
        }
    }

    /** Stops this owner's work (its clock, its saves): for tests, which make owners of their own. */
    internal fun close() = scope.cancel()

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
        pieceSets.value = next.data.pieceSet
        val was = before?.attempt
        val now = next.attempt
        val motion = slideAfter(was, now, motions.value, motionId + 1)
        if (motion != motions.value) {
            if (motion != null) motionId = motion.id
            motions.value = motion
            // Once it has played, the slide is over: a screen that comes back doesn't play it again.
            slideEnd?.cancel()
            if (motion != null) {
                slideEnd = scope.launch {
                    delay(SLIDE_KEPT_MS)
                    if (motions.value == motion) motions.value = null
                }
            }
        }
        if (restartsClock(was, now)) {
            timer?.cancel()
            timer = now?.delayMs?.let { ms ->
                scope.launch {
                    delay(ms)
                    set(flow.advance(sessions.value ?: return@launch))
                }
            }
        }
        if (next.current?.puzzle != before?.current?.puzzle || next.data.rating != before?.data?.rating) {
            scope.launch(Dispatchers.Default) { prefetch { flow.prefetchNext(next) } }
        }
        if (next.upNext != null && next.upNext != before?.upNext) {
            val upNext = next.upNext
            scope.launch(Dispatchers.Default) { upNext.position }
        }
        if (save || next.kept != before?.kept) {
            scope.launch(io) {
                val latest = sessions.value ?: return@launch
                runCatching { store.save(latest.toData()) }.onFailure { Log.w(TAG, "puzzles.json save failed", it) }
            }
        }
        updateAwake()
    }

    /** A read ahead that fails costs nothing: the main thread reads the file itself, as it would have. */
    private fun prefetch(read: () -> Unit) {
        runCatching(read).onFailure { Log.w(TAG, "prefetch failed", it) }
    }

    private fun updateAwake() {
        awakeFlow.value = recentInput && sessions.value?.attempt?.underWay == true
    }

    companion object {
        /** D3 and contradiction 4: five minutes since the last touch or wheel event. */
        const val AWAKE_MS = 5 * 60 * 1000L

        /** How long a slide stays after it starts: its 250 ms and a margin for the first frame. */
        private const val SLIDE_KEPT_MS = 2L * Motion.MS

        private val owners = HashMap<String, PuzzleOwner>()

        /**
         * The process's owner of [filesDir], made the first time it is asked for. It reads the assets
         * through [readAsset] from then on, in place of the reader of the screen that asked before.
         */
        fun of(filesDir: File, readAsset: (String) -> ByteArray): PuzzleOwner = synchronized(owners) {
            owners.getOrPut(filesDir.canonicalPath) { PuzzleOwner(filesDir, readAsset) }.also { it.readAsset = readAsset }
        }
    }
}

/**
 * The session [opened] from the file, with the Piece Set [chosen] while it was being read (M4): a
 * tap on a Pieces row before the first Puzzle is read isn't lost. Null (not read yet) changes nothing.
 */
internal fun withPieceSet(opened: PuzzleState, chosen: PieceSet?): PuzzleState =
    if (chosen == null || chosen == opened.data.pieceSet) opened else opened.copy(data = opened.data.copy(pieceSet = chosen))

/** A stage whose Moves play themselves, so the board animates them. */
private val Stage.auto: Boolean get() = this != Stage.PLAY && this != Stage.DONE

/**
 * The slide the board shows once [now] replaces [was] (A5, F11), given the slide [current] shown so
 * far: a Move that played itself (setup, reply, Solution) slides in as [id]. Otherwise [current]
 * stays only while its Move is still the latest, so the user's own Move onto that square never slides
 * in from the opponent's origin, and a new Puzzle starts with none.
 */
internal fun slideAfter(was: Attempt?, now: Attempt?, current: Motion?, id: Int): Motion? = when {
    now != null && was != null && now.puzzle == was.puzzle && now.moves.size == was.moves.size + 1 && was.stage.auto ->
        Motion(now.moves.last(), id)
    now?.puzzle != was?.puzzle -> null
    current != null && now?.moves?.lastOrNull() != current.move -> null
    else -> current
}

/**
 * The stage clock (A5) starts again only when the Attempt on screen changed. A tap that changes
 * nothing, like Hint while the reply is pending, leaves the running wait alone instead of delaying it.
 */
internal fun restartsClock(was: Attempt?, now: Attempt?): Boolean = now != was
