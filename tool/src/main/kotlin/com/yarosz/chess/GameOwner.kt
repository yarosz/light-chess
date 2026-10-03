package com.yarosz.chess

import android.os.SystemClock
import android.util.Log
import com.yarosz.chess.board.Motion
import com.yarosz.chess.book.Book
import com.yarosz.chess.engine.EngineHost
import com.yarosz.chess.games.ComputerReply
import com.yarosz.chess.games.GameChoices
import com.yarosz.chess.games.GameFlow
import com.yarosz.chess.games.GameRecord
import com.yarosz.chess.games.GameState
import com.yarosz.chess.games.GameStore
import com.yarosz.chess.games.Phase
import com.yarosz.chess.rules.Move
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one owner of the game mode in this process: the [GameState], the computer's searches on
 * [EngineHost.shared], Game Hints, the clock and `games.json`. LightOS relaunches the activity in the
 * same process without clearing old view models (PLATFORM.md), so, as with [PuzzleOwner], the screens'
 * view models are views onto this.
 *
 * Every change happens on the main thread. The file is written on [io] after every change (a
 * force-stop skips onAppPause, so the user's last Move must already be on disk); [pause] (onAppPause)
 * stops the search and writes at once on the calling thread; [resume] re-runs the computer's search if
 * it was thinking (B6), from the Game's seed. The Book comes through the latest screen that asked for
 * the owner ([of]), as [PuzzleOwner]'s assets do (V4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameOwner(
    filesDir: File,
    @Volatile private var readAsset: (String) -> ByteArray,
    /** The process's one engine; a test passes its own. */
    private val host: EngineHost = EngineHost.shared,
    /** The main thread; a test passes an unconfined scope, as `FriendOwnerTest` does. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val store = GameStore(filesDir)

    private val states = MutableStateFlow<GameState?>(null)

    /** Null until `games.json` is read. */
    val state: StateFlow<GameState?> = states

    private val motions = MutableStateFlow<Motion?>(null)

    /** The computer's Move, sliding in over 200 ms as it lands (F11). */
    val motion: StateFlow<Motion?> = motions
    private var motionId = 0
    private var slideEnd: Job? = null

    private val histories = MutableStateFlow<List<GameRecord>>(emptyList())

    /** The finished Games that read, newest first (B7: the last 50). */
    val history: StateFlow<List<GameRecord>> = histories

    private val awakeFlow = MutableStateFlow(false)

    /** Keep the screen on (contradiction 4, B6): see [GameState.awake]. */
    val awake: StateFlow<Boolean> = awakeFlow
    private var recentInput = true
    private var idle: Job? = null

    private var paused = true
    private var thinking: Job? = null
    private var hinting: Job? = null
    private var hintTimer: Job? = null
    private var userMovedAt = 0L

    private var book: Book? = null
    private var bookRead = false

    init {
        scope.launch {
            val opened = withContext(Dispatchers.Default) { GameFlow.open(store.load()) }
            states.value = opened
            refreshHistory()
            updateAwake()
            schedule()
        }
    }

    // --- Actions (main thread) ---

    fun play(move: Move) {
        val before = states.value ?: return
        val next = GameFlow.play(before, move)
        if (next === before) return
        stopHint()
        userMovedAt = SystemClock.uptimeMillis()
        motions.value = null
        set(next)
    }

    fun moveNow() {
        touched()
        if (states.value?.phase == Phase.COMPUTER) host.stop()
    }

    fun takeback() {
        touched()
        val before = states.value ?: return
        val next = GameFlow.takeback(before)
        if (next === before) return
        stopThinking()
        stopHint()
        motions.value = null
        set(next)
    }

    fun hint() {
        touched()
        val before = states.value ?: return
        val asked = GameFlow.askHint(before)
        if (asked === before) return
        set(asked)
        val request = GameFlow.hintRequest(asked) ?: return
        val game = asked.record!!.game
        hinting = scope.launch {
            val result = try {
                host.search(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "hint search failed", e)
                null
            }
            hinting = null
            val now = states.value ?: return@launch
            if (result == null) {
                set(now.copy(hintPending = false))
                return@launch
            }
            set(GameFlow.hintFound(now, game, result.bestMove))
            hintTimer?.cancel()
            hintTimer = scope.launch {
                delay(HINT_MS)
                states.value?.let { set(GameFlow.hideHint(it)) }
            }
        }
    }

    fun offerDraw() = act(GameFlow::offerDraw)

    /** Resign's first tap asks for a second; returns true when the Game is over. */
    fun resign(): Boolean {
        act(GameFlow::resign)
        val over = states.value?.phase == Phase.OVER
        if (over) {
            stopThinking()
            stopHint()
        }
        return over
    }

    fun cancelConfirm() {
        states.value?.let { set(GameFlow.cancelConfirm(it)) }
    }

    fun flip() = act(GameFlow::flip)

    fun setThinkTime(seconds: Int) = act { GameFlow.setThinkTime(it, seconds) }

    /** Starts a Game from [choices]; returns true when it started (false: the second tap is needed). */
    fun start(choices: GameChoices): Boolean {
        touched()
        val before = states.value ?: return false
        val next = GameFlow.start(before, choices, Random.nextLong(), LocalDate.now().format(DATE))
        if (next.record === before.record) {
            set(next)
            return false
        }
        stopThinking()
        stopHint()
        motions.value = null
        userMovedAt = SystemClock.uptimeMillis()
        set(next)
        // Level 8's table carries over between its Moves; a new Game starts it clean.
        scope.launch { runCatching { host.newGame() } }
        return true
    }

    /** A touch or a wheel event: restarts the five minutes (contradiction 4). */
    fun touched() {
        recentInput = true
        idle?.cancel()
        idle = scope.launch {
            delay(PuzzleOwner.AWAKE_MS)
            recentInput = false
            updateAwake()
        }
        updateAwake()
    }

    /**
     * onAppPause, or the board leaving for Home: stop the search and write the file now, on this
     * thread (B6, PLATFORM.md). A Game Hint being found stops with it, and one on show goes, so the
     * strip offers Hint again when the board returns: a cancelled search never clears its own
     * "Finding a Game Hint", and the timer that hides a shown one is cancelled too.
     */
    fun pause() {
        paused = true
        stopThinking()
        stopHint()
        val state = states.value?.let(GameFlow::dropHint) ?: return
        states.value = state
        runCatching { store.save(state.data) }.onFailure { Log.w(TAG, "games.json save failed", it) }
    }

    /** The game mode is on screen again: the computer's search runs again if it was thinking (B6). */
    fun resume() {
        paused = false
        touched()
        schedule()
    }

    // --- Internals ---

    private fun act(change: (GameState) -> GameState) {
        val state = states.value ?: return
        touched()
        set(change(state))
    }

    private fun set(next: GameState) {
        val before = states.value
        states.value = next
        if (next.data != before?.data) {
            if (next.data.finished != before?.data?.finished) refreshHistory()
            scope.launch(io) {
                val latest = states.value ?: return@launch
                runCatching { store.save(latest.data) }.onFailure { Log.w(TAG, "games.json save failed", it) }
            }
        }
        if (next.phase != Phase.COMPUTER) stopThinking()
        updateAwake()
        schedule()
    }

    /** Starts the computer's search when it is its Move and nothing is thinking yet. */
    private fun schedule() {
        if (paused || thinking != null) return
        val state = states.value ?: return
        if (state.phase != Phase.COMPUTER) return
        val job = scope.launch {
            val book = withContext(Dispatchers.IO) { loadBook() }
            val turn = GameFlow.computerReply(states.value ?: return@launch, book) ?: return@launch
            val (uci, score) = think(turn)
            val wait = REPLY_MS - (SystemClock.uptimeMillis() - userMovedAt)
            if (wait > 0) delay(wait)
            ensureActive()
            val now = states.value ?: return@launch
            val next = GameFlow.computerMoved(now, turn, uci, score)
            thinking = null
            if (next === now) return@launch
            next.record?.game?.moves?.lastOrNull()?.let(::slide)
            set(next)
        }
        thinking = job
        job.invokeOnCompletion { if (thinking === job) thinking = null }
    }

    /** The computer's Move for [turn] and its true evaluation (null for a book Move). */
    private suspend fun think(turn: ComputerReply): Pair<String, Int?> {
        turn.bookMove?.let { return it.uci to null }
        if (turn.freshEngine) host.newGame()
        currentCoroutineContext().ensureActive()
        val started = SystemClock.uptimeMillis()
        val played = host.play(turn.request)
        Log.i(PERF_TAG, "computer move level=${turn.request.level.number} ms=${SystemClock.uptimeMillis() - started} nodes=${played.nodes} moveNow=${played.moveNow}")
        // A search stopped before depth 1 (a tiny budget in check, or Move now at once) has no eval to judge a draw on.
        return played.move to played.trueScore.takeIf { played.trueDepth > 0 }
    }

    private fun stopThinking() {
        val job = thinking ?: return
        thinking = null
        job.cancel()
        host.stop()
    }

    private fun stopHint() {
        hintTimer?.cancel()
        val job = hinting ?: return
        hinting = null
        job.cancel()
        host.stop()
    }

    /**
     * Slides the computer's [move] in. Once it has played, the slide is over: a board that comes back
     * (from its Menu, or a Menu action) shows the Move where it landed, as Puzzles do.
     */
    private fun slide(move: Move) {
        val motion = Motion(move, ++motionId, Motion.ENGINE_MS)
        motions.value = motion
        slideEnd?.cancel()
        slideEnd = scope.launch {
            delay(SLIDE_KEPT_MS)
            if (motions.value == motion) motions.value = null
        }
    }

    private fun loadBook(): Book? {
        if (!bookRead) {
            bookRead = true
            book = runCatching { Book.load(readAsset) }.onFailure { Log.w(TAG, "book didn't load", it) }.getOrNull()
        }
        return book
    }

    private fun refreshHistory() {
        val data = states.value?.data ?: return
        scope.launch {
            histories.value = withContext(Dispatchers.Default) { data.history() }
        }
    }

    private fun updateAwake() {
        awakeFlow.value = !paused && states.value?.awake(recentInput) == true
    }

    companion object {
        private const val TAG = "Chess"

        /** The computer's Move lands no sooner than this after the user's (A5's reply delay). */
        const val REPLY_MS = 300L

        /** How long the computer's slide stays published: long enough for the board on screen to play it. */
        const val SLIDE_KEPT_MS = 2L * Motion.ENGINE_MS

        /** How long a Game Hint stays on the board ("briefly", B5). */
        const val HINT_MS = 5_000L

        private val DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd")

        private val owners = HashMap<String, GameOwner>()

        /**
         * The process's owner of [filesDir], made the first time it is asked for. It reads the assets
         * through [readAsset] from then on, in place of the reader of the screen that asked before (V4).
         */
        fun of(filesDir: File, readAsset: (String) -> ByteArray): GameOwner = synchronized(owners) {
            owners.getOrPut(filesDir.canonicalPath) { GameOwner(filesDir, readAsset) }.also { it.readAsset = readAsset }
        }
    }
}
