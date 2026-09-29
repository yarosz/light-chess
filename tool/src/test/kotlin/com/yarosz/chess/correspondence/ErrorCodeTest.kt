package com.yarosz.chess.correspondence

import com.yarosz.chess.relay.ErrorCode
import com.yarosz.chess.relay.ErrorDetail
import com.yarosz.chess.relay.Protocol
import com.yarosz.chess.relay.SyncReply
import com.yarosz.chess.relay.SyncResult
import com.yarosz.chess.relay.FakeRelay
import com.yarosz.chess.relay.RelayRequest
import com.yarosz.chess.relay.RelayResponse
import com.yarosz.chess.relay.RelayTransport
import com.yarosz.chess.rules.Side
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Every error code of docs/protocol.md, answered to each kind of request the sync engine makes,
 * and what the phone does with it. The fake Relay answers everything else honestly.
 */
class ErrorCodeTest {

    private val relay = FakeRelay()

    /** The next request [matching] gets [code] instead of the Relay's answer. */
    private class Injector {
        var matching: ((RelayRequest) -> Boolean)? = null
        var code: String = ""
        var extra: String = ""

        fun wrap(inner: RelayTransport) = RelayTransport { request ->
            val match = matching
            if (match != null && match(request)) {
                matching = null
                val status = FakeRelay.STATUS[code] ?: 400
                RelayResponse(status, """{"error":{"code":"$code","message":"injected"$extra}}""")
            } else inner.exchange(request)
        }
    }

    private val injector = Injector()
    private val a = Phone(relay, 1, "a", injector::wrap)
    private val joiners = mutableListOf<Phone>()

    @AfterTest
    fun cleanUp() {
        a.clean()
        joiners.forEach { it.clean() }
    }

    private val codes = ErrorCode.entries.filter { it != ErrorCode.UNKNOWN }.map { it.wire } + "a_code_from_a_later_version"

    /** A Game [a] plays White in, joined by a fresh phone (each has its own cap of five). */
    private suspend fun started(): String {
        relay.now += 61_000 // past the redemption rate limit's window
        val invite = assertNotNull(done(a.c.createInvite(Side.WHITE)))
        val b = Phone(relay, 100 + joiners.size, "b${joiners.size}").also { joiners += it }
        done(b.c.redeemInvite(invite.invite!!.code!!))
        a.c.syncAll()
        return invite.gameId
    }

    /** Frees [a]'s slots: resigns what it can, forgets everything. */
    private suspend fun clear() {
        for (g in a.c.games.toList()) {
            when {
                g.halt != null || g.stage == Stage.OVER -> {}
                g.stage == Stage.WAITING -> a.c.cancelInvite(g.gameId)
                else -> done(a.c.resign(g.gameId))
            }
            a.c.forget(g.gameId)
        }
        assertTrue(a.c.games.isEmpty(), "${a.c.games}")
    }

    private fun inject(code: String, extra: String = "", matching: (RelayRequest) -> Boolean) {
        injector.code = code
        injector.extra = extra
        injector.matching = matching
    }

    private val isAppend: (RelayRequest) -> Boolean = { it.method == "POST" && it.path.endsWith("/events") }

    @Test
    fun `each code on an append`() = runBlocking<Unit> {
        for (code in codes) {
            relay.majors = listOf(1)
            val id = started()
            inject(code, if (code == "seq_conflict") ""","latestSeq":0""" else "", isAppend)
            val delivery = a.c.play(id, a.move(id, "e2e4"))
            val game = a.game(id)
            when (code) {
                "unsupported_version", "version_mismatch" -> {
                    refused(delivery, Refusal.NEEDS_UPDATE)
                    assertEquals(HaltReason.NEEDS_UPDATE, game.halt?.reason, code)
                    assertNotNull(game.pending, "kept for the updated Tool")
                }
                "game_not_found" -> {
                    refused(delivery, Refusal.HALTED)
                    assertEquals(HaltReason.GONE, game.halt?.reason)
                }
                "bad_seat_secret" -> {
                    refused(delivery, Refusal.HALTED)
                    assertEquals(HaltReason.SEAT_LOST, game.halt?.reason)
                }
                "claim_too_early" -> {
                    refused(delivery, Refusal.TOO_EARLY)
                    assertEquals("e2e4", game.rolledBack?.uci)
                }
                "seq_conflict" -> {
                    // The log didn't move, so the phone reads, finds nothing new and sends again.
                    done(delivery)
                    assertEquals(1, relay.entries(id).size)
                }
                else -> {
                    // A refusal a fresh read doesn't explain: rolled back, nothing on the Relay (C8).
                    refused(delivery, Refusal.ROLLED_BACK)
                    assertEquals("e2e4", game.rolledBack?.uci, code)
                    assertNull(game.halt, code)
                    assertTrue(relay.entries(id).isEmpty(), code)
                    assertTrue(game.yourMove, "$code: the user can play again")
                }
            }
            clear()
        }
    }

    @Test
    fun `each code on a read`() = runBlocking<Unit> {
        for (code in codes) {
            val id = started()
            val waiting = assertNotNull(done(a.c.createInvite(Side.WHITE))).gameId
            inject(code) { it.path.endsWith("/sync") }
            val report = a.c.syncAll()
            if (code == "unsupported_version" || code == "version_mismatch") {
                assertEquals(HaltReason.NEEDS_UPDATE, a.game(id).halt?.reason, code)
                assertEquals(HaltReason.NEEDS_UPDATE, a.game(waiting).halt?.reason, code)
            } else {
                // A refusal of the whole batch changes no Game; the job tries again later.
                assertTrue(report.retry, code)
                assertNull(a.game(id).halt, code)
                assertNull(a.game(waiting).halt, code)
            }
            clear()
        }
    }

    @Test
    fun `each code for one Game of a batch`() = runBlocking<Unit> {
        for (code in codes) {
            val id = started()
            val waiting = assertNotNull(done(a.c.createInvite(Side.WHITE))).gameId
            injector.matching = null
            val results = RelayTransport { request ->
                val response = relay.handle(request)
                if (!request.path.endsWith("/sync")) return@RelayTransport response
                val reply = Protocol.json.decodeFromString(SyncReply.serializer(), response.body)
                val results = reply.results.map { if (it.gameId == id) SyncResult(id, false, error = ErrorDetail(code, "injected")) else it }
                RelayResponse(response.status, Protocol.json.encodeToString(SyncReply.serializer(), SyncReply(results)))
            }
            val phone = Correspondence(com.yarosz.chess.relay.RelayClient(results), a.store) { relay.now }
            phone.syncAll()
            val game = phone.game(id)
            when (code) {
                "unsupported_version", "version_mismatch" -> assertEquals(HaltReason.NEEDS_UPDATE, game?.halt?.reason, code)
                "game_not_found" -> assertEquals(HaltReason.GONE, game?.halt?.reason, code)
                "bad_seat_secret" -> assertEquals(HaltReason.SEAT_LOST, game?.halt?.reason, code)
                else -> assertNull(game?.halt, code)
            }
            assertNull(phone.game(waiting)?.halt, "the other Game of the batch is read as usual")
            a.restart()
            clear()
        }
        // An invite the Relay no longer has drops its row (G2); a started Game is gone (C6).
        val id = started()
        val waiting = assertNotNull(done(a.c.createInvite(Side.WHITE))).gameId
        relay.delete(waiting)
        relay.delete(id)
        a.c.syncAll()
        assertNull(a.c.game(waiting))
        assertEquals(HaltReason.GONE, a.game(id).halt?.reason)
    }

    @Test
    fun `each code on create, redeem and cancel`() = runBlocking<Unit> {
        val expected = mapOf(
            "unsupported_version" to Refusal.NEEDS_UPDATE,
            "version_mismatch" to Refusal.NEEDS_UPDATE,
            "invite_not_found" to Refusal.INVITE_NOT_FOUND,
            "invite_used" to Refusal.INVITE_USED,
            "rate_limited" to Refusal.RATE_LIMITED,
        )
        for (code in codes) {
            inject(code) { it.path.endsWith("/games") }
            refused(a.c.createInvite(Side.WHITE), expected[code] ?: if (code == "invite_redeemed") Refusal.ALREADY_REDEEMED else if (code == "game_not_found") Refusal.NO_SUCH_GAME else if (code == "bad_seat_secret") Refusal.HALTED else Refusal.NOT_ALLOWED)
            inject(code) { it.path.endsWith("/redeem") }
            val redeem = a.c.redeemInvite("ABCDEFGH")
            assertIs<Delivery.Refused>(redeem)
            if (code == "bad_request") assertEquals(Refusal.BAD_CODE, redeem.reason)
            expected[code]?.let { assertEquals(it, redeem.reason, code) }
            assertTrue(a.c.games.isEmpty(), code)
        }
        for (code in codes) {
            val invite = assertNotNull(done(a.c.createInvite(Side.WHITE)))
            inject(code) { it.method == "DELETE" }
            val cancel = a.c.cancelInvite(invite.gameId)
            when (code) {
                "game_not_found" -> {
                    done(cancel)
                    assertNull(a.c.game(invite.gameId))
                }
                "invite_redeemed" -> refused(cancel, Refusal.ALREADY_REDEEMED)
                "bad_seat_secret" -> assertEquals(HaltReason.SEAT_LOST, a.game(invite.gameId).halt?.reason)
                "unsupported_version", "version_mismatch" -> assertEquals(HaltReason.NEEDS_UPDATE, a.game(invite.gameId).halt?.reason)
                else -> {
                    assertIs<Delivery.Refused>(cancel, code)
                    assertEquals(false, a.game(invite.gameId).invite?.cancelling, code)
                }
            }
            a.c.game(invite.gameId)?.let {
                if (it.halt == null) done(a.c.cancelInvite(it.gameId)) else a.c.forget(it.gameId)
            }
        }
    }
}
