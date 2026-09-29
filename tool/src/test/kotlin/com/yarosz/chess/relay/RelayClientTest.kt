package com.yarosz.chess.relay

import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.Side
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** [RelayClient] against the fake Relay: each endpoint, CAS and retries, and the error codes as the phone sees them. */
class RelayClientTest {

    private val relay = FakeRelay()
    private val client = RelayClient(relay)

    private fun <T> ok(reply: RelayReply<T>): T {
        assertIs<RelayReply.Ok<T>>(reply, "$reply")
        return reply.value
    }

    private fun refused(reply: RelayReply<*>, code: ErrorCode): RelayError {
        assertIs<RelayReply.Refused>(reply, "$reply")
        assertEquals(code, reply.error.code, reply.error.detail.message)
        return reply.error
    }

    private val start = Position.START.digest
    private fun after(vararg uci: String): String = uci.fold(Position.START) { p, m -> p.play(checkNotNull(p.moveFromUci(m))) }.digest

    private fun started(): Triple<Created, Redeemed, String> = runBlocking {
        val created = ok(client.create(Side.BLACK, 3, InviteKind.CODE))
        val redeemed = ok(client.redeem(checkNotNull(InviteCodes.normalize(checkNotNull(created.inviteCode)))))
        Triple(created, redeemed, created.gameId)
    }

    @Test
    fun `health lists the majors`() = runBlocking<Unit> {
        assertEquals(Health("ok", "1.0", listOf(1)), ok(client.health()))
    }

    @Test
    fun `create, redeem, then both Seats read the started Game`() = runBlocking<Unit> {
        val created = ok(client.create(Side.BLACK, 7, InviteKind.CODE))
        assertEquals(Side.BLACK, created.side)
        assertEquals("waiting", ok(client.events(created.gameId, created.seatSecret, 0)).status)
        val redeemed = ok(client.redeem(checkNotNull(InviteCodes.normalize(checkNotNull(created.inviteCode)))))
        assertEquals(Side.WHITE, redeemed.side)
        assertEquals(created.gameId, redeemed.gameId)
        val view = ok(client.events(created.gameId, created.seatSecret, 0))
        assertEquals("active", view.status)
        assertEquals(redeemed.startedAt + 7 * Protocol.DAY_MS, view.deadline)
        assertEquals(Side.WHITE, ok(client.events(created.gameId, redeemed.seatSecret, 0)).side)
    }

    @Test
    fun `an append is compare-and-swap on seq, and a repeat answers 200 with the stored entry`() = runBlocking<Unit> {
        val (_, white, gameId) = started()
        val move = Append(seq = 1, ply = 1, kind = "move", uci = "e2e4", hash = after("e2e4"))
        val first = client.append(gameId, white.seatSecret, move)
        assertEquals(201, (first as RelayReply.Ok).status)
        relay.now += 5_000
        val again = client.append(gameId, white.seatSecret, move)
        assertEquals(200, (again as RelayReply.Ok).status)
        assertEquals(first.value, again.value, "the original serverTime")
        val other = Append(seq = 1, ply = 1, kind = "move", uci = "d2d4", hash = after("d2d4"))
        assertEquals(1L, refused(client.append(gameId, white.seatSecret, other), ErrorCode.SEQ_CONFLICT).detail.latestSeq)
        val skip = Append(seq = 3, ply = 2, kind = "resign", hash = start)
        assertEquals(1L, refused(client.append(gameId, white.seatSecret, skip), ErrorCode.SEQ_CONFLICT).detail.latestSeq)
    }

    @Test
    fun `events since a seq, and a batched sync in request order with one Game's error`() = runBlocking<Unit> {
        val (created, white, gameId) = started()
        ok(client.append(gameId, white.seatSecret, Append(seq = 1, ply = 1, kind = "move", uci = "e2e4", hash = after("e2e4"))))
        ok(client.append(gameId, created.seatSecret, Append(seq = 2, ply = 2, kind = "move", uci = "e7e5", hash = after("e2e4", "e7e5"))))
        assertEquals(listOf(2L), ok(client.events(gameId, white.seatSecret, 1)).entries.map { it.seq })
        val missing = "f".repeat(64)
        val results = ok(client.sync(listOf(SyncItem(gameId, white.seatSecret, 0), SyncItem(missing, white.seatSecret, 0), SyncItem(gameId, created.seatSecret, 2))))
        assertEquals(listOf(gameId, missing, gameId), results.map { it.gameId })
        assertEquals(2, results[0].game?.entries?.size)
        assertEquals("game_not_found", results[1].error?.code)
        assertEquals(0, results[2].game?.entries?.size)
    }

    @Test
    fun `cancel wins before a redeem, and loses after one`() = runBlocking<Unit> {
        val a = ok(client.create(Side.WHITE, 3, InviteKind.CODE))
        assertEquals(Cancelled(true, a.gameId), ok(client.cancelInvite(a.gameId, a.seatSecret)))
        refused(client.cancelInvite(a.gameId, a.seatSecret), ErrorCode.GAME_NOT_FOUND)
        refused(client.redeem(checkNotNull(InviteCodes.normalize(a.inviteCode!!))), ErrorCode.INVITE_NOT_FOUND)
        val (b, joined, _) = started()
        refused(client.cancelInvite(b.gameId, b.seatSecret), ErrorCode.INVITE_REDEEMED)
        refused(client.cancelInvite(b.gameId, joined.seatSecret), ErrorCode.BAD_SEAT_SECRET)
        refused(client.redeem(checkNotNull(InviteCodes.normalize(b.inviteCode!!))), ErrorCode.INVITE_USED)
    }

    @Test
    fun `a rematch Game is joined with its token`() = runBlocking<Unit> {
        val created = ok(client.create(Side.WHITE, 1, InviteKind.TOKEN))
        val token = assertNotNull(created.joinToken)
        refused(client.join(created.gameId, "x".repeat(43)), ErrorCode.INVITE_NOT_FOUND)
        val joined = ok(client.join(created.gameId, token))
        assertEquals(Side.BLACK, joined.side)
        refused(client.join(created.gameId, token), ErrorCode.INVITE_USED)
    }

    @Test
    fun `a claim before the deadline is refused with the deadline`() = runBlocking<Unit> {
        val (created, white, gameId) = started()
        ok(client.append(gameId, white.seatSecret, Append(seq = 1, ply = 1, kind = "move", uci = "e2e4", hash = after("e2e4"))))
        val claim = Append(seq = 2, ply = 1, kind = "claim", hash = after("e2e4"))
        val error = refused(client.append(gameId, white.seatSecret, claim), ErrorCode.CLAIM_TOO_EARLY)
        assertEquals(relay.now + 3 * Protocol.DAY_MS, error.detail.deadline)
        refused(client.append(gameId, created.seatSecret, claim), ErrorCode.NOT_YOUR_TURN)
        relay.now += 3 * Protocol.DAY_MS
        ok(client.append(gameId, white.seatSecret, claim))
        assertEquals("closed", ok(client.events(gameId, created.seatSecret, 0)).status)
    }

    @Test
    fun `a Relay without major 1 answers unsupported_version on every endpoint`() = runBlocking<Unit> {
        val (created, white, gameId) = started()
        relay.majors = listOf(2)
        val replies = listOf(
            client.create(Side.WHITE, 3, InviteKind.CODE),
            client.redeem("ABCDEFGH"),
            client.join(gameId, "x".repeat(43)),
            client.cancelInvite(gameId, created.seatSecret),
            client.events(gameId, white.seatSecret, 0),
            client.append(gameId, white.seatSecret, Append(seq = 1, ply = 1, kind = "move", uci = "e2e4", hash = after("e2e4"))),
            client.sync(listOf(SyncItem(gameId, white.seatSecret, 0))),
        )
        for (reply in replies) {
            val error = refused(reply, ErrorCode.UNSUPPORTED_VERSION)
            assertTrue(error.needsUpdate)
            assertEquals(listOf(2), error.detail.majors)
        }
    }

    @Test
    fun `an append to a Game pinned to another major gets version_mismatch`() = runBlocking<Unit> {
        relay.majors = listOf(1, 2)
        val raw = relay.handle(RelayRequest("POST", "/v2/games", body = """{"v":"2.0","side":"white","daysPerMove":3}"""))
        val created = Protocol.json.decodeFromString(Created.serializer(), raw.body)
        val redeemed = relay.handle(RelayRequest("POST", "/v2/invites/${created.inviteCode}/redeem", body = """{"v":"2.0","seatSecret":"${"S".repeat(43)}"}"""))
        assertEquals(200, redeemed.status)
        val error = refused(client.append(created.gameId, created.seatSecret, Append(seq = 1, ply = 1, kind = "move", uci = "e2e4", hash = after("e2e4"))), ErrorCode.VERSION_MISMATCH)
        assertEquals("2.0", error.detail.gameV)
        assertTrue(error.needsUpdate)
    }

    @Test
    fun `no response, a restart and a garbled body are all unreachable`() = runBlocking<Unit> {
        val down = RelayClient { throw IOException("offline") }
        assertIs<RelayReply.Unreachable>(down.health())
        val restarting = RelayClient(FlakyTransport(relay, Random(1), Weather(restart = 1.0)))
        assertIs<RelayReply.Unreachable>(restarting.create(Side.WHITE, 3, InviteKind.CODE))
        assertEquals(0, relay.gameIds().size, "a 503 means the Relay didn't act")
        val garbled = RelayClient { RelayResponse(201, """{"v":"1.0","gameId":"not-an-id","side":"white","daysPerMove":3,"seatSecret":"s","inviteCode":"ABCD-EFGH","inviteExpiresAt":1,"serverTime":1}""") }
        assertIs<RelayReply.Unreachable>(garbled.create(Side.WHITE, 3, InviteKind.CODE))
    }

    @Test
    fun `redemptions by code are rate limited`() = runBlocking<Unit> {
        repeat(FakeRelay.REDEEM_LIMIT) { refused(client.redeem("ABCDEFGH"), ErrorCode.INVITE_NOT_FOUND) }
        val limited = refused(client.redeem("ABCDEFGH"), ErrorCode.RATE_LIMITED)
        assertEquals(60L, limited.retryAfterSeconds)
        relay.now += 60_000
        refused(client.redeem("ABCDEFGH"), ErrorCode.INVITE_NOT_FOUND)
    }

    @Test
    fun `the requests carry the seat secret only as the bearer`() = runBlocking<Unit> {
        val seen = mutableListOf<RelayRequest>()
        val spy = RelayClient { request -> seen += request; relay.handle(request) }
        val created = ok(spy.create(Side.WHITE, 3, InviteKind.CODE))
        spy.events(created.gameId, created.seatSecret, 0)
        val read = seen.last()
        assertEquals(created.seatSecret, read.bearer)
        assertTrue(created.seatSecret !in read.toString())
        assertTrue(created.seatSecret !in read.path)
    }
}
