package com.yarosz.chess.correspondence

import com.yarosz.chess.relay.LiveConnector
import com.yarosz.chess.relay.LiveListener
import com.yarosz.chess.relay.LiveFrame
import com.yarosz.chess.relay.LiveSocket
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the board needs to know of the live socket: which Game it serves, whether it is open, and whether the Game is Live. */
data class LiveView(val gameId: String? = null, val open: Boolean = false, val live: Boolean = false)

/**
 * The phone's one live socket (G3, W7, decision log "v3 PR 3"): it drives a [LiveConnection] for the
 * Game whose board is on screen, over [connector], on [scope], with [clock] for time.
 *
 * - [watch] when a Game's board shows; one Game at a time, so watching another closes the first.
 * - [unwatch] when it leaves: at once on pause, after [GRACE_MS] when another screen covers it (its
 *   Menu, say), so a quick return keeps the socket; showing the same Game again cancels the close.
 * - [pause] on any pause, from whichever screen is on top: the socket closes at once.
 * - Only a started Game that isn't Stopped gets a socket (one that is over too, so a rematch offer
 *   arrives); [reconcile], after the Games change, starts or stops it.
 * - A pushed entry, and every open, reads the Game through [Correspondence.sync]; a pushed rematch
 *   entry reads every Game ([Correspondence.syncAll]), so a rematch that started shows here at once.
 *   Nothing from the socket is applied directly. Reads of one Game never overlap: one asked for
 *   during another runs once after it.
 *
 * [changed] is called, on any thread, when [view] changes or a read ends. With [tickMs] null nothing
 * runs on a timer and a test calls [tick] itself.
 */
class LiveOwner(
    private val correspondence: Correspondence,
    private val connector: LiveConnector,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val jitter: () -> Double = { Random.nextDouble() },
    private val tickMs: Long? = TICK_MS,
    private val changed: () -> Unit = {},
) {
    private val lock = Any()
    private var gameId: String? = null
    private var connection: LiveConnection? = null
    private var socket: LiveSocket? = null

    /** Bumped for every socket; a callback from an older one is ignored. */
    private var generation = 0
    private var closeAt: Long? = null
    private var ticker: Job? = null

    /** The Games being read: true when another read was asked for meanwhile. */
    private val reading = HashMap<String, Boolean>()

    val view: LiveView
        get() = synchronized(lock) { LiveView(gameId, connection?.open == true, connection?.live == true) }

    /** The connection's state, for tests. */
    val state: LiveState? get() = synchronized(lock) { connection?.state }

    fun watch(gameId: String) = act {
        if (this.gameId != gameId) end()
        this.gameId = gameId
        closeAt = null
        // A fresh show restarts a connection that stopped, but not one the Game itself refused.
        if (connection?.state == LiveState.Stopped(LiveStop.LEFT) || connection?.state == LiveState.Stopped(LiveStop.REPLACED)) connection = null
        reconcileLocked()
    }

    /** [gameId]'s board left the screen: [now] on pause, else after [GRACE_MS]. */
    fun unwatch(gameId: String, now: Boolean) = act {
        if (this.gameId != gameId) return@act
        if (now) end() else if (closeAt == null) closeAt = clock() + GRACE_MS
    }

    /** The Tool paused: whatever Game is watched loses its socket now, under another screen or not. */
    fun pause() = act { end() }

    /** The Games changed: a watched Game that is Stopped or gone loses its socket; one that started gets one. */
    fun reconcile() = act { reconcileLocked() }

    /** Time passed: the grace before a close, then the connection's own timers. */
    fun tick() = act {
        val now = clock()
        val due = closeAt
        if (due != null && now >= due) end() else connection?.let { step(it.tick(now, jitter())) }
    }

    // ---- Inside the lock ------------------------------------------------------------------------

    /** What to do once the lock is released: socket calls and reads, which may call back at once. */
    private val effects = ArrayList<() -> Unit>()

    private fun act(block: () -> Unit) {
        val before: LiveView
        val todo: List<() -> Unit>
        synchronized(lock) {
            before = LiveView(gameId, connection?.open == true, connection?.live == true)
            block()
            timers()
            todo = effects.toList()
            effects.clear()
        }
        for (effect in todo) effect()
        if (view != before) changed()
    }

    private fun reconcileLocked() {
        val id = gameId ?: return
        val game = correspondence.game(id)
        val linkable = game != null && game.halt == null && game.startedAt != null
        val current = connection
        when {
            !linkable && current != null && !current.stopped -> step(current.stop())
            linkable && current == null -> step(LiveConnection.start(clock()))
        }
    }

    /**
     * The ticker runs only while there is something to time: a connection that isn't Stopped, or a
     * close after the grace. A Stopped one wakes nothing; watching again starts it.
     */
    private fun timers() {
        val needed = closeAt != null || connection?.stopped == false
        if (!needed) {
            ticker?.cancel()
            ticker = null
        } else if (ticker == null && tickMs != null) {
            ticker = scope.launch {
                while (isActive) {
                    delay(tickMs)
                    tick()
                }
            }
        }
    }

    /** Whether the timer runs, for tests. */
    internal val ticking: Boolean get() = synchronized(lock) { ticker != null }

    /** Stops the connection, forgets the Game and its timers. */
    private fun end() {
        connection?.let { step(it.stop()) }
        connection = null
        gameId = null
        closeAt = null
    }

    private fun step(next: LiveStep) {
        connection = next.connection
        val id = gameId ?: return
        for (action in next.commands) when (action) {
            LiveCommand.CONNECT -> connect(id)
            LiveCommand.PING -> socket?.let { s -> effects += { s.send(LiveFrame.PING) } }
            LiveCommand.CLOSE -> {
                generation++
                socket?.let { s -> effects += { s.close() } }
                socket = null
            }
            LiveCommand.SYNC -> effects += { read(id) { correspondence.sync(id) } }
            LiveCommand.SYNC_ALL -> effects += { read(ALL) { correspondence.syncAll() } }
        }
    }

    private fun connect(id: String) {
        val secret = correspondence.game(id)?.seat?.secret ?: return
        val mine = ++generation
        socket = null
        effects += {
            val opened = connector.open(id, secret, listener(mine))
            var stale = false
            synchronized(lock) {
                val state = connection?.state
                if (mine == generation && (state is LiveState.Connecting || state is LiveState.Open)) socket = opened else stale = true
            }
            if (stale) opened.close()
        }
    }

    private fun listener(mine: Int) = object : LiveListener {
        fun current(block: (LiveConnection) -> LiveStep?) = act {
            if (mine != generation) return@act
            val s = connection ?: return@act
            block(s)?.let(::step)
        }

        fun ended(block: (LiveConnection) -> LiveStep) = current { s ->
            generation++
            socket = null
            block(s)
        }

        override fun onOpen() = current { it.opened(clock()) }

        override fun onMessage(text: String) = current { it.heard(clock(), LiveFrame.decode(text)) }

        override fun onClosed(code: Int) = ended { it.closed(clock(), code, jitter()) }

        override fun onFailure(status: Int?) = ended {
            if (status == null) it.closed(clock(), null, jitter()) else it.refused(clock(), status, jitter())
        }
    }

    /** [body] reads [id] (or [ALL] Games), never two at once: one asked for during another runs after it. */
    private fun read(id: String, body: suspend () -> Unit) {
        synchronized(reading) {
            if (id in reading) {
                reading[id] = true
                return
            }
            reading[id] = false
        }
        scope.launch {
            do {
                try {
                    body()
                } catch (e: CancellationException) {
                    synchronized(reading) { reading.remove(id) }
                    throw e
                } catch (e: Exception) {
                    // A failed read changes nothing; the next push, open or poll reads again.
                }
                changed()
                val again = synchronized(reading) {
                    if (reading[id] == true) true.also { reading[id] = false } else false.also { reading.remove(id) }
                }
            } while (again)
        }
    }

    companion object {
        /** How often the timers are looked at: the ping and the watchdog are a second apart at worst. */
        const val TICK_MS = 1_000L

        /** A board covered by another screen keeps its socket this long (the Relay's presence timeout). */
        const val GRACE_MS = 10_000L

        /** The key of a read of every Game in [reading]: no Game id is this. */
        private const val ALL = "*"
    }
}
