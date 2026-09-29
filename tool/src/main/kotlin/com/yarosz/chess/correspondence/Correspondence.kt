package com.yarosz.chess.correspondence

import com.yarosz.chess.relay.Append
import com.yarosz.chess.relay.EntryKind
import com.yarosz.chess.relay.ErrorCode
import com.yarosz.chess.relay.GameView
import com.yarosz.chess.relay.InviteCodes
import com.yarosz.chess.relay.InviteKind
import com.yarosz.chess.relay.LogEntry
import com.yarosz.chess.relay.Protocol
import com.yarosz.chess.relay.RelayClient
import com.yarosz.chess.relay.RelayError
import com.yarosz.chess.relay.RelayReply
import com.yarosz.chess.relay.Redeemed
import com.yarosz.chess.relay.RematchRef
import com.yarosz.chess.relay.SeatSecrets
import com.yarosz.chess.relay.SyncItem
import com.yarosz.chess.rules.Move
import com.yarosz.chess.rules.Side
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What happened to one of the user's requests. */
sealed interface Delivery {
    /** The Relay confirmed it. [game] is the Game now, or null when its row went away (a cancelled invite). */
    data class Done(val game: CorrespondenceGame?) : Delivery

    /** Saved on the phone, not yet confirmed: "waiting to send" (C8). [Correspondence.syncAll] sends it. */
    data class Queued(val game: CorrespondenceGame) : Delivery

    /** Nothing changed on the Relay, for [reason]. */
    data class Refused(val reason: Refusal, val game: CorrespondenceGame? = null) : Delivery
}

enum class Refusal {
    /** The rules or the protocol don't allow it now; the phone didn't send it. */
    NOT_ALLOWED,

    /** Five Games already, open invites and outgoing rematch offers included (E7, F9): "Finish a game first". */
    CAP_REACHED,
    NO_SUCH_GAME,

    /** The Game is stopped ([CorrespondenceGame.halt]). */
    HALTED,

    /** The text can't be an Invite Code. */
    BAD_CODE,
    INVITE_NOT_FOUND,
    INVITE_USED,

    /** A cancel came too late: the friend already took the Seat, and the row is now the Game (G2). */
    ALREADY_REDEEMED,
    RATE_LIMITED,

    /** "Update Chess to continue this game" (E9, R6). */
    NEEDS_UPDATE,

    /** It needs the Relay's answer and none came. Nothing is queued; try again. */
    OFFLINE,

    /** The log moved on before the entry was stored, and it no longer made sense (C8); [CorrespondenceGame.rolledBack] holds it. */
    ROLLED_BACK,

    /** A timeout claim before the deadline, by the phone's estimate or the Relay's clock. */
    TOO_EARLY,

    /** The phone couldn't write its file, so nothing was sent. */
    NOT_SAVED,
}

/**
 * What [Correspondence.syncAll] did, for the background job that runs it and the home screen
 * (C2, D6). [changed] lists the Games whose state changed.
 */
data class SyncReport(
    val changed: List<String>,
    /** An entry is still waiting to send, or the Relay didn't answer: the job asks to run again (LightJobResult.Retry). */
    val retry: Boolean,
    /** Some Game waits on the other Seat: keep the periodic poll scheduled (C2). */
    val waitingOnOpponent: Boolean,
    /** "Your move: N" (C2, D6). */
    val yourMove: Int,
)

/**
 * The phone's side of every Correspondence Game (ADR 0002, docs/protocol.md "What the phone does
 * with this"): invites, the user's entries, and syncing with the Relay. Pure Kotlin over a
 * [RelayClient] and a [CorrespondenceStore]; no UI, no Android.
 *
 * - Every change is saved before anything is sent (C8), so a kill at any moment leaves either the
 *   old state or the new one with its entry pending, and a pending entry is re-sent as is: the
 *   Relay's idempotent retry answers a repeat of a stored entry with that entry.
 * - Every entry that arrives, and every one the user makes, is checked by [GameLog] with the rules
 *   core. One the core or the protocol refuses stops the Game as "Out of sync" (C5) and is never
 *   added to the log; the phone derives every Result itself, timeouts included.
 * - One pending entry per Game (C8). If the log moved on before it was stored (`seq_conflict`, or
 *   a refusal whose cause a fresh read shows), the phone replays the new entries and drafts the
 *   entry again at the new place, or rolls it back when it no longer makes sense. A pending Move is
 *   also rolled back when the other Seat offered a draw in the meantime, so the user sees the offer
 *   before their Move declines it (decision log "v3 PR 1").
 * - Every operation holds one lock, so the screen and a background sync never interleave. Hold one
 *   instance per process over one file (like EngineHost.shared).
 *
 * [clock] is this phone's clock. The phone's only use of it is to estimate the Relay's time (from
 * the offset seen at the last response) for the claim button and time left; the Relay's serverTime
 * stamps decide every timeout (C2).
 */
class Correspondence(
    private val relay: RelayClient,
    private val store: CorrespondenceStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    @Volatile
    private var data: CorrespondenceData = store.load() ?: CorrespondenceData()

    val games: List<CorrespondenceGame> get() = data.games

    /** Seats being taken that the Relay hasn't confirmed yet (W9). */
    val seats: List<PendingSeat> get() = data.seats

    fun game(gameId: String): CorrespondenceGame? = data.game(gameId)

    /** The phone's estimate of the Relay's clock now. */
    fun serverNow(): Long = clock() + data.clockOffset

    // ---- Invites (C3, G2) --------------------------------------------------------------------

    /** A new Game with the user on [side], passed to a friend by Invite Code. Needs the Relay. */
    suspend fun createInvite(side: Side, daysPerMove: Int = Protocol.DEFAULT_DAYS_PER_MOVE): Delivery = locked {
        require(daysPerMove in Protocol.DAYS_PER_MOVE) { "days per Move is 1, 3 or 7" }
        if (slotsFull()) return@locked Delivery.Refused(Refusal.CAP_REACHED)
        when (val reply = relay.create(side, daysPerMove, InviteKind.CODE)) {
            is RelayReply.Ok -> {
                val created = reply.value
                heard(created.serverTime)
                val code = checkNotNull(created.inviteCode?.let(InviteCodes::normalize))
                val game = CorrespondenceGame(
                    created.gameId, Seat(created.side, created.seatSecret), created.v, created.daysPerMove,
                    label = code.take(LABEL_LENGTH), invite = OpenInvite(code, created.inviteExpiresAt), serverTime = created.serverTime,
                )
                save(data.with(game))
                Delivery.Done(game)
            }
            is RelayReply.Refused -> Delivery.Refused(refusal(reply.error))
            is RelayReply.Unreachable -> Delivery.Refused(Refusal.OFFLINE)
        }
    }

    /**
     * Takes the other Seat with a friend's Invite Code, as typed. Needs the Relay. The seat secret is
     * this phone's own (W9), saved before the request: with no answer the redeem is kept as a
     * [PendingSeat], and the next try (the user's, or [syncAll]'s) sends the same secret, which the
     * Relay answers with the same Seat if it had already given it.
     */
    suspend fun redeemInvite(typed: String): Delivery = locked {
        val code = InviteCodes.normalize(typed) ?: return@locked Delivery.Refused(Refusal.BAD_CODE)
        if (data.games.any { it.invite?.code == code }) return@locked Delivery.Refused(Refusal.NOT_ALLOWED)
        val seat = data.seats.firstOrNull { it.code == code } ?: run {
            if (slotsFull()) return@locked Delivery.Refused(Refusal.CAP_REACHED)
            PendingSeat(SeatSecrets.fresh(), label = code.take(LABEL_LENGTH), code = code).also { save(data.withSeat(it)) }
        }
        takeSeat(seat)
    }

    /**
     * Cancels the user's open invite (G2). The row, and its slot, go only once the Relay confirms;
     * offline, the cancel is queued. If the friend redeemed it first, the row becomes the Game.
     */
    suspend fun cancelInvite(gameId: String): Delivery = locked {
        val game = data.game(gameId) ?: return@locked Delivery.Refused(Refusal.NO_SUCH_GAME)
        val invite = game.invite
        if (invite == null || game.stage != Stage.WAITING) return@locked Delivery.Refused(Refusal.NOT_ALLOWED, game)
        save(data.with(game.copy(invite = invite.copy(cancelling = true))))
        sendCancel(gameId)
    }

    /** Drops a Game that is over or stopped from this phone. The Relay deletes it on its own (C6). */
    suspend fun forget(gameId: String): Delivery = locked {
        val game = data.game(gameId) ?: return@locked Delivery.Refused(Refusal.NO_SUCH_GAME)
        if (game.stage != Stage.OVER && game.halt == null) return@locked Delivery.Refused(Refusal.NOT_ALLOWED, game)
        save(data.without(gameId))
        Delivery.Done(null)
    }

    /**
     * Renames the opponent (the Opponent Label, F11): on this phone only, never sent. Spaces are
     * collapsed and the name cut to [MAX_LABEL] characters; a blank name changes nothing.
     */
    suspend fun rename(gameId: String, label: String): Delivery = locked {
        val game = data.game(gameId) ?: return@locked Delivery.Refused(Refusal.NO_SUCH_GAME)
        val name = label.trim().replace(Regex("\\s+"), " ").take(MAX_LABEL)
        if (name.isEmpty()) return@locked Delivery.Refused(Refusal.NOT_ALLOWED, game)
        val next = game.copy(label = name)
        save(data.with(next))
        Delivery.Done(next)
    }

    // ---- The user's entries (C4, R1, C2) -----------------------------------------------------

    /**
     * Plays [move], checked by the rules core, and sends it. With [offerDraw] ("Send and offer draw",
     * W2) a draw offer is held on the phone and sent once the Move is stored.
     */
    suspend fun play(gameId: String, move: Move, offerDraw: Boolean = false): Delivery =
        locked { appendOwn(gameId, EntryKind.MOVE, move = move, holdOffer = offerDraw) }

    /** Offers a draw, FIDE style: after the user's own Move, before the other Seat's reply (G3, R1). */
    suspend fun offerDraw(gameId: String): Delivery = locked { appendOwn(gameId, EntryKind.DRAW_OFFER) }

    suspend fun acceptDraw(gameId: String): Delivery = locked { appendOwn(gameId, EntryKind.DRAW_ACCEPT) }

    suspend fun declineDraw(gameId: String): Delivery = locked { appendOwn(gameId, EntryKind.DRAW_DECLINE) }

    suspend fun resign(gameId: String): Delivery = locked { appendOwn(gameId, EntryKind.RESIGN) }

    /** Claims a win on time: one tap, never automatic, once the deadline has passed (C2, R3). */
    suspend fun claimTimeout(gameId: String): Delivery = locked { appendOwn(gameId, EntryKind.CLAIM) }

    // ---- Rematch (E8, F9) --------------------------------------------------------------------

    /**
     * Offers a rematch of a Game that is over: creates the new Game (the user takes the other Side,
     * same days per Move) with a join token, and appends the offer to the old log. Needs the Relay
     * for the new Game; the offer itself can wait to send. If the other Seat offered first, the new
     * Game is cancelled and the offer rolled back.
     */
    suspend fun offerRematch(gameId: String): Delivery = locked {
        val old = data.game(gameId) ?: return@locked Delivery.Refused(Refusal.NO_SUCH_GAME)
        val log = old.log
        if (old.halt != null) return@locked Delivery.Refused(Refusal.HALTED, old)
        if (old.pending != null || log == null || !log.closed || log.rematch != null) return@locked Delivery.Refused(Refusal.NOT_ALLOWED, old)
        if (slotsFull()) return@locked Delivery.Refused(Refusal.CAP_REACHED, old)
        val side = old.seat.side.opponent
        val created = when (val reply = relay.create(side, old.daysPerMove, InviteKind.TOKEN)) {
            is RelayReply.Ok -> reply.value
            is RelayReply.Refused -> return@locked Delivery.Refused(refusal(reply.error), old)
            is RelayReply.Unreachable -> return@locked Delivery.Refused(Refusal.OFFLINE, old)
        }
        heard(created.serverTime)
        val fresh = CorrespondenceGame(
            created.gameId, Seat(created.side, created.seatSecret), created.v, created.daysPerMove,
            label = old.label, invite = OpenInvite(expiresAt = created.inviteExpiresAt), serverTime = created.serverTime, rematchOf = old.gameId,
        )
        val offer = log.draft(old.seat.side, EntryKind.REMATCH_OFFER, rematch = RematchRef(created.gameId, checkNotNull(created.joinToken)))
            ?: error("a closed log without a rematch takes an offer")
        save(data.with(fresh).with(old.copy(pending = offer, rolledBack = null)))
        val sent = flush(gameId)
        if (sent is Delivery.Refused && sent.reason == Refusal.ROLLED_BACK) {
            data.game(created.gameId)?.let { save(data.with(it.copy(invite = it.invite?.copy(cancelling = true)))) }
            sendCancel(created.gameId)
        }
        sent
    }

    /** Accepts the other Seat's rematch offer: takes the new Game's Seat, then answers in the old log. Needs the Relay. */
    suspend fun acceptRematch(gameId: String): Delivery = locked {
        val old = data.game(gameId) ?: return@locked Delivery.Refused(Refusal.NO_SUCH_GAME)
        if (old.halt != null) return@locked Delivery.Refused(Refusal.HALTED, old)
        val log = old.log ?: return@locked Delivery.Refused(Refusal.NOT_ALLOWED, old)
        val rematch = log.rematch
        if (old.pending != null || rematch == null || rematch.offeredBy == old.seat.side || rematch.accepted != null) {
            return@locked Delivery.Refused(Refusal.NOT_ALLOWED, old)
        }
        log.draft(old.seat.side, EntryKind.REMATCH_ACCEPT) ?: return@locked Delivery.Refused(Refusal.NOT_ALLOWED, old)
        val seat = data.seats.firstOrNull { it.gameId == rematch.ref.gameId } ?: run {
            if (slotsFull()) return@locked Delivery.Refused(Refusal.CAP_REACHED, old)
            PendingSeat(SeatSecrets.fresh(), old.label, gameId = rematch.ref.gameId, joinToken = rematch.ref.joinToken, rematchOf = old.gameId)
                .also { save(data.withSeat(it)) }
        }
        when (val taken = takeSeat(seat)) {
            is Delivery.Refused -> taken.copy(game = data.game(gameId))
            else -> taken
        }
    }

    suspend fun declineRematch(gameId: String): Delivery = locked { appendOwn(gameId, EntryKind.REMATCH_DECLINE) }

    // ---- Syncing -----------------------------------------------------------------------------

    /** Sends [gameId]'s pending entry, then reads what is new: what the Game's screen calls. */
    suspend fun sync(gameId: String): Delivery = locked {
        if (data.game(gameId) == null) return@locked Delivery.Refused(Refusal.NO_SUCH_GAME)
        val sent = flush(gameId)
        if (sent is Delivery.Queued) return@locked sent
        val read = refresh(gameId)
        val game = data.game(gameId) ?: return@locked Delivery.Done(null)
        when {
            !read -> Delivery.Queued(game)
            sent is Delivery.Refused -> sent.copy(game = game)
            else -> Delivery.Done(game)
        }
    }

    /**
     * The background sync (C2, C8, E7): what a LightWork job runs, and what the Tool may run when it
     * opens. It never throws. In order:
     *
     * 1. Sends every queued invite cancel and every pending entry (each is safe to repeat).
     * 2. Reads every Game that can change, five per `/v1/sync` request: open invites, Games being
     *    played, and Games that are over while a rematch can still come (ours unanswered, or up to
     *    [REMATCH_WINDOW_MS] after the Result). Stopped Games are not read.
     * 3. Checks and adds what arrived; cancels the new Game of a rematch offer that was declined;
     *    sends any entry that a new entry made the phone draft again.
     *
     * The job maps [SyncReport.retry] to `LightJobResult.Retry` and keeps its periodic schedule
     * (every 1 to 2 hours; LightWork's minimum is 15 minutes) only while
     * [SyncReport.waitingOnOpponent]. It can't alert the user (PLATFORM.md); the Tool shows
     * [SyncReport.yourMove] when it next opens.
     */
    suspend fun syncAll(): SyncReport = mutex.withLock {
        val before = data
        var retry = false
        try {
            for (seat in data.seats) {
                if (takeSeat(seat).let { it is Delivery.Refused && (it.reason == Refusal.OFFLINE || it.reason == Refusal.RATE_LIMITED) }) retry = true
            }
            for (game in data.games.filter { it.halt == null && it.invite?.cancelling == true }) {
                if (sendCancel(game.gameId) is Delivery.Queued) retry = true
            }
            for (game in data.games.filter { it.halt == null && it.pending != null }) {
                if (flush(game.gameId) is Delivery.Queued) retry = true
            }
            for (batch in data.games.filter(::needsRead).chunked(Protocol.MAX_SYNC_GAMES)) {
                when (val reply = relay.sync(batch.map { SyncItem(it.gameId, it.seat.secret, it.entries.size.toLong()) })) {
                    is RelayReply.Ok -> for (result in reply.value) {
                        val game = data.game(result.gameId) ?: continue
                        val view = result.game
                        when {
                            result.ok && view != null -> save(apply(game, view))
                            result.error != null -> save(afterRefusedRead(game, RelayError(0, result.error)))
                            else -> retry = true
                        }
                    }
                    is RelayReply.Refused ->
                        if (reply.error.needsUpdate) {
                            for (game in batch.mapNotNull { data.game(it.gameId) }) save(data.with(game.halted(HaltReason.NEEDS_UPDATE, reply.error.detail.message)))
                        }
                        else retry = true
                    is RelayReply.Unreachable -> retry = true
                }
            }
            cancelOrphanedRematches()
            for (game in data.games.filter { it.halt == null && it.pending != null }) {
                if (flush(game.gameId) is Delivery.Queued) retry = true
            }
            if (data.games.any { it.halt == null && it.invite?.cancelling == true }) retry = true
            if (data.seats.isNotEmpty()) retry = true
        } catch (e: IOException) {
            retry = true
        }
        val ids = (before.games.map { it.gameId } + data.games.map { it.gameId }).distinct()
        SyncReport(
            changed = ids.filter { before.game(it) != data.game(it) },
            retry = retry,
            waitingOnOpponent = data.games.any { it.waitingOnOpponent },
            yourMove = data.games.count { it.yourMove },
        )
    }

    /** Whether [syncAll] has anything to do: a Game to send or read, or a Seat being taken. With none, it sends nothing (W1). */
    val hasWork: Boolean
        get() = data.seats.isNotEmpty() || data.games.any { it.halt == null && (it.pending != null || it.invite?.cancelling == true) || needsRead(it) }

    // ---- Inside the lock ---------------------------------------------------------------------

    private suspend inline fun locked(crossinline block: suspend () -> Delivery): Delivery = mutex.withLock {
        try {
            block()
        } catch (e: IOException) {
            Delivery.Refused(Refusal.NOT_SAVED)
        }
    }

    private fun save(next: CorrespondenceData) {
        store.save(next)
        data = next
    }

    private fun heard(serverTime: Long) {
        data = data.copy(clockOffset = serverTime - clock())
    }

    private fun slotsFull() = data.games.count { it.takesASlot } + data.seats.size >= MAX_GAMES

    /**
     * Sends [seat]'s redeem or join with the phone's own secret (W9). The Relay's Seat becomes the
     * Game (and a rematch's answer is appended to the old log); a refusal drops the [PendingSeat], but
     * for `rate_limited`; no answer keeps it for the next try.
     */
    private suspend fun takeSeat(seat: PendingSeat): Delivery {
        val code = seat.code
        val reply = if (code != null) relay.redeem(code, seat.secret)
        else relay.join(checkNotNull(seat.gameId), checkNotNull(seat.joinToken), seat.secret)
        return when (reply) {
            is RelayReply.Ok -> seated(seat, reply.value)
            is RelayReply.Refused -> {
                val error = reply.error
                if (error.code != ErrorCode.RATE_LIMITED) save(data.withoutSeat(seat))
                Delivery.Refused(
                    when {
                        code != null && error.code == ErrorCode.BAD_REQUEST -> Refusal.BAD_CODE
                        code == null && error.code == ErrorCode.GAME_NOT_FOUND -> Refusal.INVITE_NOT_FOUND
                        else -> refusal(error)
                    },
                )
            }
            is RelayReply.Unreachable -> Delivery.Refused(Refusal.OFFLINE)
        }
    }

    /** The Seat the Relay gave for [pending]: the Game saved with the phone's own secret, and a rematch answered. */
    private suspend fun seated(pending: PendingSeat, given: Redeemed): Delivery {
        heard(given.serverTime)
        if (Protocol.majorOf(given.v) != Protocol.MAJOR) {
            save(data.withoutSeat(pending))
            return Delivery.Refused(Refusal.NEEDS_UPDATE)
        }
        val game = data.game(given.gameId) ?: CorrespondenceGame(
            given.gameId, Seat(given.side, pending.secret), given.v, given.daysPerMove,
            label = pending.label, startedAt = given.startedAt, serverTime = given.serverTime, rematchOf = pending.rematchOf,
        )
        var next = data.with(game).withoutSeat(pending)
        val old = pending.rematchOf?.let { next.game(it) }
        val answer = old?.takeIf { it.pending == null && it.halt == null }?.log?.let { log ->
            log.rematch?.takeIf { it.accepted == null && it.offeredBy != old.seat.side && it.ref.gameId == given.gameId }
                ?.let { log.draft(old.seat.side, EntryKind.REMATCH_ACCEPT) }
        }
        if (old != null && answer != null) next = next.with(old.copy(pending = answer, rolledBack = null))
        save(next)
        if (old != null && answer != null) flush(old.gameId)
        return Delivery.Done(data.game(given.gameId))
    }

    private suspend fun appendOwn(gameId: String, kind: EntryKind, move: Move? = null, holdOffer: Boolean = false): Delivery {
        val game = data.game(gameId) ?: return Delivery.Refused(Refusal.NO_SUCH_GAME)
        if (game.halt != null) return Delivery.Refused(if (game.halt.reason == HaltReason.NEEDS_UPDATE) Refusal.NEEDS_UPDATE else Refusal.HALTED, game)
        val log = game.log
        if (game.pending != null || log == null) return Delivery.Refused(Refusal.NOT_ALLOWED, game)
        val append = log.draft(game.seat.side, kind, move, serverNow = serverNow())
        if (append == null) {
            val early = kind == EntryKind.CLAIM && log.draft(game.seat.side, kind) != null
            return Delivery.Refused(if (early) Refusal.TOO_EARLY else Refusal.NOT_ALLOWED, game)
        }
        save(data.with(game.copy(pending = append, rolledBack = null, drawOfferHeld = holdOffer && kind == EntryKind.MOVE)))
        return flush(gameId)
    }

    /**
     * Sends [gameId]'s pending entry until the Relay stores it, the phone rolls it back, or no
     * answer comes. Each round after a conflict first reads the log and drafts the entry again.
     */
    private suspend fun flush(gameId: String): Delivery {
        repeat(MAX_ROUNDS) {
            val game = data.game(gameId) ?: return Delivery.Done(null)
            val pending = game.pending ?: return if (game.rolledBack != null) Delivery.Refused(Refusal.ROLLED_BACK, game) else Delivery.Done(game)
            if (game.halt != null) return Delivery.Refused(Refusal.HALTED, game)
            when (val reply = relay.append(gameId, game.seat.secret, pending)) {
                is RelayReply.Ok -> {
                    val entry = reply.value
                    heard(entry.serverTime)
                    val next = if (entry.matches(pending, game.seat.side)) accept(game, listOf(entry))
                    else game.halted(HaltReason.OUT_OF_SYNC, "the Relay stored another entry ${entry.seq}")
                    save(data.with(next.copy(serverTime = maxOf(next.serverTime, entry.serverTime))))
                    // A held draw offer (W2) became the next pending entry: send it in the next round.
                    if (next.halt == null && next.pending != null) return@repeat
                    return if (next.halt != null) Delivery.Refused(Refusal.HALTED, next) else Delivery.Done(next)
                }
                is RelayReply.Refused -> {
                    val error = reply.error
                    when {
                        error.needsUpdate -> {
                            val halted = game.halted(HaltReason.NEEDS_UPDATE, error.detail.message)
                            save(data.with(halted))
                            return Delivery.Refused(Refusal.NEEDS_UPDATE, halted)
                        }
                        error.code == ErrorCode.GAME_NOT_FOUND || error.code == ErrorCode.BAD_SEAT_SECRET -> {
                            save(afterRefusedRead(game, error))
                            return Delivery.Refused(Refusal.HALTED, data.game(gameId))
                        }
                        error.code == ErrorCode.CLAIM_TOO_EARLY -> {
                            save(data.with(game.copy(pending = null, rolledBack = pending)))
                            refresh(gameId)
                            return Delivery.Refused(Refusal.TOO_EARLY, data.game(gameId))
                        }
                    }
                    // seq_conflict, or a refusal a stale log explains: read what is new, then decide again.
                    if (!refresh(gameId)) return Delivery.Queued(game)
                    val now = data.game(gameId) ?: return Delivery.Done(null)
                    if (error.code != ErrorCode.SEQ_CONFLICT && now.pending == pending) {
                        // The log didn't move, and the Relay still refuses it: roll it back (C8).
                        val rolled = now.copy(pending = null, rolledBack = pending)
                        save(data.with(rolled))
                        return Delivery.Refused(Refusal.ROLLED_BACK, rolled)
                    }
                }
                is RelayReply.Unreachable -> return Delivery.Queued(game)
            }
        }
        return data.game(gameId)?.let { if (it.pending != null) Delivery.Queued(it) else Delivery.Done(it) } ?: Delivery.Done(null)
    }

    /** Reads [gameId]'s new entries and applies them. False when the Relay didn't answer. */
    private suspend fun refresh(gameId: String): Boolean {
        val game = data.game(gameId) ?: return true
        return when (val reply = relay.events(gameId, game.seat.secret, since = game.entries.size.toLong())) {
            is RelayReply.Ok -> true.also { save(apply(game, reply.value)) }
            is RelayReply.Refused -> true.also { save(afterRefusedRead(game, reply.error)) }
            is RelayReply.Unreachable -> false
        }
    }

    private suspend fun sendCancel(gameId: String): Delivery {
        val game = data.game(gameId) ?: return Delivery.Done(null)
        return when (val reply = relay.cancelInvite(gameId, game.seat.secret)) {
            is RelayReply.Ok -> {
                save(data.without(gameId))
                Delivery.Done(null)
            }
            is RelayReply.Refused -> when (reply.error.code) {
                ErrorCode.INVITE_REDEEMED -> {
                    save(data.with(game.copy(invite = game.invite?.copy(cancelling = false))))
                    refresh(gameId)
                    Delivery.Refused(Refusal.ALREADY_REDEEMED, data.game(gameId))
                }
                ErrorCode.GAME_NOT_FOUND -> {
                    save(data.without(gameId))
                    Delivery.Done(null)
                }
                else -> {
                    val kept = game.copy(invite = game.invite?.copy(cancelling = false))
                    save(data.with(kept))
                    save(afterRefusedRead(kept, reply.error))
                    Delivery.Refused(refusal(reply.error), data.game(gameId))
                }
            }
            is RelayReply.Unreachable -> Delivery.Queued(game)
        }
    }

    /**
     * The new Game of a rematch offer of ours that will never start: declined, rolled back because
     * the other Seat offered first, or its old Game forgotten. Its invite is cancelled.
     */
    private suspend fun cancelOrphanedRematches() {
        for (fresh in data.games) {
            val old = fresh.rematchOf?.let { data.game(it) }
            val invite = fresh.invite ?: continue
            if (fresh.rematchOf == null || fresh.stage != Stage.WAITING || invite.cancelling || fresh.halt != null) continue
            val rematch = old?.log?.rematch
            val offering = old?.pending?.rematch?.gameId == fresh.gameId
            val offered = rematch != null && rematch.ref.gameId == fresh.gameId && rematch.accepted != false
            if (offering || offered) continue
            save(data.with(fresh.copy(invite = invite.copy(cancelling = true))))
            sendCancel(fresh.gameId)
        }
    }

    private fun needsRead(game: CorrespondenceGame): Boolean {
        if (game.halt != null || game.invite?.cancelling == true) return false
        return when (game.stage) {
            Stage.WAITING, Stage.ACTIVE -> true
            Stage.OVER -> {
                val rematch = game.log?.rematch
                when {
                    rematch != null -> rematch.offeredBy == game.seat.side && rematch.accepted == null
                    else -> serverNow() < (game.closedAt ?: 0) + REMATCH_WINDOW_MS
                }
            }
        }
    }

    /** [view] applied to [game]: the start, the new entries checked, then the Relay's bookkeeping compared with the phone's. */
    private fun apply(game: CorrespondenceGame, view: GameView): CorrespondenceData {
        heard(view.serverTime)
        var next = game.copy(serverTime = maxOf(game.serverTime, view.serverTime))
        if (Protocol.majorOf(view.v) != Protocol.MAJOR) return data.with(next.halted(HaltReason.NEEDS_UPDATE, "the Game uses protocol ${view.v}"))
        if (view.gameId != game.gameId || view.side != game.seat.side || view.daysPerMove != game.daysPerMove) {
            return data.with(next.halted(HaltReason.OUT_OF_SYNC, "the Relay describes another Game or Seat"))
        }
        if (next.startedAt == null) {
            if (view.waiting) return data.with(next.copy(invite = next.invite?.let { it.copy(expiresAt = view.inviteExpiresAt ?: it.expiresAt) }))
            val startedAt = view.startedAt ?: return data.with(next.halted(HaltReason.OUT_OF_SYNC, "a started Game without a start"))
            next = next.copy(startedAt = startedAt, invite = null)
        }
        next = accept(next, view.entries)
        if (next.halt == null) disagreement(next, view)?.let { next = next.halted(HaltReason.OUT_OF_SYNC, it) }
        return data.with(next)
    }

    /**
     * [entries] checked and added to [game]'s log, in order. An entry the phone already has must be
     * the same one; the first that isn't, or that the core refuses, stops the Game. Then the pending
     * entry is reconciled with the new log.
     */
    private fun accept(game: CorrespondenceGame, entries: List<LogEntry>): CorrespondenceGame {
        var log = game.log ?: return game.halted(HaltReason.OUT_OF_SYNC, "the saved log doesn't replay")
        fun stop(reason: HaltReason, why: String) = game.copy(entries = log.entries).withClosedAt(log).halted(reason, why)
        for (entry in entries) {
            if (entry.seq < 1) return stop(HaltReason.OUT_OF_SYNC, "entry ${entry.seq}")
            if (entry.seq <= log.latestSeq) {
                if (log.entries[(entry.seq - 1).toInt()] != entry) return stop(HaltReason.OUT_OF_SYNC, "entry ${entry.seq} changed on the Relay")
                continue
            }
            when (val verdict = log.check(entry)) {
                is Verdict.Accepted -> log = verdict.log
                is Verdict.Rejected -> return stop(HaltReason.OUT_OF_SYNC, verdict.why)
                is Verdict.UnknownKind -> return stop(HaltReason.NEEDS_UPDATE, "entry ${entry.seq} is a ${verdict.kind}")
            }
        }
        return heldOffer(reconcile(game.copy(entries = log.entries).withClosedAt(log)))
    }

    /**
     * W2: once the Move it waited for is stored, a held draw offer becomes the pending entry, if the
     * rules still allow it; else (the opponent has moved since) it is dropped and shown as "Offer not
     * sent" through [CorrespondenceGame.rolledBack]. A Move rolled back takes its offer with it.
     */
    private fun heldOffer(game: CorrespondenceGame): CorrespondenceGame {
        if (!game.drawOfferHeld || game.pending != null) return game
        val log = game.log ?: return game.copy(drawOfferHeld = false)
        if (game.halt != null || game.rolledBack?.entryKind == EntryKind.MOVE) return game.copy(drawOfferHeld = false)
        val offer = log.draft(game.seat.side, EntryKind.DRAW_OFFER)
        if (offer != null) return game.copy(pending = offer, drawOfferHeld = false)
        val notSent = Append(seq = log.latestSeq + 1, ply = log.game.ply, kind = EntryKind.DRAW_OFFER.wire, hash = log.game.position.digest)
        return game.copy(drawOfferHeld = false, rolledBack = notSent)
    }

    private fun CorrespondenceGame.withClosedAt(log: GameLog): CorrespondenceGame {
        if (closedAt != null || !log.closed) return this
        val closing = log.entries.lastOrNull { !(it.entryKind?.isRematch ?: false) }
        return copy(closedAt = closing?.serverTime ?: serverTime)
    }

    /**
     * The pending entry against a log that may have grown past it: stored (a lost response), so
     * cleared; or displaced by the other Seat, so drafted again at the new place or rolled back.
     */
    private fun reconcile(game: CorrespondenceGame): CorrespondenceGame {
        val pending = game.pending ?: return game
        val log = game.log ?: return game
        if (pending.seq > log.latestSeq) return game
        if (log.entries[(pending.seq - 1).toInt()].matches(pending, game.seat.side)) return game.copy(pending = null)
        val kind = pending.entryKind ?: return game.copy(pending = null, rolledBack = pending)
        val displacing = log.entries.drop((pending.seq - 1).toInt())
        val offerArrived = kind == EntryKind.MOVE && displacing.any { it.side != game.seat.side && it.entryKind == EntryKind.DRAW_OFFER }
        val again = if (offerArrived) null else log.draft(
            game.seat.side, kind,
            move = pending.uci?.let { log.game.position.moveFromUci(it) },
            rematch = pending.rematch,
            serverNow = serverNow(),
        )
        return if (again != null) game.copy(pending = again) else game.copy(pending = null, rolledBack = pending)
    }

    /** What the Relay's bookkeeping says that the phone's log doesn't, or null when they agree. */
    private fun disagreement(game: CorrespondenceGame, view: GameView): String? {
        val log = game.log ?: return "the saved log doesn't replay"
        val closed = view.status == "closed"
        return when {
            view.latestSeq != log.latestSeq -> "the Relay has ${view.latestSeq} entries, this phone ${log.latestSeq}"
            view.latestPly != log.game.ply -> "the Relay is at ply ${view.latestPly}, this phone at ${log.game.ply}"
            closed != log.closed -> if (closed) "the Relay closed the log, but the Game goes on here" else "the Game is over here (${log.game.result}), but the Relay's log is open"
            view.openDrawBy != log.game.openDrawOffer -> "the open draw offer differs"
            !closed && view.deadline != log.deadline -> "the deadline differs"
            view.rematch?.offeredBy != log.rematch?.offeredBy || view.rematch?.gameId != log.rematch?.ref?.gameId -> "the rematch offer differs"
            view.rematch != null && view.rematch.answer != when (log.rematch?.accepted) { true -> "accept"; false -> "decline"; null -> null } -> "the rematch answer differs"
            else -> null
        }
    }

    /** [game] after the Relay refused to read it (or to act on it) with [error]. */
    private fun afterRefusedRead(game: CorrespondenceGame, error: RelayError): CorrespondenceData = when {
        error.needsUpdate -> data.with(game.halted(HaltReason.NEEDS_UPDATE, error.detail.message))
        error.code == ErrorCode.GAME_NOT_FOUND ->
            // An invite that expired or was cancelled drops its row (G2); a started Game was deleted (C6).
            if (game.stage == Stage.WAITING) data.without(game.gameId) else data.with(game.halted(HaltReason.GONE))
        error.code == ErrorCode.BAD_SEAT_SECRET -> data.with(game.halted(HaltReason.SEAT_LOST))
        else -> data
    }

    private fun refusal(error: RelayError): Refusal = when {
        error.needsUpdate -> Refusal.NEEDS_UPDATE
        else -> when (error.code) {
            ErrorCode.INVITE_NOT_FOUND -> Refusal.INVITE_NOT_FOUND
            ErrorCode.INVITE_USED -> Refusal.INVITE_USED
            ErrorCode.INVITE_REDEEMED -> Refusal.ALREADY_REDEEMED
            ErrorCode.RATE_LIMITED -> Refusal.RATE_LIMITED
            ErrorCode.GAME_NOT_FOUND -> Refusal.NO_SUCH_GAME
            ErrorCode.BAD_SEAT_SECRET -> Refusal.HALTED
            else -> Refusal.NOT_ALLOWED
        }
    }

    companion object {
        /** The cap on Games at once (E7, F9). */
        const val MAX_GAMES = 5

        /** Send attempts per pending entry in one call, each after reading what displaced it. */
        const val MAX_ROUNDS = 4

        /** A Game that is over is read this long after its Result, so a rematch offer from the other Seat is seen (the invite's 48 h). */
        const val REMATCH_WINDOW_MS = 48 * 3_600_000L

        /** The default opponent label's length: the first characters of the Invite Code (F11). */
        const val LABEL_LENGTH = 4

        /** The longest Opponent Label a rename keeps. */
        const val MAX_LABEL = 16
    }
}

private fun CorrespondenceGame.halted(reason: HaltReason, detail: String = ""): CorrespondenceGame = copy(halt = Halt(reason, detail))
