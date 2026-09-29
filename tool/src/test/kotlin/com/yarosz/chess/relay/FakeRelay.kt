package com.yarosz.chess.relay

import com.yarosz.chess.rules.Side
import java.io.IOException
import java.security.MessageDigest
import kotlin.random.Random
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * An in-memory Relay that follows docs/protocol.md 1.0 the way relay/src does (game.ts, index.ts,
 * protocol.ts): the same routes, checks, error codes and bookkeeping, with a clock the test moves
 * ([now]). It knows no chess, like the real one.
 *
 * For the tests that a real Relay can't stage, [tamper] appends an entry without any check (a
 * Relay bug, or a phone the protocol can't catch), and [rewrite] changes a stored entry.
 */
class FakeRelay(var now: Long = START, seed: Int = 1) : RelayTransport {
    private val random = Random(seed)
    private val games = LinkedHashMap<String, Stored>()
    private val redemptions = ArrayDeque<Long>()

    /** Every request handled, as "METHOD path" (secrets never recorded). */
    val requests = mutableListOf<String>()

    var majors = listOf(1)

    /** How many times each error code was answered. */
    val refusals = mutableMapOf<String, Int>()

    override suspend fun exchange(request: RelayRequest): RelayResponse = handle(request)

    fun handle(request: RelayRequest): RelayResponse {
        requests += request.toString()
        return try {
            route(request)
        } catch (e: Refusal) {
            refusals.merge(e.code, 1, Int::plus)
            e.response
        }
    }

    fun entries(gameId: String): List<LogEntry> = games.getValue(gameId).entries.toList()

    fun exists(gameId: String) = gameId in games

    fun gameIds(): Set<String> = games.keys.toSet()

    /** Appends [entry] (its gameId, seq and serverTime filled in) with no check at all, and closes the log if [closes]. */
    fun tamper(gameId: String, closes: Boolean = false, entry: (seq: Long, gameId: String, now: Long) -> LogEntry) {
        val game = games.getValue(gameId)
        val e = entry(game.entries.size + 1L, gameId, now)
        game.entries += e
        game.latestPly = e.ply
        if (e.kind == "move") {
            game.lastMoveAt = now
            game.openDrawBy = null
        }
        if (closes) game.closed = true
    }

    /** Replaces stored entry [seq] with [change] of it. */
    fun rewrite(gameId: String, seq: Long, change: (LogEntry) -> LogEntry) {
        val list = games.getValue(gameId).entries
        list[(seq - 1).toInt()] = change(list[(seq - 1).toInt()])
    }

    /** Deletes the Game, as the retention alarm does. */
    fun delete(gameId: String) {
        games.remove(gameId)
    }

    // ---- Routing (index.ts) ------------------------------------------------------------------

    private fun route(request: RelayRequest): RelayResponse {
        val path = request.path.substringBefore('?')
        val query = request.path.substringAfter('?', "")
        if (path == "/health") {
            if (request.method != "GET") fail("method_not_allowed")
            return ok(buildJsonObject { put("status", "ok"); put("protocol", "1.0"); put("majors", JsonArray(majors.map { JsonPrimitive(it) })) })
        }
        val versioned = Regex("""^/v(\d+)(/.*)$""").matchEntire(path) ?: fail("not_found")
        val major = versioned.groupValues[1].toInt()
        if (major !in majors) fail("unsupported_version", extra = mapOf("majors" to JsonArray(majors.map { JsonPrimitive(it) })))
        val rest = versioned.groupValues[2]
        fun methods(vararg allowed: String) { if (request.method !in allowed) fail("method_not_allowed") }
        Regex("^/games$").matchEntire(rest)?.let { methods("POST"); return create(body(request), major) }
        Regex("^/invites/([^/]+)/redeem$").matchEntire(rest)?.let { methods("POST"); return redeemCode(request, major, it.groupValues[1]) }
        Regex("^/games/([^/]+)/join$").matchEntire(rest)?.let { methods("POST"); return redeemToken(body(request), major, it.groupValues[1]) }
        Regex("^/games/([^/]+)/invite$").matchEntire(rest)?.let { methods("DELETE"); return cancel(request, it.groupValues[1]) }
        Regex("^/games/([^/]+)/events$").matchEntire(rest)?.let {
            methods("GET", "POST")
            val game = it.groupValues[1]
            return if (request.method == "GET") read(game, bearer(request), since(query)) else append(game, bearer(request), body(request), major)
        }
        Regex("^/games/([^/]+)/live$").matchEntire(rest)?.let { methods("GET"); fail("upgrade_required") }
        Regex("^/sync$").matchEntire(rest)?.let { methods("POST"); return sync(body(request), major) }
        fail("not_found")
    }

    private fun body(request: RelayRequest): JsonObject {
        val text = request.body ?: ""
        if (text.toByteArray().size > Protocol.MAX_BODY_BYTES) fail("body_too_large")
        val element = runCatching { Protocol.json.parseToJsonElement(text) }.getOrNull() ?: fail("bad_request")
        return element as? JsonObject ?: fail("bad_request")
    }

    private fun bearer(request: RelayRequest): String = request.bearer ?: fail("bad_seat_secret")

    private fun since(query: String): Long {
        val raw = query.split('&').firstOrNull { it.startsWith("since=") }?.substringAfter('=') ?: return 0
        if (raw.isEmpty()) return 0
        if (!Regex("""\d{1,9}""").matches(raw)) fail("bad_request")
        return raw.toLong()
    }

    // ---- Validation (protocol.ts) ------------------------------------------------------------

    private fun checkVersion(v: JsonElement?, pathMajor: Int): String {
        val text = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (text == null || !Regex("""\d{1,4}\.\d{1,4}""").matches(text)) fail("bad_request")
        val major = text.substringBefore('.').toInt()
        if (major !in majors || major != pathMajor) fail("unsupported_version", extra = mapOf("majors" to JsonArray(majors.map { JsonPrimitive(it) })))
        return text
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.count(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 }

    private fun parseAppend(o: JsonObject, major: Int): Pending {
        val v = checkVersion(o["v"], major)
        val seq = o.count("seq")?.takeIf { it >= 1 } ?: fail("bad_request")
        val ply = o.count("ply")?.toInt() ?: fail("bad_request")
        val kind = o.string("kind")?.takeIf { EntryKind.of(it) != null } ?: fail("bad_request")
        val hash = o.string("hash")?.takeIf { Regex("[0-9a-f]{64}").matches(it) } ?: fail("bad_request")
        var uci: String? = null
        var end: Boolean? = null
        if (kind == "move") {
            uci = o.string("uci")?.takeIf { Regex("^[a-h][1-8][a-h][1-8][qrbn]?$").matches(it) } ?: fail("bad_request")
            if ("end" in o) {
                if ((o["end"] as? JsonPrimitive)?.booleanOrNull != true) fail("bad_request")
                end = true
            }
        } else if ("uci" in o || "end" in o) fail("bad_request")
        var rematch: RematchRef? = null
        if (kind == "rematchOffer") {
            val r = o["rematch"] as? JsonObject ?: fail("bad_request")
            val gameId = r.string("gameId")?.takeIf { Protocol.isGameId(it) } ?: fail("bad_request")
            val token = r.string("joinToken")?.takeIf { Protocol.isToken(it) } ?: fail("bad_request")
            rematch = RematchRef(gameId, token)
        } else if ("rematch" in o) fail("bad_request")
        return Pending(v, seq, ply, kind, uci, end, hash, rematch)
    }

    private class Pending(val v: String, val seq: Long, val ply: Int, val kind: String, val uci: String?, val end: Boolean?, val hash: String, val rematch: RematchRef?)

    // ---- The Durable Object (game.ts) --------------------------------------------------------

    private class Invite(val kind: String, val secret: String, val expiresAt: Long)

    private class Stored(
        val v: String,
        val gameId: String,
        val createdAt: Long,
        val daysPerMove: Int,
        val creatorSide: Side,
        val seats: MutableMap<Side, String?>,
        var invite: Invite?,
        var usedInvite: String? = null,
        var startedAt: Long? = null,
        var deleteAt: Long,
        val entries: MutableList<LogEntry> = mutableListOf(),
        var latestPly: Int = 0,
        var lastMoveAt: Long? = null,
        var closed: Boolean = false,
        var openDrawBy: Side? = null,
        var rematch: RematchStatus? = null,
    ) {
        fun seatOf(secret: String): Side? = seats.entries.firstOrNull { it.value == secret }?.key
        val deadline: Long get() = (lastMoveAt ?: startedAt!!) + daysPerMove * Protocol.DAY_MS
    }

    /** The Game, or null; an expired invite or a Game past its retention is deleted on sight (the alarm). */
    private fun live(gameId: String): Stored? {
        val game = games[gameId] ?: return null
        val invite = game.invite
        if ((invite != null && now >= invite.expiresAt) || now >= game.deleteAt) {
            games.remove(gameId)
            return null
        }
        return game
    }

    private fun create(o: JsonObject, major: Int): RelayResponse {
        val v = checkVersion(o["v"], major)
        val side = when (o.string("side")) { "white" -> Side.WHITE; "black" -> Side.BLACK; else -> fail("bad_request") }
        val days = o.count("daysPerMove")?.toInt()?.takeIf { it in Protocol.DAYS_PER_MOVE } ?: fail("bad_request")
        val invite = if (o["invite"] == null || o["invite"] is JsonNull) "code" else o.string("invite") ?: fail("bad_request")
        if (invite != "code" && invite != "token") fail("bad_request")
        val seat = token()
        val secret = if (invite == "code") newCode() else token()
        val gameId = hex(if (invite == "code") "invite:$secret" else "unique:${random.nextLong()}")
        val game = Stored(v, gameId, now, days, side, mutableMapOf(Side.WHITE to null, Side.BLACK to null), Invite(invite, secret, now + INVITE_TTL), deleteAt = now + INVITE_TTL)
        game.seats[side] = seat
        games[gameId] = game
        return ok(buildJsonObject {
            put("v", v); put("gameId", gameId); put("side", WireSide.name(side)); put("daysPerMove", days)
            put("inviteExpiresAt", now + INVITE_TTL); put("serverTime", now); put("seatSecret", seat)
            if (invite == "code") put("inviteCode", "${secret.take(4)}-${secret.drop(4)}") else put("joinToken", secret)
        }, 201)
    }

    private fun redeemCode(request: RelayRequest, major: Int, raw: String): RelayResponse {
        while (redemptions.isNotEmpty() && redemptions.first() <= now - 60_000) redemptions.removeFirst()
        if (redemptions.size >= REDEEM_LIMIT) return RelayResponse(429, error("rate_limited"), "60")
        redemptions.addLast(now)
        val o = body(request)
        val v = checkVersion(o["v"], major)
        val seat = seatSecret(o)
        val code = InviteCodes.normalize(raw) ?: fail("bad_request")
        return redeem(hex("invite:$code"), v, code, seat)
    }

    private fun redeemToken(o: JsonObject, major: Int, gameId: String): RelayResponse {
        val v = checkVersion(o["v"], major)
        val seat = seatSecret(o)
        val token = o.string("joinToken")?.takeIf { Protocol.isToken(it) } ?: fail("bad_request")
        if (!Protocol.isGameId(gameId)) fail("game_not_found")
        return redeem(gameId, v, token, seat)
    }

    /** The joining Seat's own secret (W9): 43 base64url characters, or `bad_request`. */
    private fun seatSecret(o: JsonObject): String = o.string("seatSecret")?.takeIf { Protocol.isToken(it) } ?: fail("bad_request")

    /** game.ts redeem: the Seat for [seat]'s secret; the same invite and secret again answer the same Seat (W9). */
    private fun redeem(gameId: String, v: String, secret: String, seat: String): RelayResponse {
        val game = live(gameId) ?: fail("invite_not_found")
        val side = game.creatorSide.opponent
        fun seated() = ok(buildJsonObject {
            put("v", game.v); put("gameId", game.gameId); put("side", WireSide.name(side)); put("daysPerMove", game.daysPerMove)
            put("startedAt", game.startedAt!!); put("serverTime", now)
        })
        val invite = game.invite
        if (invite == null) {
            if (game.usedInvite != secret) fail("invite_not_found")
            return if (game.seats[side] == seat) seated() else fail("invite_used")
        }
        if (invite.secret != secret) fail("invite_not_found")
        if (v.substringBefore('.') != game.v.substringBefore('.')) fail("version_mismatch", extra = mapOf("gameV" to JsonPrimitive(game.v)))
        if (game.seats[game.creatorSide] == seat) fail("bad_request")
        game.seats[side] = seat
        game.usedInvite = invite.secret
        game.invite = null
        game.startedAt = now
        game.deleteAt = now + RETENTION
        return seated()
    }

    private fun cancel(request: RelayRequest, gameId: String): RelayResponse {
        val secret = bearer(request)
        if (!Protocol.isGameId(gameId)) fail("game_not_found")
        val game = live(gameId) ?: fail("game_not_found")
        if (game.seats[game.creatorSide] != secret) fail("bad_seat_secret")
        if (game.invite == null) fail("invite_redeemed")
        games.remove(gameId)
        return ok(buildJsonObject { put("cancelled", true); put("gameId", gameId) })
    }

    private fun view(gameId: String, secret: String, since: Long): JsonObject {
        if (!Protocol.isGameId(gameId)) fail("game_not_found")
        val game = live(gameId) ?: fail("game_not_found")
        val side = game.seatOf(secret) ?: fail("bad_seat_secret")
        return buildJsonObject {
            put("v", game.v); put("gameId", game.gameId)
            put("status", if (game.invite != null) "waiting" else if (game.closed) "closed" else "active")
            put("side", WireSide.name(side)); put("daysPerMove", game.daysPerMove); put("createdAt", game.createdAt)
            put("startedAt", game.startedAt?.let(::JsonPrimitive) ?: JsonNull)
            put("inviteExpiresAt", game.invite?.expiresAt?.let(::JsonPrimitive) ?: JsonNull)
            put("latestSeq", game.entries.size); put("latestPly", game.latestPly)
            put("openDrawBy", game.openDrawBy?.let { JsonPrimitive(WireSide.name(it)) } ?: JsonNull)
            put("deadline", if (game.invite != null || game.closed) JsonNull else JsonPrimitive(game.deadline))
            put("rematch", game.rematch?.let { r ->
                buildJsonObject {
                    put("offeredBy", WireSide.name(r.offeredBy)); put("gameId", r.gameId)
                    put("answer", r.answer?.let(::JsonPrimitive) ?: JsonNull)
                }
            } ?: JsonNull)
            put("serverTime", now)
            put("entries", JsonArray(game.entries.filter { it.seq > since }.map { Protocol.json.encodeToJsonElement(LogEntry.serializer(), it) }))
        }
    }

    private fun read(gameId: String, secret: String, since: Long) = ok(view(gameId, secret, since))

    private fun append(gameId: String, secret: String, o: JsonObject, major: Int): RelayResponse {
        val e = parseAppend(o, major)
        if (!Protocol.isGameId(gameId)) fail("game_not_found")
        val game = live(gameId) ?: fail("game_not_found")
        val side = game.seatOf(secret) ?: fail("bad_seat_secret")
        if (game.invite != null) fail("game_not_started")
        if (e.v.substringBefore('.') != game.v.substringBefore('.')) fail("version_mismatch", extra = mapOf("gameV" to JsonPrimitive(game.v)))
        val latestSeq = game.entries.size.toLong()
        if (e.seq <= latestSeq) {
            val stored = game.entries[(e.seq - 1).toInt()]
            val same = stored.side == side && stored.kind == e.kind && stored.ply == e.ply && stored.hash == e.hash &&
                stored.uci == e.uci && stored.end == e.end && stored.rematch == e.rematch
            if (same) return ok(buildJsonObject { put("entry", Protocol.json.encodeToJsonElement(LogEntry.serializer(), stored)) })
            fail("seq_conflict", extra = mapOf("latestSeq" to JsonPrimitive(latestSeq)))
        }
        if (e.seq != latestSeq + 1) fail("seq_conflict", extra = mapOf("latestSeq" to JsonPrimitive(latestSeq)))
        if (latestSeq >= MAX_ENTRIES && e.kind in setOf("move", "drawOffer", "drawDecline")) fail("log_full")
        val expectedPly = if (e.kind == "move") game.latestPly + 1 else game.latestPly
        if (e.ply != expectedPly) fail("bad_request")
        refusal(game, side, e)?.let { fail(it.first, extra = it.second) }

        val entry = LogEntry(e.v, gameId, e.seq, e.ply, side, e.kind, e.uci, e.end, e.hash, e.rematch, now)
        game.entries += entry
        game.latestPly = e.ply
        if (e.kind == "move") game.lastMoveAt = now
        if (e.kind == "drawOffer") game.openDrawBy = side
        if (e.kind == "move" || e.kind == "drawDecline") game.openDrawBy = null
        if (e.kind in setOf("resign", "drawAccept", "claim") || e.end == true) {
            game.closed = true
            game.openDrawBy = null
        }
        when (e.kind) {
            "rematchOffer" -> game.rematch = RematchStatus(side, e.rematch!!.gameId, null)
            "rematchAccept" -> game.rematch = game.rematch!!.copy(answer = "accept")
            "rematchDecline" -> game.rematch = game.rematch!!.copy(answer = "decline")
        }
        game.deleteAt = now + RETENTION
        return ok(buildJsonObject { put("entry", Protocol.json.encodeToJsonElement(LogEntry.serializer(), entry)) }, 201)
    }

    private fun refusal(game: Stored, side: Side, e: Pending): Pair<String, Map<String, JsonElement>>? {
        fun no(code: String, extra: Map<String, JsonElement> = emptyMap()) = code to extra
        return when (e.kind) {
            "rematchOffer" -> when {
                !game.closed -> no("game_not_over")
                game.rematch != null -> no("rematch_already_offered")
                else -> null
            }
            "rematchAccept", "rematchDecline" -> when {
                game.rematch == null -> no("no_rematch_offer")
                game.rematch!!.answer != null -> no("rematch_answered")
                game.rematch!!.offeredBy == side -> no("not_your_turn")
                else -> null
            }
            else -> {
                if (game.closed) return no("game_closed")
                val toMove = if ((game.latestPly + 1) % 2 == 1) Side.WHITE else Side.BLACK
                when (e.kind) {
                    "move" -> if (side != toMove) no("not_your_turn") else null
                    "claim" -> when {
                        side == toMove -> no("not_your_turn")
                        now < game.deadline -> no("claim_too_early", mapOf("deadline" to JsonPrimitive(game.deadline)))
                        else -> null
                    }
                    "drawOffer" -> when {
                        side == toMove || game.latestPly < 1 -> no("not_your_turn")
                        game.openDrawBy != null -> no("draw_already_offered")
                        else -> null
                    }
                    "drawAccept", "drawDecline" -> if (side != toMove || game.openDrawBy != side.opponent) no("no_draw_offer") else null
                    else -> null
                }
            }
        }
    }

    private fun sync(o: JsonObject, major: Int): RelayResponse {
        checkVersion(o["v"], major)
        val items = o["games"] as? JsonArray ?: fail("bad_request")
        if (items.size !in 1..Protocol.MAX_SYNC_GAMES) fail("bad_request")
        val results = items.map { raw ->
            val item = raw as? JsonObject ?: fail("bad_request")
            val gameId = item.string("gameId") ?: fail("bad_request")
            val secret = item.string("seatSecret") ?: fail("bad_request")
            val since = if (item["since"] == null) 0 else item.count("since") ?: fail("bad_request")
            try {
                buildJsonObject { put("gameId", gameId); put("ok", true); put("game", view(gameId, secret, since)) }
            } catch (e: Refusal) {
                val error = Protocol.json.parseToJsonElement(e.response.body).jsonObject
                JsonObject(mapOf("gameId" to JsonPrimitive(gameId), "ok" to JsonPrimitive(false)) + error)
            }
        }
        return ok(buildJsonObject { put("results", JsonArray(results)) })
    }

    // ---- Secrets and replies -----------------------------------------------------------------

    private fun token(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return String(CharArray(43) { alphabet[random.nextInt(64)] })
    }

    private fun newCode(): String {
        val crockford = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        while (true) {
            val code = String(CharArray(8) { crockford[random.nextInt(32)] })
            if (hex("invite:$code") !in games) return code
        }
    }

    private fun hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private class Refusal(val code: String, val response: RelayResponse) : RuntimeException()

    private fun fail(code: String, extra: Map<String, JsonElement> = emptyMap()): Nothing =
        throw Refusal(code, RelayResponse(STATUS.getValue(code), error(code, extra)))

    private fun error(code: String, extra: Map<String, JsonElement> = emptyMap()): String =
        JsonObject(mapOf("error" to JsonObject(mapOf("code" to JsonPrimitive(code), "message" to JsonPrimitive("fake: $code")) + extra))).toString()

    private fun ok(body: JsonObject, status: Int = 200) = RelayResponse(status, body.toString())

    companion object {
        const val START = 1_790_000_000_000L
        const val INVITE_TTL = 48 * 3_600_000L
        const val RETENTION = 30 * Protocol.DAY_MS
        const val MAX_ENTRIES = 2_000
        const val REDEEM_LIMIT = 10

        val STATUS = mapOf(
            "bad_request" to 400, "unsupported_version" to 400, "bad_seat_secret" to 401, "not_your_turn" to 403,
            "not_found" to 404, "game_not_found" to 404, "invite_not_found" to 404, "method_not_allowed" to 405,
            "version_mismatch" to 409, "invite_used" to 409, "invite_redeemed" to 409, "game_not_started" to 409,
            "seq_conflict" to 409, "game_closed" to 409, "rematch_already_offered" to 409, "no_rematch_offer" to 409,
            "rematch_answered" to 409, "claim_too_early" to 409, "draw_already_offered" to 409, "no_draw_offer" to 409,
            "game_not_over" to 409, "log_full" to 409, "body_too_large" to 413, "upgrade_required" to 426, "rate_limited" to 429,
        )
    }
}

/** What can go wrong between a phone and the Relay, injected per request. */
class Weather(
    val offline: Double = 0.0,
    val lostResponse: Double = 0.0,
    val duplicate: Double = 0.0,
    val restart: Double = 0.0,
)

/**
 * A transport to [relay] through bad [weather]: a request that never arrives (offline), a response
 * that never comes back after the Relay acted (lost), a request delivered twice (duplicate), and a
 * Relay restart that answers 503 without acting. [beforeDelivery] runs just before a request reaches
 * the Relay, so a test can interleave the other phone's actions there.
 */
class FlakyTransport(
    private val relay: FakeRelay,
    private val random: Random,
    var weather: Weather = Weather(),
    var beforeDelivery: (suspend (RelayRequest) -> Unit)? = null,
) : RelayTransport {
    var delivered = 0
        private set

    override suspend fun exchange(request: RelayRequest): RelayResponse {
        val w = weather
        if (random.nextDouble() < w.offline) throw IOException("offline")
        if (random.nextDouble() < w.restart) return RelayResponse(503, "<html>restarting</html>")
        beforeDelivery?.invoke(request)
        delivered++
        var response = relay.handle(request)
        if (random.nextDouble() < w.duplicate) response = relay.handle(request)
        if (random.nextDouble() < w.lostResponse) throw IOException("the response was lost")
        return response
    }
}
