package com.yarosz.chess.correspondence

import com.yarosz.chess.relay.FakeRelay
import com.yarosz.chess.relay.LogEntry
import com.yarosz.chess.relay.Protocol
import com.yarosz.chess.relay.RelayRequest
import com.yarosz.chess.relay.RelayTransport
import com.yarosz.chess.relay.Weather
import com.yarosz.chess.rules.DrawReason
import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.Result
import com.yarosz.chess.rules.Side
import com.yarosz.chess.rules.WinReason
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The sync engine against the fake Relay: invites, play, draws, timeouts, rematches, and what the network does wrong. */
class CorrespondenceTest {

    private val relay = FakeRelay()
    private val a = Phone(relay, 1, "a")
    private val b = Phone(relay, 2, "b")
    private val offline = Weather(offline = 1.0)

    @AfterTest
    fun cleanUp() {
        a.clean()
        b.clean()
    }

    /** A Game [a] created with [side] and [b] joined; [a] has seen it start. */
    private suspend fun started(side: Side = Side.WHITE, days: Int = 3): String {
        val invite = assertNotNull(done(a.c.createInvite(side, days)))
        done(b.c.redeemInvite(assertNotNull(invite.invite?.code)))
        a.c.syncAll()
        assertEquals(Stage.ACTIVE, a.game(invite.gameId).stage)
        return invite.gameId
    }

    /** [phone] plays [uci] in [id] and it is stored. */
    private suspend fun Phone.plays(id: String, vararg uci: String) {
        for (m in uci) {
            c.syncAll()
            done(c.play(id, move(id, m)))
        }
    }

    private fun same(id: String) {
        val entries = relay.entries(id)
        assertEquals(entries, a.game(id).entries)
        assertEquals(entries, b.game(id).entries)
        assertEquals(a.log(id).game.position.digest, b.log(id).game.position.digest)
        assertEquals(a.log(id).game.result, b.log(id).game.result)
    }

    // ---- Invites ------------------------------------------------------------------------------

    @Test
    fun `an Invite Code starts a Game, and each Seat's secret stays in its own file`() = runBlocking<Unit> {
        val invite = assertNotNull(done(a.c.createInvite(Side.BLACK, 7)))
        assertEquals(Stage.WAITING, invite.stage)
        val code = assertNotNull(invite.invite?.code)
        assertEquals(code.take(4), invite.label)
        assertTrue(invite.waitingOnOpponent)
        val joined = assertNotNull(done(b.c.redeemInvite(code.lowercase().chunked(4).joinToString("-"))))
        assertEquals(Side.WHITE, joined.seat.side)
        assertEquals(Stage.ACTIVE, joined.stage)
        assertTrue(joined.yourMove)
        val report = a.c.syncAll()
        assertEquals(listOf(invite.gameId), report.changed)
        assertEquals(Stage.ACTIVE, a.game(invite.gameId).stage)
        assertNull(a.game(invite.gameId).invite)
        assertEquals(joined.startedAt, a.game(invite.gameId).startedAt)
        val file = File(a.dir, CorrespondenceStore.FILE).readText()
        assertTrue(a.game(invite.gameId).seat.secret in file)
        assertFalse(joined.seat.secret in file)
        assertFalse(a.game(invite.gameId).seat.secret in a.game(invite.gameId).toString())
    }

    @Test
    fun `the store lives beside filesDir in no_backup`() {
        assertEquals(File("/data/user/0/com.yarosz.chess/no_backup"), CorrespondenceStore.noBackupDir(File("/data/user/0/com.yarosz.chess/files")))
    }

    @Test
    fun `a code that can't be one, the phone's own, a used one and an unknown one are refused`() = runBlocking<Unit> {
        refused(b.c.redeemInvite("ABCD-EFGU"), Refusal.BAD_CODE)
        val invite = assertNotNull(done(a.c.createInvite(Side.WHITE)))
        val code = invite.invite!!.code!!
        refused(a.c.redeemInvite(code), Refusal.NOT_ALLOWED)
        done(b.c.redeemInvite(code))
        val c = Phone(relay, 3, "c")
        refused(c.c.redeemInvite(code), Refusal.INVITE_USED)
        refused(c.c.redeemInvite(if (code == "00000000") "00000001" else "00000000"), Refusal.INVITE_NOT_FOUND)
        c.weather = offline
        refused(c.c.redeemInvite("ABCDEFGH"), Refusal.OFFLINE)
        c.clean()
    }

    @Test
    fun `redemptions past the rate limit are refused`() = runBlocking<Unit> {
        repeat(FakeRelay.REDEEM_LIMIT) { refused(b.c.redeemInvite("ABCDEFGH"), Refusal.INVITE_NOT_FOUND) }
        refused(b.c.redeemInvite("ABCDEFGH"), Refusal.RATE_LIMITED)
    }

    @Test
    fun `five Games at once, open invites included (E7, F9)`() = runBlocking<Unit> {
        val ids = (1..Correspondence.MAX_GAMES).map { assertNotNull(done(a.c.createInvite(Side.WHITE))).gameId }
        refused(a.c.createInvite(Side.WHITE), Refusal.CAP_REACHED)
        val other = assertNotNull(done(b.c.createInvite(Side.WHITE)))
        refused(a.c.redeemInvite(other.invite!!.code!!), Refusal.CAP_REACHED)
        done(a.c.cancelInvite(ids.first()))
        done(a.c.redeemInvite(other.invite!!.code!!))
        refused(a.c.createInvite(Side.WHITE), Refusal.CAP_REACHED)
    }

    @Test
    fun `a cancel goes once the Relay confirms, waits offline, and loses to a redeem (G2)`() = runBlocking<Unit> {
        val first = assertNotNull(done(a.c.createInvite(Side.WHITE)))
        assertNull(done(a.c.cancelInvite(first.gameId)))
        assertNull(a.c.game(first.gameId))
        assertFalse(relay.exists(first.gameId))

        val second = assertNotNull(done(a.c.createInvite(Side.WHITE)))
        a.weather = offline
        assertTrue(queued(a.c.cancelInvite(second.gameId)).invite!!.cancelling)
        assertTrue(a.game(second.gameId).takesASlot)
        assertTrue(a.c.syncAll().retry)
        a.weather = Weather()
        assertFalse(a.c.syncAll().retry)
        assertNull(a.c.game(second.gameId))
        assertFalse(relay.exists(second.gameId))

        val third = assertNotNull(done(a.c.createInvite(Side.WHITE)))
        done(b.c.redeemInvite(third.invite!!.code!!))
        val game = assertNotNull(refused(a.c.cancelInvite(third.gameId), Refusal.ALREADY_REDEEMED))
        assertEquals(Stage.ACTIVE, game.stage)
        assertTrue(game.yourMove)
    }

    @Test
    fun `an invite nobody redeems drops after 48 hours`() = runBlocking<Unit> {
        val invite = assertNotNull(done(a.c.createInvite(Side.WHITE)))
        relay.now += FakeRelay.INVITE_TTL
        a.c.syncAll()
        assertNull(a.c.game(invite.gameId))
    }

    // ---- Play ---------------------------------------------------------------------------------

    @Test
    fun `both phones play a Game to checkmate and agree on everything`() = runBlocking<Unit> {
        val id = started()
        a.plays(id, "f2f3")
        b.plays(id, "e7e5")
        a.plays(id, "g2g4")
        b.plays(id, "d8h4")
        a.c.syncAll()
        same(id)
        assertEquals(Result.Win(Side.BLACK, WinReason.CHECKMATE), a.log(id).game.result)
        assertEquals(true, relay.entries(id).last().end)
        assertEquals(Stage.OVER, a.game(id).stage)
        assertEquals(relay.entries(id).last().serverTime, a.game(id).closedAt)
        refused(a.c.play(id, Position.START.legalMoves.first()), Refusal.NOT_ALLOWED)
    }

    @Test
    fun `the report says whose move it is and whether to keep polling`() = runBlocking<Unit> {
        val id = started()
        var report = b.c.syncAll()
        assertEquals(0, report.yourMove)
        assertTrue(report.waitingOnOpponent)
        report = a.c.syncAll()
        assertEquals(1, report.yourMove)
        assertFalse(report.waitingOnOpponent)
        a.plays(id, "e2e4")
        b.plays(id, "e7e5")
        a.c.resign(id)
        report = a.c.syncAll()
        assertFalse(report.waitingOnOpponent, "a Game that is over waits on nothing")
        assertEquals(0, report.yourMove)
    }

    @Test
    fun `a lost response is retried, and the Relay's repeat confirms it once (C8)`() = runBlocking<Unit> {
        val id = started()
        a.weather = Weather(lostResponse = 1.0)
        assertNotNull(queued(a.c.play(id, a.move(id, "e2e4"))).pending)
        assertEquals(1, relay.entries(id).size, "the Relay stored it")
        a.weather = Weather()
        assertFalse(a.c.syncAll().retry)
        assertNull(a.game(id).pending)
        b.c.syncAll()
        same(id)
    }

    @Test
    fun `a duplicated request stores one entry`() = runBlocking<Unit> {
        val id = started()
        a.weather = Weather(duplicate = 1.0)
        done(a.c.play(id, a.move(id, "e2e4")))
        b.weather = Weather(duplicate = 1.0)
        b.plays(id, "c7c5")
        assertEquals(2, relay.entries(id).size)
        a.c.syncAll()
        same(id)
    }

    @Test
    fun `offline, the entry is saved first and sent after a restart of the process`() = runBlocking<Unit> {
        val id = started()
        a.weather = offline
        queued(a.c.play(id, a.move(id, "d2d4")))
        assertTrue(relay.entries(id).isEmpty())
        a.restart()
        assertNotNull(a.game(id).pending, "the pending entry survived")
        assertTrue(a.c.syncAll().retry)
        a.weather = Weather()
        assertFalse(a.c.syncAll().retry)
        assertEquals(1, relay.entries(id).size)
        b.c.syncAll()
        same(id)
    }

    @Test
    fun `a Relay restart answers 503, and the entry goes later`() = runBlocking<Unit> {
        val id = started()
        a.weather = Weather(restart = 1.0)
        queued(a.c.play(id, a.move(id, "e2e4")))
        a.weather = Weather()
        a.c.syncAll()
        assertEquals(1, relay.entries(id).size)
    }

    // ---- Conflicts (C8) -----------------------------------------------------------------------

    @Test
    fun `a pending Move is rolled back when the other Seat offered a draw meanwhile`() = runBlocking<Unit> {
        val id = started()
        a.plays(id, "e2e4")
        b.plays(id, "e7e5")
        a.c.syncAll()
        done(b.c.offerDraw(id))
        val game = assertNotNull(refused(a.c.play(id, a.move(id, "g1f3")), Refusal.ROLLED_BACK))
        assertEquals("g1f3", game.rolledBack?.uci)
        assertEquals(Side.BLACK, game.log?.game?.openDrawOffer)
        assertTrue(game.yourMove)
        done(a.c.acceptDraw(id))
        b.c.syncAll()
        same(id)
        assertEquals(Result.Draw(DrawReason.AGREEMENT), b.log(id).game.result)
    }

    @Test
    fun `a pending draw offer is rolled back when the other Seat moved first`() = runBlocking<Unit> {
        val id = started()
        a.plays(id, "e2e4")
        a.weather = offline
        queued(a.c.offerDraw(id))
        b.plays(id, "e7e5")
        a.weather = Weather()
        a.c.syncAll()
        val game = a.game(id)
        assertNull(game.pending)
        assertEquals("drawOffer", game.rolledBack?.kind)
        assertNull(game.log?.game?.openDrawOffer)
        same(id)
    }

    @Test
    fun `a pending resignation that still makes sense is drafted again at the new place`() = runBlocking<Unit> {
        val id = started()
        a.plays(id, "e2e4")
        b.plays(id, "e7e5")
        a.c.syncAll()
        done(b.c.offerDraw(id))
        done(a.c.resign(id))
        b.c.syncAll()
        same(id)
        assertEquals(listOf("move", "move", "drawOffer", "resign"), relay.entries(id).map { it.kind })
        assertEquals(Result.Win(Side.BLACK, WinReason.RESIGNATION), a.log(id).game.result)
    }

    @Test
    fun `a pending Move after the other Seat resigned is rolled back`() = runBlocking<Unit> {
        val id = started()
        a.plays(id, "e2e4")
        b.plays(id, "e7e5")
        a.c.syncAll()
        done(b.c.resign(id))
        refused(a.c.play(id, a.move(id, "g1f3")), Refusal.ROLLED_BACK)
        assertEquals(Result.Win(Side.WHITE, WinReason.RESIGNATION), a.log(id).game.result)
        same(id)
    }

    // ---- Timeouts (C2, R3) --------------------------------------------------------------------

    @Test
    fun `a claim on time needs the deadline, by the phone's estimate and by the Relay`() = runBlocking<Unit> {
        val id = started(days = 1)
        a.plays(id, "e2e4")
        b.c.syncAll()
        refused(a.c.claimTimeout(id), Refusal.TOO_EARLY)
        refused(b.c.claimTimeout(id), Refusal.NOT_ALLOWED)
        // A phone whose clock jumps a day ahead thinks the time is up; the Relay's clock decides.
        a.skew = Protocol.DAY_MS
        val early = assertNotNull(refused(a.c.claimTimeout(id), Refusal.TOO_EARLY))
        assertEquals("claim", early.rolledBack?.kind)
        a.skew = 0
        relay.now += Protocol.DAY_MS
        a.c.syncAll()
        done(a.c.claimTimeout(id))
        b.c.syncAll()
        same(id)
        assertEquals(Result.Win(Side.WHITE, WinReason.TIME), b.log(id).game.result)
    }

    @Test
    fun `a late Move is accepted and closes the claim window`() = runBlocking<Unit> {
        val id = started(days = 1)
        a.plays(id, "e2e4")
        relay.now += 2 * Protocol.DAY_MS
        a.c.syncAll()
        a.weather = offline
        queued(a.c.claimTimeout(id))
        b.plays(id, "e7e5")
        a.weather = Weather()
        a.c.syncAll()
        assertEquals("claim", a.game(id).rolledBack?.kind)
        assertNull(a.log(id).game.result)
        assertTrue(a.game(id).yourMove)
        same(id)
    }

    // ---- Rematch (E8) -------------------------------------------------------------------------

    private suspend fun over(): String {
        val id = started()
        a.plays(id, "e2e4")
        done(b.c.resign(id))
        a.c.syncAll()
        return id
    }

    @Test
    fun `a rematch swaps the Sides and starts when the other Seat accepts`() = runBlocking<Unit> {
        val id = over()
        done(a.c.offerRematch(id))
        val offer = assertNotNull(a.log(id).rematch)
        val fresh = a.game(offer.ref.gameId)
        assertEquals(Side.BLACK, fresh.seat.side)
        assertEquals(Stage.WAITING, fresh.stage)
        assertEquals(id, fresh.rematchOf)
        assertTrue(a.game(id).waitingOnOpponent)
        b.c.syncAll()
        refused(b.c.offerRematch(id), Refusal.NOT_ALLOWED)
        val joined = assertNotNull(done(b.c.acceptRematch(id)))
        assertEquals(Side.WHITE, joined.seat.side)
        assertEquals(Stage.ACTIVE, joined.stage)
        assertEquals(b.game(id).label, joined.label)
        a.c.syncAll()
        assertEquals(Stage.ACTIVE, a.game(joined.gameId).stage)
        assertEquals(true, a.log(id).rematch?.accepted)
        same(id)
        b.plays(joined.gameId, "d2d4")
    }

    @Test
    fun `a declined rematch cancels its new Game`() = runBlocking<Unit> {
        val id = over()
        done(a.c.offerRematch(id))
        val freshId = a.log(id).rematch!!.ref.gameId
        b.c.syncAll()
        done(b.c.declineRematch(id))
        a.c.syncAll()
        assertEquals(false, a.log(id).rematch?.accepted)
        assertNull(a.c.game(freshId))
        assertFalse(relay.exists(freshId))
        same(id)
    }

    @Test
    fun `when both Seats offer a rematch at once, one offer wins and the other's Game is cancelled`() = runBlocking<Unit> {
        val id = over()
        b.c.syncAll()
        // a's new Game is created, but its offer can't be sent yet.
        a.transport.beforeDelivery = { request -> if (request.path.endsWith("/events") && request.method == "POST") throw java.io.IOException("offline") }
        queued(a.c.offerRematch(id))
        a.transport.beforeDelivery = null
        val mine = a.game(id).pending!!.rematch!!.gameId
        done(b.c.offerRematch(id))
        a.c.syncAll()
        assertEquals("rematchOffer", a.game(id).rolledBack?.kind)
        assertNull(a.c.game(mine))
        assertFalse(relay.exists(mine))
        assertEquals(Side.BLACK, a.log(id).rematch?.offeredBy)
        val joined = assertNotNull(done(a.c.acceptRematch(id)))
        assertEquals(Stage.ACTIVE, joined.stage)
    }

    // ---- What the phone refuses (C5) ----------------------------------------------------------

    private fun tamperedWith(id: String, entry: (seq: Long, gameId: String, now: Long) -> LogEntry, reason: HaltReason = HaltReason.OUT_OF_SYNC) = runBlocking {
        a.c.syncAll()
        val before = a.game(id).entries
        relay.tamper(id) { seq, gameId, now -> entry(seq, gameId, now) }
        a.c.syncAll()
        val game = a.game(id)
        assertEquals(reason, game.halt?.reason, "${game.halt}")
        assertEquals(before, game.entries, "the refused entry never joins the log")
        refused(a.c.play(id, Position.START.legalMoves.first()), if (reason == HaltReason.NEEDS_UPDATE) Refusal.NEEDS_UPDATE else Refusal.HALTED)
        val requests = relay.requests.size
        a.c.syncAll()
        assertEquals(requests, relay.requests.size, "a stopped Game isn't read")
    }

    private fun digestAfter(vararg uci: String) = uci.fold(Position.START) { p, m -> p.play(p.moveFromUci(m)!!) }.digest

    @Test
    fun `an illegal Move from the other Seat freezes the Game as out of sync`() {
        val id = runBlocking { started(Side.BLACK) }
        tamperedWith(id, { seq, g, now -> LogEntry("1.0", g, seq, 1, Side.WHITE, "move", "e2e5", null, digestAfter("e2e4"), null, now) })
    }

    @Test
    fun `a digest that isn't this phone's freezes the Game`() {
        val id = runBlocking { started(Side.BLACK) }
        tamperedWith(id, { seq, g, now -> LogEntry("1.0", g, seq, 1, Side.WHITE, "move", "e2e4", null, digestAfter("d2d4"), null, now) })
    }

    @Test
    fun `a Move marked end that doesn't end the Game freezes it`() {
        val id = runBlocking { started(Side.BLACK) }
        tamperedWith(id, { seq, g, now -> LogEntry("1.0", g, seq, 1, Side.WHITE, "move", "e2e4", true, digestAfter("e2e4"), null, now) })
    }

    @Test
    fun `a claim before the deadline freezes the Game`() {
        val id = runBlocking { started(Side.BLACK).also { b.plays(it, "e2e4") } }
        tamperedWith(id, { seq, g, now -> LogEntry("1.0", g, seq, 1, Side.WHITE, "claim", null, null, digestAfter("e2e4"), null, now) })
    }

    @Test
    fun `a Move by the side not to move freezes the Game`() {
        val id = runBlocking { started(Side.WHITE) }
        tamperedWith(id, { seq, g, now -> LogEntry("1.0", g, seq, 1, Side.BLACK, "move", "e2e4", null, digestAfter("e2e4"), null, now) })
    }

    @Test
    fun `a kind from a later version stops the Game until Chess is updated`() {
        val id = runBlocking { started(Side.BLACK) }
        tamperedWith(id, { seq, g, now -> LogEntry("1.1", g, seq, 0, Side.WHITE, "takeback", null, null, Position.START.digest, null, now) }, HaltReason.NEEDS_UPDATE)
    }

    @Test
    fun `entries the phone already has are skipped when they come again, and must never change`() = runBlocking<Unit> {
        // A Relay that answers every read from the start, as a buggy cache or a replayed response might.
        val replaying = Phone(relay, 5, "replaying") { inner ->
            RelayTransport { r -> inner.exchange(RelayRequest(r.method, r.path.replace(Regex("since=\\d+"), "since=0"), r.bearer, r.body?.replace(Regex("\"since\":\\d+"), "\"since\":0"))) }
        }
        val invite = assertNotNull(done(replaying.c.createInvite(Side.WHITE)))
        done(b.c.redeemInvite(invite.invite!!.code!!))
        val id = invite.gameId
        replaying.plays(id, "e2e4")
        b.plays(id, "e7e5")
        replaying.plays(id, "g1f3")
        assertNull(replaying.game(id).halt)
        assertEquals(3, replaying.game(id).entries.size)
        relay.rewrite(id, 1) { it.copy(serverTime = it.serverTime + 1) }
        replaying.c.syncAll()
        assertEquals(HaltReason.OUT_OF_SYNC, replaying.game(id).halt?.reason)
        assertTrue("changed" in replaying.game(id).halt!!.detail)
        replaying.clean()
    }

    @Test
    fun `the Relay's bookkeeping disagreeing with the phone freezes the Game`() = runBlocking<Unit> {
        val id = started()
        a.plays(id, "e2e4")
        // The Relay closes the log on a Move the phones don't find ending the Game.
        relay.tamper(id, closes = true) { seq, g, now -> LogEntry("1.0", g, seq, 2, Side.BLACK, "move", "e7e5", null, digestAfter("e2e4", "e7e5"), null, now) }
        a.c.syncAll()
        assertEquals(HaltReason.OUT_OF_SYNC, a.game(id).halt?.reason)
        assertTrue("closed" in a.game(id).halt!!.detail, a.game(id).halt!!.detail)
    }

    @Test
    fun `a deleted Game is gone, and a Relay without major 1 means an update`() = runBlocking<Unit> {
        val id = started()
        val other = started()
        relay.delete(id)
        a.c.syncAll()
        assertEquals(HaltReason.GONE, a.game(id).halt?.reason)
        done(a.c.forget(id))
        relay.majors = listOf(2)
        a.c.syncAll()
        assertEquals(HaltReason.NEEDS_UPDATE, a.game(other).halt?.reason)
        refused(a.c.play(other, a.move(other, "e2e4")), Refusal.NEEDS_UPDATE)
    }
}
