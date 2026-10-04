package com.yarosz.chess.correspondence

import com.yarosz.chess.relay.LiveEntry
import com.yarosz.chess.relay.LiveFrame
import com.yarosz.chess.relay.LivePresence

/** What a [LiveConnection] asks of whoever drives it. */
enum class LiveCommand {
    /** Open a new socket. */
    CONNECT,

    /** Send [LiveFrame.PING] on the open socket. */
    PING,

    /** Close the socket, open or opening, and forget it. */
    CLOSE,

    /**
     * Read the Game (`Correspondence.sync`): on every open, since a push may have been missed while
     * the socket was down, after a pushed entry, and once more when the Relay refuses the socket, so
     * the Game's own state says why (Game deleted, Seat lost, Update Chess).
     */
    SYNC,

    /**
     * Read every Game (`Correspondence.syncAll`), after a pushed rematch entry: an accepted offer of
     * ours starts its new Game, which only a read of that Game shows, and a declined one cancels it.
     */
    SYNC_ALL,
}

/** Why a [LiveConnection] stopped for good. */
enum class LiveStop {
    /** The board left the screen (or the Game can no longer change). */
    LEFT,

    /** Another socket of this Seat took its place (close code 4001). */
    REPLACED,

    /** The Relay refused the Game: deleted (close code 4004), or the upgrade answered 400, 401, 404 or 409. */
    REFUSED,
}

/** Where a [LiveConnection] is. */
sealed interface LiveState {
    /** A socket has been opening since [since]; [failures] in a row came before it. */
    data class Connecting(val failures: Int, val since: Long) : LiveState

    /**
     * The socket is open: the latest [presence], when the Relay was last heard from, and when the next
     * ping goes. [failures] carries over from before the open until the Relay's first presence, so a
     * Relay that accepts and drops at once still backs off further each time.
     */
    data class Open(val presence: LivePresence?, val heardAt: Long, val pingAt: Long, val failures: Int = 0) : LiveState

    /** No socket after [failures] in a row; the next one opens at [until]. */
    data class Backoff(val failures: Int, val until: Long) : LiveState

    data class Stopped(val why: LiveStop) : LiveState
}

/** A step of a [LiveConnection]: the connection after it, and what to do now. */
data class LiveStep(val connection: LiveConnection, val commands: List<LiveCommand> = emptyList())

/**
 * One Game's live socket as a pure state machine (G3, W7): no clock, no thread, no socket. Its
 * driver tells it what happened, with the time ([now]) and, for a back-off, a [jitter] in 0..1, and
 * does the [LiveStep.commands] it answers. `LiveOwner` drives it on the phone; tests drive it by hand.
 *
 * - Open: a ping every [PING_MS]; every message counts as hearing from the Relay, which answers each
 *   ping. [SILENCE_MS] without a message drops the socket: the network may have gone without a close.
 * - Opening: a socket that hasn't opened [CONNECT_TIMEOUT_MS] after it was asked for is abandoned,
 *   since OkHttp bounds the TCP connect and the handshake reads but no whole upgrade.
 * - Down: a new socket after a back-off of 1 s, then doubling to [MAX_BACKOFF_MS], each between half
 *   its step and its step (jitter), at least 1 s. The count resets on the Relay's first presence on
 *   an open socket, not on the open itself.
 * - Close code 4001 stops it: another socket of this Seat replaced it, and two would replace each
 *   other forever. Close code 4004, or an upgrade answered 400, 401, 404 or 409, stops it after one
 *   [LiveCommand.SYNC], whose refusal stops the Game itself as the HTTPS reads would.
 * - Any other close or failure (offline, a timeout, a 5xx, 429) backs off and tries again.
 */
data class LiveConnection(val state: LiveState) {
    /** Whether the socket is open: the board needs no poll (Y12) while it is. */
    val open: Boolean get() = state is LiveState.Open

    /** Live (G3): the socket is open and the Relay last reported both Seats here. */
    val live: Boolean get() = (state as? LiveState.Open)?.presence?.live == true

    val stopped: Boolean get() = state is LiveState.Stopped

    /** The socket opened: read the Game, in case an entry came while it was down, and ping from now on. */
    fun opened(now: Long): LiveStep = when (state) {
        is LiveState.Connecting ->
            LiveStep(LiveConnection(LiveState.Open(null, now, now + PING_MS, state.failures)), listOf(LiveCommand.SYNC))
        else -> LiveStep(this)
    }

    /** A message from the Relay (null: one this version can't read, still a sign of life). */
    fun heard(now: Long, message: LiveFrame?): LiveStep {
        val open = state as? LiveState.Open ?: return LiveStep(this)
        val presence = message as? LivePresence
        // The Relay's first presence shows the socket really works: the back-off starts over.
        val next = open.copy(heardAt = now, presence = presence ?: open.presence, failures = if (presence != null) 0 else open.failures)
        val read = when {
            message !is LiveEntry -> emptyList()
            message.entry.entryKind?.isRematch == true -> listOf(LiveCommand.SYNC_ALL)
            else -> listOf(LiveCommand.SYNC)
        }
        return LiveStep(LiveConnection(next), read)
    }

    /** The Relay closed the socket with [code], or it broke with no HTTP answer ([code] null). */
    fun closed(now: Long, code: Int?, jitter: Double): LiveStep = when {
        stopped -> LiveStep(this)
        code == REPLACED -> LiveStep(LiveConnection(LiveState.Stopped(LiveStop.REPLACED)))
        code == DELETED -> LiveStep(LiveConnection(LiveState.Stopped(LiveStop.REFUSED)), listOf(LiveCommand.SYNC))
        else -> backOff(now, jitter)
    }

    /** The Relay answered the upgrade with HTTP [status] instead of a socket. */
    fun refused(now: Long, status: Int, jitter: Double): LiveStep = when {
        stopped -> LiveStep(this)
        status in REFUSALS -> LiveStep(LiveConnection(LiveState.Stopped(LiveStop.REFUSED)), listOf(LiveCommand.SYNC))
        else -> backOff(now, jitter)
    }

    /** Time passed: a ping due, a silent socket dropped, a stuck opening abandoned, or a back-off over. */
    fun tick(now: Long, jitter: Double): LiveStep = when (val s = state) {
        is LiveState.Open -> when {
            now - s.heardAt >= SILENCE_MS -> backOff(now, jitter).closing()
            now >= s.pingAt -> LiveStep(LiveConnection(s.copy(pingAt = now + PING_MS)), listOf(LiveCommand.PING))
            else -> LiveStep(this)
        }
        is LiveState.Connecting ->
            if (now - s.since >= CONNECT_TIMEOUT_MS) backOff(now, jitter).closing() else LiveStep(this)
        is LiveState.Backoff ->
            if (now >= s.until) LiveStep(LiveConnection(LiveState.Connecting(s.failures, now)), listOf(LiveCommand.CONNECT)) else LiveStep(this)
        is LiveState.Stopped -> LiveStep(this)
    }

    /** The same step, closing the socket first. */
    private fun LiveStep.closing() = copy(commands = listOf(LiveCommand.CLOSE) + commands)

    /** The board left: close whatever socket there is. */
    fun stop(): LiveStep = when (state) {
        is LiveState.Connecting, is LiveState.Open -> LiveStep(LiveConnection(LiveState.Stopped(LiveStop.LEFT)), listOf(LiveCommand.CLOSE))
        is LiveState.Backoff -> LiveStep(LiveConnection(LiveState.Stopped(LiveStop.LEFT)))
        is LiveState.Stopped -> LiveStep(this)
    }

    private fun backOff(now: Long, jitter: Double): LiveStep {
        val failures = when (val s = state) {
            is LiveState.Connecting -> s.failures + 1
            is LiveState.Open -> s.failures + 1
            is LiveState.Backoff -> s.failures
            is LiveState.Stopped -> 1
        }
        return LiveStep(LiveConnection(LiveState.Backoff(failures, now + backoff(failures, jitter))))
    }

    companion object {
        /** W7: a ping every 5 seconds while the board shows. */
        const val PING_MS = 5_000L

        /** No message for this long drops the socket: two pings unanswered, and some slack. */
        const val SILENCE_MS = 12_000L

        /** A socket not open this long after it was asked for is abandoned: a hung TLS or upgrade. */
        const val CONNECT_TIMEOUT_MS = 15_000L

        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L

        /** Close codes from relay/src/game.ts. */
        const val REPLACED = 4001
        const val DELETED = 4004

        /** HTTP answers to the upgrade that no retry changes: bad_request or unsupported_version, bad_seat_secret, game_not_found, version_mismatch or game_not_started. */
        val REFUSALS = setOf(400, 401, 404, 409)

        /** A new connection: its first socket opens [now]. */
        fun start(now: Long): LiveStep = LiveStep(LiveConnection(LiveState.Connecting(0, now)), listOf(LiveCommand.CONNECT))

        /** The wait after [failures] in a row: 1 s, 2 s, 4 s ... up to 30 s, each from half to all of its step, at least 1 s. */
        fun backoff(failures: Int, jitter: Double): Long {
            val step = (MIN_BACKOFF_MS shl (failures - 1).coerceIn(0, 5)).coerceAtMost(MAX_BACKOFF_MS)
            return (step / 2 + (step / 2 * jitter.coerceIn(0.0, 1.0)).toLong()).coerceAtLeast(MIN_BACKOFF_MS)
        }
    }
}
