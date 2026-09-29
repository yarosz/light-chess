package com.yarosz.chess.correspondence

import com.yarosz.chess.relay.EntryKind
import com.yarosz.chess.relay.ErrorCode
import com.yarosz.chess.relay.FakeRelay
import com.yarosz.chess.relay.RelayClient
import com.yarosz.chess.relay.RelayReply
import com.yarosz.chess.relay.SeatSecrets
import com.yarosz.chess.relay.Weather
import com.yarosz.chess.rules.Side
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * W9: the phone chooses its own seat secret on redeem and join, so a lost response is retried with
 * the same secret and the Relay answers the same Seat; another secret is refused. W2: "Send and offer
 * draw" holds the offer on the phone and sends it once the Move is stored.
 */
class SeatAndOfferTest {

    private val relay = FakeRelay()
    private val a = Phone(relay, 1, "a")
    private val b = Phone(relay, 2, "b")

    @AfterTest
    fun cleanUp() {
        a.clean()
        b.clean()
    }

    private suspend fun code(): Pair<String, String> {
        val invite = assertNotNull(done(a.c.createInvite(Side.WHITE)))
        return invite.gameId to assertNotNull(invite.invite?.code)
    }

    private suspend fun started(): String {
        val (id, code) = code()
        done(b.c.redeemInvite(code))
        a.c.syncAll()
        return id
    }

    // ---- W9 -------------------------------------------------------------------------------------

    @Test
    fun `a redeem retried with the same secret after a lost response takes the same Seat`() = runBlocking<Unit> {
        val (id, code) = code()
        b.weather = Weather(lostResponse = 1.0)
        refused(b.c.redeemInvite(code), Refusal.OFFLINE)
        assertEquals(1, b.c.seats.size, "the chosen secret is kept for the retry")
        assertTrue(b.c.seats.single().secret in File(b.dir, CorrespondenceStore.FILE).readText(), "saved before sending")
        a.c.syncAll()
        assertEquals(Stage.ACTIVE, a.game(id).stage, "the Relay gave the Seat")
        done(a.c.play(id, a.move(id, "e2e4")))

        b.weather = Weather()
        val joined = assertNotNull(done(b.c.redeemInvite(code.lowercase())))
        assertEquals(id, joined.gameId)
        assertEquals(Side.BLACK, joined.seat.side)
        assertTrue(b.c.seats.isEmpty())
        b.c.syncAll()
        done(b.c.play(id, b.move(id, "e7e5")))
        a.c.syncAll()
        assertEquals(relay.entries(id), a.game(id).entries)
        assertEquals(relay.entries(id), b.game(id).entries)
    }

    @Test
    fun `the background sync completes a lost redeem, across a restart`() = runBlocking<Unit> {
        val (id, code) = code()
        b.weather = Weather(lostResponse = 1.0)
        refused(b.c.redeemInvite(code), Refusal.OFFLINE)
        b.restart()
        assertEquals(1, b.c.seats.size)
        b.weather = Weather()
        assertTrue(b.c.hasWork)
        b.c.syncAll()
        assertTrue(b.c.seats.isEmpty())
        assertEquals(Stage.ACTIVE, b.game(id).stage)
        assertTrue(b.game(id).waitingOnOpponent)
    }

    @Test
    fun `the Relay refuses the same code with another secret`() = runBlocking<Unit> {
        val (_, code) = code()
        val client = RelayClient(relay)
        val secret = SeatSecrets.fresh()
        val first = assertIs<RelayReply.Ok<*>>(client.redeem(code, secret))
        val again = assertIs<RelayReply.Ok<*>>(client.redeem(code, secret))
        assertEquals(first.value, again.value.let { it })
        val other = assertIs<RelayReply.Refused>(client.redeem(code, SeatSecrets.fresh()))
        assertEquals(ErrorCode.INVITE_USED, other.error.code)
    }

    @Test
    fun `a Seat being taken holds a slot, and a refusal drops it`() = runBlocking<Unit> {
        b.weather = Weather(offline = 1.0)
        repeat(Correspondence.MAX_GAMES) { refused(b.c.redeemInvite("ABCD-EFG${"0123456789"[it]}"), Refusal.OFFLINE) }
        refused(b.c.redeemInvite("ZZZZ-ZZZZ"), Refusal.CAP_REACHED)
        b.weather = Weather()
        b.c.syncAll()
        assertTrue(b.c.seats.isEmpty(), "unknown codes are dropped once the Relay answers")
    }

    @Test
    fun `a join retried after a lost response accepts the rematch once`() = runBlocking<Unit> {
        val id = started()
        done(a.c.resign(id))
        b.c.syncAll()
        done(b.c.offerRematch(id))
        a.c.syncAll()
        a.weather = Weather(lostResponse = 1.0)
        refused(a.c.acceptRematch(id), Refusal.OFFLINE)
        val next = assertNotNull(a.c.seats.singleOrNull()?.gameId)
        a.weather = Weather()
        val joined = assertNotNull(done(a.c.acceptRematch(id)))
        assertEquals(next, joined.gameId)
        assertEquals(Side.BLACK, joined.seat.side)
        assertEquals(true, a.log(id).rematch?.accepted)
        b.c.syncAll()
        assertEquals(Stage.ACTIVE, b.game(next).stage)
        assertEquals(1, relay.entries(id).count { it.kind == EntryKind.REMATCH_ACCEPT.wire })
    }

    // ---- W2 -------------------------------------------------------------------------------------

    @Test
    fun `send and offer draw stores the Move, then the offer`() = runBlocking<Unit> {
        val id = started()
        done(a.c.play(id, a.move(id, "e2e4"), offerDraw = true))
        assertEquals(listOf("move", "drawOffer"), relay.entries(id).map { it.kind })
        assertFalse(a.game(id).drawOfferHeld)
        b.c.syncAll()
        assertEquals(Side.WHITE, b.log(id).game.openDrawOffer)
    }

    @Test
    fun `offline, the offer waits on the phone behind its Move and goes after a restart`() = runBlocking<Unit> {
        val id = started()
        a.weather = Weather(offline = 1.0)
        queued(a.c.play(id, a.move(id, "e2e4"), offerDraw = true))
        assertTrue(a.game(id).drawOfferHeld)
        assertEquals(EntryKind.MOVE.wire, a.game(id).pending?.kind, "one pending entry per Game (V8)")
        a.restart()
        a.weather = Weather()
        assertFalse(a.c.syncAll().retry)
        assertEquals(listOf("move", "drawOffer"), relay.entries(id).map { it.kind })
        assertNull(a.game(id).pending)
    }

    @Test
    fun `an offer the opponent's Move overtook is not sent`() = runBlocking<Unit> {
        val id = started()
        a.weather = Weather(lostResponse = 1.0)
        queued(a.c.play(id, a.move(id, "e2e4"), offerDraw = true))
        a.weather = Weather(offline = 1.0)
        b.c.syncAll()
        done(b.c.play(id, b.move(id, "e7e5")))
        a.weather = Weather()
        a.c.syncAll()
        assertEquals(listOf("move", "move"), relay.entries(id).map { it.kind })
        assertEquals(EntryKind.DRAW_OFFER, a.game(id).rolledBack?.entryKind, "shown as \"Offer not sent\"")
        assertFalse(a.game(id).drawOfferHeld)
        assertTrue(a.game(id).yourMove)
    }

    @Test
    fun `a rename stays on the phone`() = runBlocking<Unit> {
        val id = started()
        val before = relay.requests.size
        assertEquals("Ada", done(a.c.rename(id, "  Ada  "))?.label)
        refused(a.c.rename(id, " "), Refusal.NOT_ALLOWED)
        assertEquals("Ada", a.game(id).label)
        assertEquals(before, relay.requests.size, "nothing sent")
        a.restart()
        assertEquals("Ada", a.game(id).label)
    }
}
