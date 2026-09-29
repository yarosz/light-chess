package com.yarosz.chess.relay

import com.yarosz.chess.rules.Side
import java.io.IOException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/** What one call to the Relay came to. */
sealed interface RelayReply<out T> {
    /** The Relay answered with [value]; [status] tells a new entry (201) from a matching retry (200). */
    data class Ok<T>(val value: T, val status: Int) : RelayReply<T>

    /** The Relay refused, with one of the protocol's error codes. */
    data class Refused(val error: RelayError) : RelayReply<Nothing>

    /**
     * No answer the phone can use: no response at all (offline, a timeout, a lost response), a 5xx
     * (a Relay restart or an outage), or a body that isn't protocol 1.0. The request may or may not
     * have been acted on; try again later.
     */
    data class Unreachable(val why: String) : RelayReply<Nothing>
}

/**
 * The Relay's endpoints (docs/protocol.md "Endpoints"), typed. Every call returns a [RelayReply] and
 * never throws for anything the network or the Relay does. The live WebSocket (`/live`) is not here:
 * it comes with the v3 game screen.
 */
class RelayClient(private val transport: RelayTransport) {

    suspend fun health(): RelayReply<Health> = call(RelayRequest("GET", "/health"), Health.serializer())

    /** Creates a Game with the creator's [side] and [daysPerMove] (C2, C3), passed by an Invite Code or a join token. */
    suspend fun create(side: Side, daysPerMove: Int, invite: InviteKind): RelayReply<Created> =
        post("/v$MAJOR/games", null, CreateRequest(side = side, daysPerMove = daysPerMove, invite = invite.wire), CreateRequest.serializer(), Created.serializer())
            .checked { Protocol.isGameId(it.gameId) && Protocol.isToken(it.seatSecret) && it.side == side &&
                if (invite == InviteKind.CODE) it.inviteCode?.let(InviteCodes::normalize) != null else it.joinToken?.let(Protocol::isToken) == true }

    /**
     * Takes the other Seat with an Invite Code, already [InviteCodes.normalize]d, and [seatSecret], the
     * secret this phone chose (W9). Sent again with the same secret, it answers the same Seat.
     */
    suspend fun redeem(code: String, seatSecret: String = SeatSecrets.fresh()): RelayReply<Redeemed> =
        post("/v$MAJOR/invites/${requireNotNull(InviteCodes.normalize(code))}/redeem", null, RedeemRequest(seatSecret = secret(seatSecret)), RedeemRequest.serializer(), Redeemed.serializer())
            .checked(::seated).withSecret(seatSecret)

    /** Takes the other Seat of a rematch Game with its join token (E8) and [seatSecret], as [redeem] does. */
    suspend fun join(gameId: String, joinToken: String, seatSecret: String = SeatSecrets.fresh()): RelayReply<Redeemed> =
        post("/v$MAJOR/games/${id(gameId)}/join", null, JoinRequest(seatSecret = secret(seatSecret), joinToken = joinToken), JoinRequest.serializer(), Redeemed.serializer())
            .checked { seated(it) && it.gameId == gameId }.withSecret(seatSecret)

    /** Cancels the creator's unredeemed invite, which deletes the Game (G2). */
    suspend fun cancelInvite(gameId: String, seatSecret: String): RelayReply<Cancelled> =
        call(RelayRequest("DELETE", "/v$MAJOR/games/${id(gameId)}/invite", seatSecret), Cancelled.serializer())

    /** The Game as [seatSecret]'s Seat sees it, with the entries after [since]. */
    suspend fun events(gameId: String, seatSecret: String, since: Long): RelayReply<GameView> =
        call(RelayRequest("GET", "/v$MAJOR/games/${id(gameId)}/events?since=$since", seatSecret), GameView.serializer())

    /** Appends one entry, compare-and-swap on its seq; a repeat of a stored entry answers 200 with it (C8). */
    suspend fun append(gameId: String, seatSecret: String, append: Append): RelayReply<LogEntry> =
        when (val reply = post("/v$MAJOR/games/${id(gameId)}/events", seatSecret, append, Append.serializer(), AppendReply.serializer())) {
            is RelayReply.Ok -> RelayReply.Ok(reply.value.entry, reply.status)
            is RelayReply.Refused -> reply
            is RelayReply.Unreachable -> reply
        }

    /** Reads up to [Protocol.MAX_SYNC_GAMES] Games at once, in request order (E7). */
    suspend fun sync(items: List<SyncItem>): RelayReply<List<SyncResult>> {
        require(items.size in 1..Protocol.MAX_SYNC_GAMES) { "a sync reads 1 to ${Protocol.MAX_SYNC_GAMES} Games" }
        return when (val reply = post("/v$MAJOR/sync", null, SyncRequest(games = items), SyncRequest.serializer(), SyncReply.serializer())) {
            is RelayReply.Ok -> RelayReply.Ok(reply.value.results, reply.status)
            is RelayReply.Refused -> reply
            is RelayReply.Unreachable -> reply
        }
    }

    private fun seated(r: Redeemed) = Protocol.isGameId(r.gameId)

    private fun secret(seatSecret: String): String = seatSecret.also { require(Protocol.isToken(it)) { "a seat secret is 43 base64url characters" } }

    private fun RelayReply<Redeemed>.withSecret(seatSecret: String): RelayReply<Redeemed> =
        if (this is RelayReply.Ok) copy(value = value.copy(seatSecret = seatSecret)) else this

    /** A Game id goes into a path only as the Relay handed it out. */
    private fun id(gameId: String): String = gameId.also { require(Protocol.isGameId(it)) { "not a Game id" } }

    /** An Ok reply whose value fails [valid] is no answer the phone can use. */
    private inline fun <T> RelayReply<T>.checked(valid: (T) -> Boolean): RelayReply<T> =
        if (this is RelayReply.Ok && !valid(value)) RelayReply.Unreachable("a reply that isn't protocol ${Protocol.VERSION}") else this

    private suspend fun <B, T> post(path: String, bearer: String?, body: B, bodySerializer: KSerializer<B>, serializer: KSerializer<T>): RelayReply<T> {
        val text = Protocol.json.encodeToString(bodySerializer, body)
        check(text.length <= Protocol.MAX_BODY_BYTES) { "a request body is at most ${Protocol.MAX_BODY_BYTES} bytes" }
        return call(RelayRequest("POST", path, bearer, text), serializer)
    }

    private suspend fun <T> call(request: RelayRequest, serializer: KSerializer<T>): RelayReply<T> {
        val response = try {
            transport.exchange(request)
        } catch (e: IOException) {
            return RelayReply.Unreachable("no response: ${e.message ?: "I/O error"}")
        }
        return decode(response, serializer)
    }

    companion object {
        private const val MAJOR = Protocol.MAJOR

        /** A response as a [RelayReply]: a 2xx body read as [serializer], a 4xx as the error shape, anything else unreachable. */
        fun <T> decode(response: RelayResponse, serializer: KSerializer<T>): RelayReply<T> = try {
            when (response.status) {
                in 200..299 -> RelayReply.Ok(Protocol.json.decodeFromString(serializer, response.body), response.status)
                in 400..499 -> {
                    val detail = Protocol.json.decodeFromString(ErrorBody.serializer(), response.body).error
                    RelayReply.Refused(RelayError(response.status, detail, response.retryAfter?.trim()?.toLongOrNull()))
                }
                else -> RelayReply.Unreachable("status ${response.status}")
            }
        } catch (e: SerializationException) {
            RelayReply.Unreachable("status ${response.status} with a body that isn't protocol ${Protocol.VERSION}")
        } catch (e: IllegalArgumentException) {
            RelayReply.Unreachable("status ${response.status} with a body that isn't protocol ${Protocol.VERSION}")
        }
    }
}
