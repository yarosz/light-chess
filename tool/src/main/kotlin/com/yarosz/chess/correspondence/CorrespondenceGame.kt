package com.yarosz.chess.correspondence

import com.yarosz.chess.relay.Append
import com.yarosz.chess.relay.LogEntry
import com.yarosz.chess.relay.WireSide
import com.yarosz.chess.rules.Side
import kotlinx.serialization.Serializable

/**
 * This phone's Seat in a Correspondence Game: its Side and the seat secret the Relay returned once
 * (C3). The secret never leaves [CorrespondenceStore]'s file except as the bearer of a request to
 * the Relay, and there is no recovery (E6). toString leaves it out.
 */
@Serializable
data class Seat(@Serializable(with = WireSide::class) val side: Side, val secret: String) {
    override fun toString() = "Seat($side)"
}

/** The creator's open invite: its Invite Code (none for a rematch's join token) and when it expires (C3, E8). */
@Serializable
data class OpenInvite(
    val code: String? = null,
    val expiresAt: Long,
    /** A cancel was asked for and the Relay hasn't confirmed it; the slot stays taken until it does (G2). */
    val cancelling: Boolean = false,
)

/** Why a Correspondence Game stopped: nothing is sent or accepted for it until the reason goes away. */
enum class HaltReason {
    /** An entry the rules core or the protocol refuses, or the Relay disagrees with this phone's log (C5). */
    OUT_OF_SYNC,

    /** The Game or the Relay uses a protocol this version doesn't know: "Update Chess to continue this game" (E9, R6). */
    NEEDS_UPDATE,

    /** The Relay no longer has the Game (deleted 30 days after its last entry, C6). */
    GONE,

    /** The Relay refuses this phone's seat secret (E6: there is no recovery). */
    SEAT_LOST,
}

@Serializable
data class Halt(val reason: HaltReason, val detail: String = "")

/** Where a Correspondence Game is: waiting for its invite to be redeemed, being played, or over. */
enum class Stage { WAITING, ACTIVE, OVER }

/**
 * One Correspondence Game as this phone holds it (CONTEXT.md): its [seat], its log of checked
 * entries, and the one entry of the user's that is written but not yet confirmed by the Relay
 * ([pending], C8). Everything else, the Position and the Result included, follows from replaying
 * [entries] through the rules core ([log]).
 *
 * [v] is the version the Game is pinned to (E9). [label] is the local name for the opponent, the
 * first four characters of the Invite Code by default, never sent (F11).
 */
@Serializable
data class CorrespondenceGame(
    val gameId: String,
    val seat: Seat,
    val v: String,
    val daysPerMove: Int,
    val label: String,
    /** The creator's open invite; null once redeemed, and always null for the Seat that joined. */
    val invite: OpenInvite? = null,
    /** Null while waiting for the invite to be redeemed. */
    val startedAt: Long? = null,
    /** The checked log, entries 1 to n in order. */
    val entries: List<LogEntry> = emptyList(),
    /** The user's entry, saved before it is sent: shown as "waiting to send" (C8). */
    val pending: Append? = null,
    /** The user's latest entry that the Relay's log made meaningless before it was stored (C8), for the screen to say so. */
    val rolledBack: Append? = null,
    val halt: Halt? = null,
    /** The newest serverTime the Relay reported about this Game. */
    val serverTime: Long = 0,
    /** When the log closed, in the Relay's time; a closed Game is polled for a rematch offer for a while after (decision log "v3 PR 1"). */
    val closedAt: Long? = null,
    /** The Game this one is a rematch of. */
    val rematchOf: String? = null,
    /**
     * "Send and offer draw" (W2): a draw offer held on the phone, not a second pending entry (V8). It is
     * drafted and sent once the pending Move is stored, or dropped as "Offer not sent" when the
     * opponent has moved by then.
     */
    val drawOfferHeld: Boolean = false,
) {
    /** [entries] replayed through the rules core; null while waiting, or if a stored log no longer replays. */
    val log: GameLog? by lazy {
        val started = startedAt ?: return@lazy null
        (GameLog.replay(gameId, started, daysPerMove, entries) as? Verdict.Accepted)?.log
    }

    val stage: Stage
        get() = when {
            startedAt == null -> Stage.WAITING
            log?.closed == true -> Stage.OVER
            else -> Stage.ACTIVE
        }

    /** Whether the user is to move: the Game is played, nothing is waiting to send, and nothing stopped it. */
    val yourMove: Boolean
        get() = halt == null && pending == null && stage == Stage.ACTIVE && log?.game?.position?.sideToMove == seat.side

    /**
     * Whether the Game waits on the other Seat: its invite, its Move, or its answer to the user's
     * rematch offer. A background poll runs only while some Game does (C2).
     */
    val waitingOnOpponent: Boolean
        get() = halt == null && when (stage) {
            Stage.WAITING -> invite?.cancelling != true
            Stage.ACTIVE -> log?.game?.position?.sideToMove != seat.side
            Stage.OVER -> log?.rematch?.let { it.offeredBy == seat.side && it.accepted == null } == true
        }

    /** Counts toward the cap of five (E7, F9): an open invite (a rematch offer's included) or a Game being played. */
    val takesASlot: Boolean get() = stage != Stage.OVER && halt?.reason != HaltReason.GONE

    /**
     * Stopped (CONTEXT.md): nothing more is sent or accepted for this Game, for [halt]'s reason (Out of
     * Sync, Update Chess, deleted, Seat lost).
     */
    val stopped: Boolean get() = halt != null

    override fun toString() = "CorrespondenceGame($gameId, $seat, $stage, ${entries.size} entries, pending=${pending?.kind}, halt=${halt?.reason})"
}

/**
 * A Seat this phone is taking but the Relay hasn't confirmed (W9): the redeem of an Invite Code, or
 * the join of a rematch Game ([gameId] and [joinToken], for the Game [rematchOf]). [secret] is the
 * seat secret the phone chose, saved before the request is sent, so every retry sends the same one
 * and a lost response never loses the Seat. It holds a slot of the five (E7) until the Relay answers.
 */
@Serializable
data class PendingSeat(
    val secret: String,
    val label: String,
    /** The Invite Code, canonical, for a redeem. */
    val code: String? = null,
    val gameId: String? = null,
    val joinToken: String? = null,
    val rematchOf: String? = null,
) {
    override fun toString() = "PendingSeat(${code ?: gameId})"
}
