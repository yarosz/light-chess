package com.yarosz.chess.relay

import com.yarosz.chess.rules.Side
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The codec against docs/protocol.md: its examples as golden JSON, and its tables of codes and kinds. */
class ProtocolTest {

    private val json = Protocol.json
    private val doc = File("../docs/protocol.md").readText()

    private fun minified(text: String) = json.parseToJsonElement(text).toString()

    // ---- Golden bodies, copied from docs/protocol.md ------------------------------------------

    @Test
    fun `the entry example reads`() {
        val entry = json.decodeFromString(
            LogEntry.serializer(),
            """{ "v": "1.0", "gameId": "5f0c…", "seq": 3, "ply": 2, "side": "black", "kind": "move", "uci": "e7e5", "hash": "9c1b…", "serverTime": 1790000000000 }""",
        )
        assertEquals(LogEntry("1.0", "5f0c…", 3, 2, Side.BLACK, "move", "e7e5", null, "9c1b…", null, 1790000000000), entry)
        assertEquals(EntryKind.MOVE, entry.entryKind)
    }

    @Test
    fun `an append is written exactly as the doc's example, with no nulls`() {
        val move = Append(seq = 3, ply = 2, kind = "move", uci = "e7e5", hash = "9c1b…")
        assertEquals(minified("""{ "v": "1.0", "seq": 3, "ply": 2, "kind": "move", "uci": "e7e5", "hash": "9c1b…" }"""), json.encodeToString(Append.serializer(), move))
        // The Relay refuses "uci": null on a resign, so absent fields stay absent.
        val resign = Append(seq = 4, ply = 2, kind = "resign", hash = "ab")
        assertEquals("""{"v":"1.0","seq":4,"ply":2,"kind":"resign","hash":"ab"}""", json.encodeToString(Append.serializer(), resign))
        val ending = Append(seq = 5, ply = 3, kind = "move", uci = "d8h4", end = true, hash = "cd")
        assertEquals("""{"v":"1.0","seq":5,"ply":3,"kind":"move","uci":"d8h4","end":true,"hash":"cd"}""", json.encodeToString(Append.serializer(), ending))
    }

    @Test
    fun `create, redeem, join and sync requests match the doc`() {
        assertEquals(
            minified("""{ "v": "1.0", "side": "white", "daysPerMove": 3, "invite": "code" }"""),
            json.encodeToString(CreateRequest.serializer(), CreateRequest(side = Side.WHITE, daysPerMove = 3, invite = InviteKind.CODE.wire)),
        )
        assertEquals("""{"v":"1.0","seatSecret":"s"}""", json.encodeToString(RedeemRequest.serializer(), RedeemRequest(seatSecret = "s")))
        assertEquals("""{"v":"1.0","seatSecret":"s","joinToken":"t"}""", json.encodeToString(JoinRequest.serializer(), JoinRequest(seatSecret = "s", joinToken = "t")))
        assertEquals("RedeemRequest(1.0)", RedeemRequest(seatSecret = "s").toString(), "toString leaves the secret out")
        assertEquals(
            minified("""{ "v": "1.0", "games": [ { "gameId": "…", "seatSecret": "…", "since": 3 } ] }"""),
            json.encodeToString(SyncRequest.serializer(), SyncRequest(games = listOf(SyncItem("…", "…", 3)))),
        )
    }

    @Test
    fun `the create and redeem responses read`() {
        val created = json.decodeFromString(
            Created.serializer(),
            """{
              "v": "1.0", "gameId": "5f0c…", "side": "white", "daysPerMove": 3,
              "seatSecret": "q3…", "inviteCode": "ABCD-EFGH", "inviteExpiresAt": 1790172800000,
              "serverTime": 1790000000000
            }""",
        )
        assertEquals(Created("1.0", "5f0c…", Side.WHITE, 3, "q3…", "ABCD-EFGH", null, 1790172800000, 1790000000000), created)
        val redeemed = json.decodeFromString(
            Redeemed.serializer(),
            """{
              "v": "1.0", "gameId": "5f0c…", "side": "black", "daysPerMove": 3,
              "startedAt": 1790000500000, "serverTime": 1790000500000
            }""",
        )
        assertEquals(Redeemed("1.0", "5f0c…", Side.BLACK, 3, 1790000500000, 1790000500000), redeemed)
        assertEquals(Cancelled(true, "…"), json.decodeFromString(Cancelled.serializer(), """{ "cancelled": true, "gameId": "…" }"""))
        assertEquals(Health("ok", "1.0", listOf(1)), json.decodeFromString(Health.serializer(), """{ "status": "ok", "protocol": "1.0", "majors": [1] }"""))
    }

    @Test
    fun `the GET events response reads`() {
        val view = json.decodeFromString(
            GameView.serializer(),
            """{
              "v": "1.0", "gameId": "5f0c…", "status": "active", "side": "white", "daysPerMove": 3,
              "createdAt": 1790000000000, "startedAt": 1790000500000, "inviteExpiresAt": null,
              "latestSeq": 3, "latestPly": 2, "openDrawBy": null, "deadline": 1790259700000,
              "rematch": null, "serverTime": 1790001000000,
              "entries": [ ]
            }""",
        )
        assertEquals(
            GameView("1.0", "5f0c…", "active", Side.WHITE, 3, 1790000000000, 1790000500000, null, 3, 2, null, 1790259700000, null, 1790001000000, emptyList()),
            view,
        )
        val rematch = json.decodeFromString(RematchStatus.serializer(), """{ "offeredBy": "white", "gameId": "…", "answer": null }""")
        assertEquals(RematchStatus(Side.WHITE, "…", null), rematch)
    }

    @Test
    fun `the sync response and the error shape read`() {
        val reply = json.decodeFromString(
            SyncReply.serializer(),
            """{ "results": [
              { "gameId": "a", "ok": false, "error": { "code": "game_not_found", "message": "…" } }
            ] }""",
        )
        assertEquals(listOf(SyncResult("a", false, null, ErrorDetail("game_not_found", "…"))), reply.results)
        val error = json.decodeFromString(
            ErrorBody.serializer(),
            """{ "error": { "code": "seq_conflict", "message": "Entry 7 already exists; fetch events since 6", "latestSeq": 6 } }""",
        ).error
        assertEquals(ErrorCode.SEQ_CONFLICT, ErrorCode.of(error.code))
        assertEquals(6L, error.latestSeq)
    }

    @Test
    fun `fields and kinds a later minor adds are ignored or kept as text`() {
        val entry = json.decodeFromString(
            LogEntry.serializer(),
            """{ "v": "1.1", "gameId": "g", "seq": 1, "ply": 0, "side": "white", "kind": "chat", "hash": "h", "serverTime": 1, "mood": "calm" }""",
        )
        assertNull(entry.entryKind)
        assertEquals("chat", entry.kind)
        val health = json.decodeFromString(Health.serializer(), """{ "status": "ok", "protocol": "1.3", "majors": [1, 2], "region": "x" }""")
        assertEquals(listOf(1, 2), health.majors)
        assertEquals(ErrorCode.UNKNOWN, ErrorCode.of("new_code"))
    }

    // ---- The doc's tables --------------------------------------------------------------------

    @Test
    fun `every error code in the doc's table is known, with its status, and nothing else is`() {
        val rows = Regex("""^\| (\d{3}) \| `([a-z_]+)` \|""", RegexOption.MULTILINE).findAll(doc).map { it.groupValues[2] to it.groupValues[1].toInt() }.toList()
        assertEquals(25, rows.size)
        assertEquals(ErrorCode.entries.filter { it != ErrorCode.UNKNOWN }.map { it.wire }.toSet(), rows.map { it.first }.toSet())
        for ((code, status) in rows) assertEquals(status, FakeRelay.STATUS[code], code)
        // The two version codes, and only they, mean "Update Chess to continue this game" (R6).
        for ((code, status) in rows) {
            val error = RelayError(status, ErrorDetail(code))
            assertEquals(code == "unsupported_version" || code == "version_mismatch", error.needsUpdate, code)
        }
    }

    @Test
    fun `every kind in the doc's table is known`() {
        val kinds = Regex("""^\| `([a-zA-Z]+)` \| .* \| .* \|$""", RegexOption.MULTILINE).findAll(doc.substringAfter("Kinds [").substringBefore("There is no takeback"))
            .map { it.groupValues[1] }.filter { it != "kind" }.toList() // the header row
        assertEquals(EntryKind.entries.map { it.wire }, kinds)
    }

    @Test
    fun `the version is major dot minor`() {
        assertEquals(1, Protocol.majorOf(Protocol.VERSION))
        assertEquals(12, Protocol.majorOf("12.3"))
        assertNull(Protocol.majorOf("1"))
        assertNull(Protocol.majorOf("v1.0"))
    }

    @Test
    fun `invite codes read the way the Relay reads them`() {
        assertEquals("ABCD0F11", InviteCodes.normalize("abcd-ofil"))
        assertEquals("ABCDEFGH", InviteCodes.normalize(" ABCD EFGH "))
        assertNull(InviteCodes.normalize("ABCD-EFGU")) // U isn't Crockford
        assertNull(InviteCodes.normalize("ABCD-EFG"))
        assertEquals("ABCD-EFGH", InviteCodes.display("abcdefgh"))
    }

    // ---- Decoding a response ---------------------------------------------------------------

    @Test
    fun `a response the phone can't use is unreachable, never half read`() {
        val s = Health.serializer()
        assertIs<RelayReply.Unreachable>(RelayClient.decode(RelayResponse(503, "<html>"), s))
        assertIs<RelayReply.Unreachable>(RelayClient.decode(RelayResponse(502, ""), s))
        assertIs<RelayReply.Unreachable>(RelayClient.decode(RelayResponse(200, "not json"), s))
        assertIs<RelayReply.Unreachable>(RelayClient.decode(RelayResponse(200, """{"status":"ok"}"""), s))
        assertIs<RelayReply.Unreachable>(RelayClient.decode(RelayResponse(403, "<html>blocked</html>"), s))
        val side = RelayClient.decode(RelayResponse(200, """{ "offeredBy": "green", "gameId": "g" }"""), RematchStatus.serializer())
        assertIs<RelayReply.Unreachable>(side)
        val limited = RelayClient.decode(RelayResponse(429, """{"error":{"code":"rate_limited","message":"m"}}""", "60"), s)
        assertIs<RelayReply.Refused>(limited)
        assertEquals(ErrorCode.RATE_LIMITED, limited.error.code)
        assertEquals(60L, limited.error.retryAfterSeconds)
        assertFalse(limited.error.needsUpdate)
        val ok = RelayClient.decode(RelayResponse(200, """{"status":"ok","protocol":"1.0","majors":[1]}"""), s)
        assertTrue(ok is RelayReply.Ok && ok.value.majors == listOf(1))
    }

    // ---- The live socket (G3) ------------------------------------------------------------------

    @Test
    fun `the live messages read as the doc writes them`() {
        val presence = assertIs<LivePresence>(LiveFrame.decode("""{ "type": "presence", "live": true, "opponent": "here", "serverTime": 1790001000000 }"""))
        assertEquals(LivePresence(true, "here", 1790001000000), presence)
        assertTrue(presence.opponentHere)
        assertTrue("""{ "type": "presence", "live": true, "opponent": "here", "serverTime": 1790001000000 }""" in doc, "the doc's example")
        val gone = assertIs<LivePresence>(LiveFrame.decode("""{"type":"presence","live":false,"opponent":"gone","serverTime":1}"""))
        assertFalse(gone.opponentHere || gone.live)
        val pushed = assertIs<LiveEntry>(
            LiveFrame.decode(
                """{"type":"entry","entry":{"v":"1.0","gameId":"g","seq":1,"ply":1,"side":"white","kind":"move","uci":"e2e4","hash":"h","serverTime":5}}""",
            ),
        )
        assertEquals("e2e4", pushed.entry.uci)
        assertEquals(LiveError("bad_request"), LiveFrame.decode("""{ "type": "error", "code": "bad_request" }"""))
        assertTrue("""{ "type": "error", "code": "bad_request" }""" in doc)
        assertTrue("""{ "type": "entry", "entry": {…} }""" in doc)
    }

    @Test
    fun `a live message this version can't read is null, never half read`() {
        assertNull(LiveFrame.decode("""{"type":"typing"}"""), "a type from a later minor")
        assertNull(LiveFrame.decode("""{"type":"presence","live":"yes","opponent":"here","serverTime":1}"""))
        assertNull(LiveFrame.decode("""{"live":true}"""))
        assertNull(LiveFrame.decode("[1]"))
        assertNull(LiveFrame.decode("not json"))
        // A field a later minor adds is ignored.
        assertIs<LivePresence>(LiveFrame.decode("""{"type":"presence","live":true,"opponent":"here","serverTime":1,"since":3}"""))
    }

    @Test
    fun `the ping is the doc's, under the Relay's 256 bytes (R5)`() {
        assertTrue("""{ "type": "ping" }""" in doc)
        assertEquals(minified("""{ "type": "ping" }"""), LiveFrame.PING)
        assertTrue(LiveFrame.PING.toByteArray().size <= 256)
        val limits = File("../relay/src/protocol.ts").readText()
        assertTrue("MAX_WS_MESSAGE_BYTES = 256" in limits)
        assertTrue("PRESENCE_TIMEOUT_MS = 10_000" in limits, "the 10 s the grace and G3 follow")
    }

    @Test
    fun `the live endpoint is WSS, or WS to a local Worker`() {
        val id = "a".repeat(64)
        assertEquals("wss://chess-relay.example.com/v1/games/$id/live", OkHttpLiveConnector.url("https://chess-relay.example.com/", id))
        assertEquals("ws://10.0.2.2:8787/v1/games/$id/live", OkHttpLiveConnector.url("http://10.0.2.2:8787", id))
        assertTrue("`GET /v1/games/{gameId}/live`" in doc)
        assertFalse(runCatching { OkHttpLiveConnector.url("https://relay", "../x") }.isSuccess, "a Game id only as the Relay handed it out")
        assertFalse(runCatching { OkHttpLiveConnector("http://relay.example.com", OkHttpTransport.defaultClient()) }.isSuccess, "no cleartext")
    }
}
