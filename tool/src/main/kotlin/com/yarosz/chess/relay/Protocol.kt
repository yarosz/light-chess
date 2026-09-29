package com.yarosz.chess.relay

import com.yarosz.chess.rules.Side
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * The Relay's wire protocol, version 1.0 (docs/protocol.md, ADR 0002): constants, every request and
 * response body, and the error codes. Pure Kotlin, no network: [RelayClient] sends these through a
 * [RelayTransport].
 *
 * Versions (E9, R6): the path carries the major (`/v1/`), every body that the phone sends carries
 * [VERSION], and a Game is pinned to the version it was created with. A later minor only adds
 * optional fields, kinds and endpoints, so [json] ignores fields it doesn't know; an unknown kind in
 * a Game's log is the Correspondence Game's question (it stops as "Update Chess").
 */
object Protocol {
    const val VERSION = "1.0"
    const val MAJOR = 1

    /** A batched sync reads at most this many Games (E7). */
    const val MAX_SYNC_GAMES = 5

    /** A request body is at most this many bytes (R5); the phone's bodies are far below it. */
    const val MAX_BODY_BYTES = 4_096

    const val DAY_MS = 86_400_000L

    /** Days per Move a Game may have (C2); 3 is the default. */
    val DAYS_PER_MOVE = listOf(1, 3, 7)
    const val DEFAULT_DAYS_PER_MOVE = 3

    /** A Game's id as the Relay hands it out: 64 lowercase hex characters. */
    fun isGameId(text: String): Boolean = GAME_ID.matches(text)

    /** A seat secret or a join token: 32 bytes as unpadded base64url, 43 characters. */
    fun isToken(text: String): Boolean = TOKEN.matches(text)

    /** A digest (`hash`): the rules core's Position.digest, 64 lowercase hex characters. */
    fun isDigest(text: String): Boolean = GAME_ID.matches(text)

    /** The major of a `major.minor` version, or null when [v] isn't one. */
    fun majorOf(v: String): Int? = VERSION_TEXT.matchEntire(v)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * The codec for every body: unknown fields ignored, absent optional fields left out (the Relay
     * refuses a `null` where a field must be absent, such as `uci` on a `resign`).
     */
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val VERSION_TEXT = Regex("""(\d{1,4})\.\d{1,4}""")
    private val GAME_ID = Regex("[0-9a-f]{64}")
    private val TOKEN = Regex("[A-Za-z0-9_-]{43}")
}

/**
 * [Side] on the wire: `"white"` or `"black"`. Anything else fails the body, so a response the phone
 * can't read is never half read.
 */
object WireSide : KSerializer<Side> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.yarosz.chess.relay.WireSide", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Side) = encoder.encodeString(name(value))

    override fun deserialize(decoder: Decoder): Side = when (val text = decoder.decodeString()) {
        "white" -> Side.WHITE
        "black" -> Side.BLACK
        else -> throw SerializationException("unknown side \"$text\"")
    }

    fun name(side: Side): String = if (side == Side.WHITE) "white" else "black"
}

/** The kinds of entry in protocol 1.0 (C4, E8, contradiction 9). A later minor may add kinds. */
enum class EntryKind(val wire: String) {
    MOVE("move"),
    RESIGN("resign"),
    DRAW_OFFER("drawOffer"),
    DRAW_ACCEPT("drawAccept"),
    DRAW_DECLINE("drawDecline"),
    CLAIM("claim"),
    REMATCH_OFFER("rematchOffer"),
    REMATCH_ACCEPT("rematchAccept"),
    REMATCH_DECLINE("rematchDecline"),
    ;

    val isRematch: Boolean get() = this == REMATCH_OFFER || this == REMATCH_ACCEPT || this == REMATCH_DECLINE

    companion object {
        /** The kind named [wire], or null for a kind this version doesn't know. */
        fun of(wire: String): EntryKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** A rematch offer's new Game, carried in the old Game's log (E8). The join token takes its other Seat. */
@Serializable
data class RematchRef(val gameId: String, val joinToken: String)

/**
 * One entry of a Game's log, as the Relay returns it: one Game Event, or a rematch entry once the
 * log is closed. [kind] stays text so that a kind from a later minor still reads; [entryKind] is
 * null for one. [end] is `true` or absent (R2).
 */
@Serializable
data class LogEntry(
    val v: String,
    val gameId: String,
    val seq: Long,
    val ply: Int,
    @Serializable(with = WireSide::class) val side: Side,
    val kind: String,
    val uci: String? = null,
    val end: Boolean? = null,
    val hash: String,
    val rematch: RematchRef? = null,
    val serverTime: Long,
) {
    val entryKind: EntryKind? get() = EntryKind.of(kind)

    /** Whether this stored entry is the one [append] asked for: what the Relay's idempotent retry compares. */
    fun matches(append: Append, side: Side): Boolean =
        seq == append.seq && this.side == side && kind == append.kind && ply == append.ply &&
            uci == append.uci && (end == true) == (append.end == true) && hash == append.hash && rematch == append.rematch
}

/** `POST /v1/games/{gameId}/events`: an entry without the fields the Relay stamps (gameId, side, serverTime). */
@Serializable
data class Append(
    val v: String = Protocol.VERSION,
    val seq: Long,
    val ply: Int,
    val kind: String,
    val uci: String? = null,
    val end: Boolean? = null,
    val hash: String,
    val rematch: RematchRef? = null,
) {
    init {
        require(end == null || end) { "end is true or absent (R2)" }
    }

    val entryKind: EntryKind? get() = EntryKind.of(kind)
}

@Serializable
data class AppendReply(val entry: LogEntry)

/** How the other Seat of a new Game is passed: an Invite Code by hand, or a join token through a rematch offer. */
enum class InviteKind(val wire: String) { CODE("code"), TOKEN("token") }

/** `POST /v1/games`. */
@Serializable
data class CreateRequest(
    val v: String = Protocol.VERSION,
    @Serializable(with = WireSide::class) val side: Side,
    val daysPerMove: Int,
    val invite: String,
)

/** `201` from `POST /v1/games`: the creator's Seat, and exactly one of [inviteCode] or [joinToken]. */
@Serializable
data class Created(
    val v: String,
    val gameId: String,
    @Serializable(with = WireSide::class) val side: Side,
    val daysPerMove: Int,
    val seatSecret: String,
    val inviteCode: String? = null,
    val joinToken: String? = null,
    val inviteExpiresAt: Long,
    val serverTime: Long,
)

/** `POST /v1/invites/{code}/redeem`, with the seat secret this phone chose (W9). */
@Serializable
data class RedeemRequest(val v: String = Protocol.VERSION, val seatSecret: String) {
    override fun toString() = "RedeemRequest($v)"
}

/** `POST /v1/games/{gameId}/join`, with the seat secret this phone chose (W9). */
@Serializable
data class JoinRequest(val v: String = Protocol.VERSION, val seatSecret: String, val joinToken: String) {
    override fun toString() = "JoinRequest($v)"
}

/**
 * `200` from redeem or join: the other Seat. [v] is the Game's pinned version; [startedAt] starts
 * White's clock. The Relay doesn't send the secret back (the phone chose it): [seatSecret] is the one
 * [RelayClient] sent, filled in by the client.
 */
@Serializable
data class Redeemed(
    val v: String,
    val gameId: String,
    @Serializable(with = WireSide::class) val side: Side,
    val daysPerMove: Int,
    val startedAt: Long,
    val serverTime: Long,
    val seatSecret: String = "",
) {
    override fun toString() = "Redeemed($gameId, $side)"
}

/**
 * The joining Seat's secret (W9, C3): 32 random bytes from the platform's secure generator, as unpadded
 * base64url, chosen by the phone and written to its file before the redeem or join is sent, so a
 * retry after a lost response sends the same one and gets the same Seat back.
 */
object SeatSecrets {
    private val random = SecureRandom()

    fun fresh(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/** `200` from `DELETE /v1/games/{gameId}/invite`. */
@Serializable
data class Cancelled(val cancelled: Boolean, val gameId: String)

/** The rematch bookkeeping of `GET events`: who offered, the new Game, and the answer once given. */
@Serializable
data class RematchStatus(
    @Serializable(with = WireSide::class) val offeredBy: Side,
    val gameId: String,
    val answer: String? = null,
)

/** A Game as one of its Seats sees it (`GET events` and each `/sync` result). */
@Serializable
data class GameView(
    val v: String,
    val gameId: String,
    /** `waiting`, `active` or `closed`. */
    val status: String,
    @Serializable(with = WireSide::class) val side: Side,
    val daysPerMove: Int,
    val createdAt: Long,
    val startedAt: Long? = null,
    val inviteExpiresAt: Long? = null,
    val latestSeq: Long,
    val latestPly: Int,
    @Serializable(with = WireSide::class) val openDrawBy: Side? = null,
    val deadline: Long? = null,
    val rematch: RematchStatus? = null,
    val serverTime: Long,
    val entries: List<LogEntry> = emptyList(),
) {
    val waiting: Boolean get() = status == "waiting"
}

/** `POST /v1/sync`: one Game to read, with its own Seat's secret. */
@Serializable
data class SyncItem(val gameId: String, val seatSecret: String, val since: Long) {
    override fun toString() = "SyncItem($gameId, since=$since)"
}

@Serializable
data class SyncRequest(val v: String = Protocol.VERSION, val games: List<SyncItem>)

/** One Game's answer in a batch: [game] when [ok], else [error]. */
@Serializable
data class SyncResult(val gameId: String, val ok: Boolean, val game: GameView? = null, val error: ErrorDetail? = null)

@Serializable
data class SyncReply(val results: List<SyncResult>)

/** `GET /health`. */
@Serializable
data class Health(val status: String, val protocol: String, val majors: List<Int>)

/** The one error shape; the fields after [message] come with some codes only. */
@Serializable
data class ErrorDetail(
    val code: String,
    val message: String = "",
    val latestSeq: Long? = null,
    val deadline: Long? = null,
    val gameV: String? = null,
    val majors: List<Int>? = null,
)

@Serializable
data class ErrorBody(val error: ErrorDetail)

/** The Relay's error codes (docs/protocol.md "Errors"). Phones branch on these only; [UNKNOWN] is one from a later version. */
enum class ErrorCode(val wire: String) {
    BAD_REQUEST("bad_request"),
    UNSUPPORTED_VERSION("unsupported_version"),
    BAD_SEAT_SECRET("bad_seat_secret"),
    NOT_YOUR_TURN("not_your_turn"),
    NOT_FOUND("not_found"),
    GAME_NOT_FOUND("game_not_found"),
    INVITE_NOT_FOUND("invite_not_found"),
    METHOD_NOT_ALLOWED("method_not_allowed"),
    VERSION_MISMATCH("version_mismatch"),
    INVITE_USED("invite_used"),
    INVITE_REDEEMED("invite_redeemed"),
    GAME_NOT_STARTED("game_not_started"),
    SEQ_CONFLICT("seq_conflict"),
    GAME_CLOSED("game_closed"),
    REMATCH_ALREADY_OFFERED("rematch_already_offered"),
    NO_REMATCH_OFFER("no_rematch_offer"),
    REMATCH_ANSWERED("rematch_answered"),
    CLAIM_TOO_EARLY("claim_too_early"),
    DRAW_ALREADY_OFFERED("draw_already_offered"),
    NO_DRAW_OFFER("no_draw_offer"),
    GAME_NOT_OVER("game_not_over"),
    LOG_FULL("log_full"),
    BODY_TOO_LARGE("body_too_large"),
    UPGRADE_REQUIRED("upgrade_required"),
    RATE_LIMITED("rate_limited"),
    UNKNOWN(""),
    ;

    companion object {
        fun of(wire: String): ErrorCode = entries.firstOrNull { it.wire == wire && it != UNKNOWN } ?: UNKNOWN
    }
}

/** A refusal from the Relay: its HTTP [status], the [code] and the fields that come with it. */
data class RelayError(val status: Int, val detail: ErrorDetail, val retryAfterSeconds: Long? = null) {
    val code: ErrorCode get() = ErrorCode.of(detail.code)

    /** `unsupported_version` or `version_mismatch`, on any endpoint: "Update Chess to continue this game" (E9, R6). */
    val needsUpdate: Boolean get() = code == ErrorCode.UNSUPPORTED_VERSION || code == ErrorCode.VERSION_MISMATCH
}

/**
 * Invite Codes (C3): 8 characters of Crockford base32, shown as `ABCD-EFGH`. The phone reads a typed
 * code as the Relay does, so a code that can't be one is refused before it costs a redemption
 * against the rate limit (F11).
 */
object InviteCodes {
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** The canonical 8 characters of [typed] (any case, `-` and spaces ignored, O as 0, I and L as 1), or null. */
    fun normalize(typed: String): String? {
        val code = typed.uppercase().filterNot { it == '-' || it.isWhitespace() }
            .replace('O', '0').replace('I', '1').replace('L', '1')
        return code.takeIf { it.length == 8 && it.all { c -> c in CROCKFORD } }
    }

    /** `ABCDEFGH` as `ABCD-EFGH`. */
    fun display(code: String): String = normalize(code)?.let { "${it.take(4)}-${it.drop(4)}" } ?: code
}
