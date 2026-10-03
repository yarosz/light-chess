package com.yarosz.chess.correspondence

import com.yarosz.chess.relay.LiveEntry
import com.yarosz.chess.relay.LiveError
import com.yarosz.chess.relay.LivePresence
import com.yarosz.chess.relay.LogEntry
import com.yarosz.chess.rules.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The live socket's pure state machine (G3, W7, decision log U5) under virtual time: the test is its clock. */
class LiveConnectionTest {

    private fun open(at: Long = 0): LiveConnection = LiveConnection.start(0).connection.opened(at).connection

    private val here = LivePresence(live = true, opponent = "here", serverTime = 0)
    private val gone = LivePresence(live = false, opponent = "gone", serverTime = 0)

    @Test
    fun `a connection opens a socket, and reads the Game on every open`() {
        val start = LiveConnection.start(0)
        assertEquals(listOf(LiveCommand.CONNECT), start.commands)
        assertEquals(LiveState.Connecting(0, 0), start.connection.state)
        val opened = start.connection.opened(100)
        assertEquals(listOf(LiveCommand.SYNC), opened.commands, "a push missed while it was down is read now")
        assertTrue(opened.connection.open)
        assertFalse(opened.connection.live, "not Live until the Relay reports both Seats here")
    }

    @Test
    fun `it pings every 5 seconds while the Relay answers`() {
        var connection = open()
        var pings = 0
        // A minute of one-second ticks; the Relay answers each ping at once.
        for (now in 1_000L..60_000L step 1_000L) {
            val step = connection.tick(now, 0.5)
            connection = step.connection
            if (LiveCommand.PING in step.commands) {
                pings++
                connection = connection.heard(now, here).connection
            }
            assertTrue(connection.open, "never dropped at $now")
        }
        assertEquals(12, pings)
        assertTrue(connection.live)
    }

    @Test
    fun `12 seconds without a message drops the socket and backs off`() {
        var connection = open()
        assertEquals(listOf(LiveCommand.PING), connection.tick(5_000, 0.0).commands)
        connection = connection.tick(5_000, 0.0).connection
        assertEquals(listOf(LiveCommand.PING), connection.tick(11_999, 0.0).commands, "still pinging, not dropped")
        val drop = connection.tick(12_000, 0.0)
        assertEquals(listOf(LiveCommand.CLOSE), drop.commands)
        assertEquals(LiveState.Backoff(1, 13_000), drop.connection.state)
        assertFalse(drop.connection.live)
        // Any message counts, one this version can't read too.
        val heard = open().heard(11_000, null).connection
        assertTrue(heard.tick(22_999, 0.0).connection.open)
    }

    @Test
    fun `back-off grows from 1 to 30 seconds, with jitter, and resets on open`() {
        assertEquals(1_000, LiveConnection.backoff(1, 0.0))
        assertEquals(1_000, LiveConnection.backoff(1, 1.0))
        assertEquals(1_000, LiveConnection.backoff(2, 0.0))
        assertEquals(2_000, LiveConnection.backoff(2, 1.0))
        assertEquals(15_000, LiveConnection.backoff(6, 0.0))
        assertEquals(30_000, LiveConnection.backoff(6, 1.0))
        assertEquals(30_000, LiveConnection.backoff(60, 1.0), "capped")
        assertEquals(15_000, LiveConnection.backoff(60, 0.0))

        var now = 0L
        var connection = LiveConnection.start(0).connection
        val waits = mutableListOf<Long>()
        repeat(7) {
            connection = connection.closed(now, null, 1.0).connection
            val backoff = assertIs<LiveState.Backoff>(connection.state)
            waits += backoff.until - now
            assertEquals(emptyList(), connection.tick(backoff.until - 1, 1.0).commands)
            now = backoff.until
            val again = connection.tick(now, 1.0)
            assertEquals(listOf(LiveCommand.CONNECT), again.commands)
            connection = again.connection
        }
        assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000), waits)
        connection = connection.opened(now).connection.heard(now, here).connection
        connection = connection.closed(now, 1006, 1.0).connection
        assertEquals(LiveState.Backoff(1, now + 1_000), connection.state, "the Relay's first presence resets the count")
    }

    @Test
    fun `a Relay that accepts and drops at once still backs off further each time`() {
        var now = 0L
        var connection = LiveConnection.start(0).connection
        val waits = mutableListOf<Long>()
        repeat(6) {
            // The socket opens, the open's read goes out, and the Relay closes it (1011) before any presence.
            val opened = connection.opened(now)
            assertEquals(listOf(LiveCommand.SYNC), opened.commands)
            connection = opened.connection.closed(now, 1011, 1.0).connection
            val backoff = assertIs<LiveState.Backoff>(connection.state)
            waits += backoff.until - now
            now = backoff.until
            connection = connection.tick(now, 1.0).connection
        }
        assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 30_000), waits, "not a reconnect and a read every second")
        // A socket that dies silently before any presence counts too.
        val quiet = LiveConnection.SILENCE_MS
        val silent = LiveConnection(LiveState.Connecting(3, 0)).opened(0).connection.tick(quiet, 0.0)
        assertEquals(LiveState.Backoff(4, quiet + LiveConnection.backoff(4, 0.0)), silent.connection.state)
    }

    @Test
    fun `a socket that hangs while opening is abandoned after 12 seconds`() {
        val connecting = LiveConnection.start(1_000).connection
        assertEquals(emptyList(), connecting.tick(1_000 + LiveConnection.CONNECT_TIMEOUT_MS - 1, 0.0).commands, "still opening")
        val abandoned = connecting.tick(1_000 + LiveConnection.CONNECT_TIMEOUT_MS, 0.0)
        assertEquals(listOf(LiveCommand.CLOSE), abandoned.commands, "the stuck call is cancelled")
        val backoff = assertIs<LiveState.Backoff>(abandoned.connection.state)
        assertEquals(1, backoff.failures)
        // The next attempt opens after the back-off, with its own deadline.
        val again = abandoned.connection.tick(backoff.until, 0.0)
        assertEquals(listOf(LiveCommand.CONNECT), again.commands)
        assertEquals(LiveState.Connecting(1, backoff.until), again.connection.state)
        val twice = again.connection.tick(backoff.until + LiveConnection.CONNECT_TIMEOUT_MS, 0.0)
        assertEquals(listOf(LiveCommand.CLOSE), twice.commands)
        assertEquals(2, assertIs<LiveState.Backoff>(twice.connection.state).failures)
        // One that opens in time is not abandoned.
        assertTrue(connecting.opened(5_000).connection.tick(1_000 + LiveConnection.CONNECT_TIMEOUT_MS, 0.0).connection.open)
    }

    @Test
    fun `4001 stops it, since another socket of the Seat replaced it`() {
        val step = open().closed(1_000, LiveConnection.REPLACED, 0.0)
        assertEquals(LiveState.Stopped(LiveStop.REPLACED), step.connection.state)
        assertEquals(emptyList(), step.commands)
        assertEquals(emptyList(), step.connection.tick(1_000_000, 0.0).commands, "never reconnects")
    }

    @Test
    fun `4004 and an HTTP refusal stop it after one read, so the Game's own state says why`() {
        val deleted = open().closed(1_000, LiveConnection.DELETED, 0.0)
        assertEquals(LiveState.Stopped(LiveStop.REFUSED), deleted.connection.state)
        assertEquals(listOf(LiveCommand.SYNC), deleted.commands)
        for (status in listOf(400, 401, 404, 409)) {
            val refused = LiveConnection.start(0).connection.refused(0, status, 0.0)
            assertEquals(LiveState.Stopped(LiveStop.REFUSED), refused.connection.state, "$status")
            assertEquals(listOf(LiveCommand.SYNC), refused.commands)
            assertEquals(emptyList(), refused.connection.tick(1_000_000, 0.0).commands)
        }
        // A restart, an outage, the rate limit: try again later.
        for (status in listOf(426, 429, 500, 503)) {
            assertIs<LiveState.Backoff>(LiveConnection.start(0).connection.refused(0, status, 0.0).connection.state, "$status")
        }
        for (code in listOf(1000, 1001, 1006, 1011)) assertIs<LiveState.Backoff>(open().closed(0, code, 0.0).connection.state, "$code")
    }

    @Test
    fun `a pushed entry is read, not applied, and presence says whether the Game is Live`() {
        val entry = LogEntry("1.0", "g", 1, 1, Side.WHITE, "move", "e2e4", null, "h", null, 0)
        assertEquals(listOf(LiveCommand.SYNC), open().heard(1, LiveEntry(entry)).commands)
        for (kind in listOf("rematchOffer", "rematchAccept", "rematchDecline")) {
            assertEquals(listOf(LiveCommand.SYNC_ALL), open().heard(1, LiveEntry(entry.copy(kind = kind))).commands, "$kind: the new Game too")
        }
        assertEquals(emptyList(), open().heard(1, LiveError("bad_request")).commands)
        val live = open().heard(1, here).connection
        assertTrue(live.live)
        assertTrue(live.heard(2, LiveEntry(entry)).connection.live, "an entry keeps the last presence")
        assertFalse(live.heard(3, gone).connection.live, "the Relay reports the opponent gone after 10 s (G3)")
        assertFalse(live.tick(20_000, 0.0).connection.live, "a dropped socket is not Live")
    }

    @Test
    fun `stop closes whatever socket there is, and a stopped connection ignores everything`() {
        assertEquals(listOf(LiveCommand.CLOSE), open().stop().commands)
        assertEquals(listOf(LiveCommand.CLOSE), LiveConnection.start(0).connection.stop().commands)
        val backoff = open().closed(0, 1006, 0.0).connection
        assertEquals(emptyList(), backoff.stop().commands)
        val stopped = open().stop().connection
        assertEquals(LiveState.Stopped(LiveStop.LEFT), stopped.state)
        assertEquals(stopped, stopped.opened(1).connection)
        assertEquals(stopped, stopped.heard(1, here).connection)
        assertEquals(stopped, stopped.closed(1, 1006, 0.0).connection)
        assertEquals(stopped, stopped.refused(1, 404, 0.0).connection)
        assertEquals(emptyList(), stopped.refused(1, 404, 0.0).commands)
    }
}
