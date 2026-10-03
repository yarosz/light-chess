package com.yarosz.chess.correspondence

import com.yarosz.chess.relay.FakeRelay
import com.yarosz.chess.relay.LiveListener
import com.yarosz.chess.rules.Side
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * Two phones with live sockets on the fake Relay (G3, W7, decision log U1-U6), under virtual time:
 * the Relay's clock is both phones' clock, and the test ticks each phone's LiveOwner itself.
 */
class LiveOwnerTest {
    private val relay = FakeRelay()
    private val a = Phone(relay, 1, "a")
    private val b = Phone(relay, 2, "b")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun liveOwner(phone: Phone) = LiveOwner(phone.c, relay.live(), scope, clock = { relay.now }, jitter = { 0.0 }, tickMs = null)

    private val liveA by lazy { liveOwner(a) }
    private val liveB by lazy { liveOwner(b) }

    @AfterTest
    fun cleanUp() {
        a.clean()
        b.clean()
    }

    /** A Game A created as White and B joined; both phones know it has started. */
    private fun started(): String = runBlocking {
        val invite = assertNotNull(done(a.c.createInvite(Side.WHITE, 3)))
        done(b.c.redeemInvite(assertNotNull(invite.invite?.code)))
        a.c.syncAll()
        invite.gameId
    }

    /** [ms] of virtual time, ticking both owners each second. */
    private fun pass(ms: Long, vararg owners: LiveOwner = arrayOf(liveA, liveB)) {
        val end = relay.now + ms
        while (relay.now < end) {
            relay.now = minOf(end, relay.now + 1_000)
            for (owner in owners) owner.tick()
        }
    }

    @Test
    fun `A moves and B's board has the Move at once, with no poll`() = runBlocking<Unit> {
        val id = started()
        liveA.watch(id)
        liveB.watch(id)
        assertEquals(2, relay.liveSockets(id))
        assertTrue(liveA.view.live && liveB.view.live, "both Seats here: Live (G3)")
        val reads = relay.requests.size
        done(a.c.play(id, a.move(id, "e2e4")))
        assertEquals(1, b.log(id).game.ply, "the pushed entry made B read the Game")
        val after = relay.requests.drop(reads)
        assertTrue(after.none { it.startsWith("POST /v1/sync") }, "no batched poll: $after")
        // The push reaches both sockets: B reads the Move, A reads its own once more.
        assertEquals(2, after.count { it.startsWith("GET /v1/games/$id/events") }, "one read per phone: $after")
        done(b.c.play(id, b.move(id, "e7e5")))
        assertEquals(2, a.log(id).game.ply)
    }

    @Test
    fun `Live once both are here, gone 10 seconds after the opponent goes quiet`() = runBlocking<Unit> {
        val id = started()
        liveA.watch(id)
        assertTrue(liveA.view.open)
        assertFalse(liveA.view.live, "alone on the Game")
        liveB.watch(id)
        assertTrue(liveA.view.live)
        pass(30_000)
        assertTrue(liveA.view.live && liveB.view.live, "the pings keep both here")
        // B's network dies without a close: the Relay stops hearing B, and tells A at A's next ping after 10 s.
        relay.silence(id, Side.BLACK)
        pass(9_000, liveA)
        assertTrue(liveA.view.live, "under 10 s, B still counts as here")
        pass(6_000, liveA)
        assertFalse(liveA.view.live, "B gone for 10 s or more")
        assertTrue(liveA.view.open)
    }

    @Test
    fun `a silent socket is dropped after 12 seconds, and the reconnect reads what it missed`() = runBlocking<Unit> {
        val id = started()
        liveB.watch(id)
        relay.silence(id, Side.BLACK)
        pass(11_000, liveB)
        assertTrue(liveB.view.open)
        pass(1_000, liveB)
        assertFalse(liveB.view.open, "12 s without a message")
        assertIs<LiveState.Backoff>(liveB.state)
        // While B is down, A moves: no push reaches B.
        done(a.c.play(id, a.move(id, "d2d4")))
        assertEquals(0, b.log(id).game.ply)
        pass(1_000, liveB)
        assertTrue(liveB.view.open, "back after 1 s")
        assertEquals(1, b.log(id).game.ply, "the read on open caught the Move")
    }

    @Test
    fun `a broken socket backs off and comes back`() = runBlocking<Unit> {
        val id = started()
        liveB.watch(id)
        relay.breakLive(id, Side.BLACK)
        assertEquals(LiveState.Backoff(1, relay.now + 1_000), liveB.state)
        pass(1_000, liveB)
        assertTrue(liveB.view.open)
        assertEquals(1, relay.liveSockets(id))
    }

    @Test
    fun `on pause the socket closes at once, and the other phone loses Live`() = runBlocking<Unit> {
        val id = started()
        liveA.watch(id)
        liveB.watch(id)
        liveB.unwatch(id, now = true)
        assertEquals(1, relay.liveSockets(id))
        assertFalse(liveB.view.open)
        assertFalse(liveA.view.live, "the Relay tells A when B's socket closes")
    }

    @Test
    fun `under another screen the socket stays 10 seconds, and a return in time keeps it`() = runBlocking<Unit> {
        val id = started()
        liveA.watch(id)
        liveB.watch(id)
        val opens = relay.liveOpens
        liveB.unwatch(id, now = false)
        pass(9_000)
        assertTrue(liveB.view.open && liveA.view.live, "the board's Menu doesn't drop Live")
        liveB.watch(id)
        pass(30_000)
        assertTrue(liveB.view.open, "the return cancelled the close")
        assertEquals(opens, relay.liveOpens, "the same socket throughout")
        liveB.unwatch(id, now = false)
        pass(10_000)
        assertFalse(liveB.view.open, "closed 10 s after the board left")
        assertEquals(1, relay.liveSockets(id))
        assertFalse(liveA.view.live)
    }

    @Test
    fun `a deleted Game closes with 4004, and one read stops it as Game deleted`() = runBlocking<Unit> {
        val id = started()
        liveB.watch(id)
        relay.delete(id)
        assertEquals(LiveState.Stopped(LiveStop.REFUSED), liveB.state)
        assertEquals(HaltReason.GONE, b.game(id).halt?.reason)
        pass(60_000, liveB)
        assertEquals(LiveState.Stopped(LiveStop.REFUSED), liveB.state, "never reconnects")
    }

    @Test
    fun `an upgrade the Relay refuses stops after one read`() = runBlocking<Unit> {
        val id = started()
        relay.delete(id)
        val opens = relay.liveOpens
        liveB.watch(id)
        assertEquals(opens + 1, relay.liveOpens)
        assertEquals(HaltReason.GONE, b.game(id).halt?.reason, "the 404 read stopped the Game")
        pass(60_000, liveB)
        assertEquals(opens + 1, relay.liveOpens, "no retry")
        // A fresh show doesn't reopen a Stopped Game's socket either.
        liveB.unwatch(id, now = true)
        liveB.watch(id)
        assertEquals(opens + 1, relay.liveOpens)
    }

    @Test
    fun `4001 stops a socket another of the Seat replaced`() = runBlocking<Unit> {
        val id = started()
        liveB.watch(id)
        val quiet = object : LiveListener {
            override fun onOpen() {}
            override fun onMessage(text: String) {}
            override fun onClosed(code: Int) {}
            override fun onFailure(status: Int?) {}
        }
        relay.live().open(id, b.game(id).seat.secret, quiet)
        assertEquals(LiveState.Stopped(LiveStop.REPLACED), liveB.state)
        val opens = relay.liveOpens
        pass(60_000, liveB)
        assertEquals(opens, relay.liveOpens, "the two don't replace each other forever")
    }

    @Test
    fun `only a started Game that isn't Stopped gets a socket`() = runBlocking<Unit> {
        val invite = assertNotNull(done(a.c.createInvite(Side.WHITE, 3)))
        liveA.watch(invite.gameId)
        liveA.watch("0".repeat(64))
        assertEquals(0, relay.liveOpens, "no socket for an invite or an unknown Game")
        done(b.c.redeemInvite(assertNotNull(invite.invite?.code)))
        a.c.syncAll()
        liveA.watch(invite.gameId)
        liveA.reconcile()
        assertEquals(1, relay.liveOpens, "the started Game")
        // The other phone resigns; the Game is over, and its socket stays for a rematch offer.
        done(b.c.resign(invite.gameId))
        assertEquals(Stage.OVER, a.game(invite.gameId).stage)
        assertTrue(liveA.view.open)
    }

    @Test
    fun `watching another Game closes the first`() = runBlocking<Unit> {
        val first = started()
        val second = started()
        liveA.watch(first)
        liveA.watch(second)
        assertEquals(0, relay.liveSockets(first))
        assertEquals(1, relay.liveSockets(second))
        assertEquals(second, liveA.view.gameId)
        liveA.unwatch(first, now = true)
        assertTrue(liveA.view.open, "unwatching a Game no longer watched changes nothing")
    }
}
