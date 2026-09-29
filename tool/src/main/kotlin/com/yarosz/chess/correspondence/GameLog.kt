package com.yarosz.chess.correspondence

import com.yarosz.chess.relay.Append
import com.yarosz.chess.relay.EntryKind
import com.yarosz.chess.relay.LogEntry
import com.yarosz.chess.relay.Protocol
import com.yarosz.chess.relay.RematchRef
import com.yarosz.chess.rules.DrawAcceptance
import com.yarosz.chess.rules.DrawOffer
import com.yarosz.chess.rules.DrawRefusal
import com.yarosz.chess.rules.Game
import com.yarosz.chess.rules.GameEvent
import com.yarosz.chess.rules.InvalidGameEventException
import com.yarosz.chess.rules.Move
import com.yarosz.chess.rules.Resignation
import com.yarosz.chess.rules.Side
import com.yarosz.chess.rules.TimeoutClaim

/** A rematch offer in a closed log (E8): who offered, the new Game, and the answer once given (true: accepted). */
data class Rematch(val offeredBy: Side, val ref: RematchRef, val accepted: Boolean? = null)

/** What [GameLog.check] makes of one entry. */
sealed interface Verdict {
    data class Accepted(val log: GameLog) : Verdict

    /** The entry is one the rules core or the protocol refuses: the Game is "Out of sync" (C5). */
    data class Rejected(val why: String) : Verdict

    /** A kind from a later protocol minor: "Update Chess to continue this game" (E9). */
    data class UnknownKind(val kind: String) : Verdict
}

/**
 * A started Correspondence Game's log, every entry checked by the rules core and the protocol
 * (ADR 0002, C5). The Relay knows no chess, so this is where a Move is found legal, a `hash` is
 * compared with [com.yarosz.chess.rules.Position.digest], an `end` flag with the Result the core
 * derives, and a timeout claim with the deadline computed from the Relay's own serverTime stamps
 * (C2). The Result is [game]'s: the phone derives it and the Relay never states one.
 *
 * The same check runs on the phone's own entries before they are sent ([draft]) and on every entry
 * that arrives, so the two phones, running the same core over the same log, end with the same Game.
 */
class GameLog private constructor(
    val gameId: String,
    /** When the Game started (the invite was redeemed): White's clock runs from here (C2). */
    val startedAt: Long,
    val daysPerMove: Int,
    val entries: List<LogEntry>,
    val game: Game,
    val rematch: Rematch?,
    /** serverTime of the latest `move` entry. */
    private val lastMoveAt: Long?,
) {
    val latestSeq: Long get() = entries.size.toLong()

    /** The log takes only the rematch kinds once the Game has a Result (docs/protocol.md "After the end"). */
    val closed: Boolean get() = game.isOver

    /** When the side to move's time runs out, in the Relay's time, or null once closed (C2, H3). */
    val deadline: Long? get() = if (closed) null else (lastMoveAt ?: startedAt) + daysPerMove * Protocol.DAY_MS

    /** Checks [entry] as the next one of this log. */
    fun check(entry: LogEntry): Verdict {
        fun invalid(why: String) = Verdict.Rejected("entry ${entry.seq} (${entry.kind}): $why")
        if (entry.gameId != gameId) return invalid("belongs to another Game")
        if (entry.seq != latestSeq + 1) return invalid("expected entry ${latestSeq + 1}")
        val kind = entry.entryKind ?: return Verdict.UnknownKind(entry.kind)
        if (Protocol.majorOf(entry.v) != Protocol.MAJOR) return invalid("protocol ${entry.v} in a Game of major ${Protocol.MAJOR}")
        if (!Protocol.isDigest(entry.hash)) return invalid("the hash isn't a digest")
        if (kind != EntryKind.MOVE && (entry.uci != null || entry.end != null)) return invalid("only a move carries uci or end")
        if (kind != EntryKind.REMATCH_OFFER && entry.rematch != null) return invalid("only a rematchOffer carries rematch")
        if (kind == EntryKind.MOVE && entry.end == false) return invalid("end is true or absent")
        if (closed != kind.isRematch) return invalid(if (closed) "the Game is over (${game.result})" else "the Game isn't over")

        val side = entry.side
        val toMove = game.position.sideToMove
        if (kind.isRematch) return rematchEntry(entry, kind, ::invalid)

        val event: GameEvent = when (kind) {
            EntryKind.MOVE -> {
                if (side != toMove) return invalid("$side moved on $toMove's turn")
                val uci = entry.uci ?: return invalid("a move without uci")
                game.position.moveFromUci(uci)?.takeIf { it.uci == uci } ?: return invalid("$uci is not a legal Move in ${game.position.fen}")
            }
            EntryKind.RESIGN -> Resignation(side)
            EntryKind.DRAW_OFFER -> {
                // R1: offered with the sender's own Move, so by the side not to move, once a Move is played.
                if (side == toMove || game.ply < 1) return invalid("a draw offer comes from the side that has just moved")
                DrawOffer(side)
            }
            EntryKind.DRAW_ACCEPT, EntryKind.DRAW_DECLINE -> {
                if (side != toMove) return invalid("a draw offer is answered by the side to move")
                if (kind == EntryKind.DRAW_ACCEPT) DrawAcceptance(side) else DrawRefusal(side)
            }
            EntryKind.CLAIM -> {
                if (side == toMove) return invalid("only the side not to move can claim on time")
                val deadline = checkNotNull(deadline)
                if (entry.serverTime < deadline) return invalid("claimed at ${entry.serverTime}, before the deadline $deadline")
                TimeoutClaim(side)
            }
            else -> error("rematch kinds are handled above")
        }
        val next = try {
            game + event
        } catch (e: InvalidGameEventException) {
            return invalid(e.message ?: "the rules refuse it")
        }
        if (entry.ply != next.ply) return invalid("ply ${entry.ply}, expected ${next.ply}")
        if (entry.hash != next.position.digest) return invalid("the hash doesn't match this phone's digest")
        if (kind == EntryKind.MOVE && (entry.end == true) != next.isOver) {
            return invalid(if (next.isOver) "the Move ends the Game (${next.result}) but isn't marked end" else "marked end, but the Game goes on")
        }
        val movedAt = if (kind == EntryKind.MOVE) entry.serverTime else lastMoveAt
        return Verdict.Accepted(GameLog(gameId, startedAt, daysPerMove, entries + entry, next, rematch, movedAt))
    }

    private fun rematchEntry(entry: LogEntry, kind: EntryKind, invalid: (String) -> Verdict): Verdict {
        if (entry.ply != game.ply) return invalid("ply ${entry.ply}, expected ${game.ply}")
        if (entry.hash != game.position.digest) return invalid("the hash doesn't match this phone's digest")
        val next = when (kind) {
            EntryKind.REMATCH_OFFER -> {
                if (rematch != null) return invalid("a Game gets one rematch offer")
                val ref = entry.rematch ?: return invalid("a rematch offer without its Game")
                if (!Protocol.isGameId(ref.gameId) || !Protocol.isToken(ref.joinToken)) return invalid("a malformed rematch Game")
                Rematch(entry.side, ref)
            }
            else -> {
                if (rematch == null) return invalid("no rematch offer to answer")
                if (rematch.accepted != null) return invalid("the rematch offer was already answered")
                if (rematch.offeredBy == entry.side) return invalid("only the other Seat answers a rematch offer")
                rematch.copy(accepted = kind == EntryKind.REMATCH_ACCEPT)
            }
        }
        return Verdict.Accepted(GameLog(gameId, startedAt, daysPerMove, entries + entry, game, next, lastMoveAt))
    }

    /**
     * The phone's own next entry for [side], of [kind] (with [move] or [rematch] where the kind
     * needs one), or null when the rules or the protocol don't allow it now. [serverNow] is the
     * phone's estimate of the Relay's time, which only a claim needs; the Relay decides with its own.
     */
    fun draft(side: Side, kind: EntryKind, move: Move? = null, rematch: RematchRef? = null, serverNow: Long = Long.MAX_VALUE): Append? {
        val after = if (kind == EntryKind.MOVE) {
            if (move == null || closed) return null
            try {
                game + move
            } catch (e: InvalidGameEventException) {
                return null
            }
        } else game
        val append = Append(
            seq = latestSeq + 1,
            ply = after.ply,
            kind = kind.wire,
            uci = move?.uci,
            end = if (kind == EntryKind.MOVE && after.isOver) true else null,
            hash = after.position.digest,
            rematch = rematch,
        )
        val entry = LogEntry(append.v, gameId, append.seq, append.ply, side, append.kind, append.uci, append.end, append.hash, append.rematch, serverNow)
        return append.takeIf { check(entry) is Verdict.Accepted }
    }

    companion object {
        /** The log of a Game that started at [startedAt], before any entry. */
        fun start(gameId: String, startedAt: Long, daysPerMove: Int): GameLog =
            GameLog(gameId, startedAt, daysPerMove, emptyList(), Game.of(), null, null)

        /** [entries] checked one by one from the start; the first refusal stops it. */
        fun replay(gameId: String, startedAt: Long, daysPerMove: Int, entries: List<LogEntry>): Verdict {
            var log = start(gameId, startedAt, daysPerMove)
            for (entry in entries) {
                when (val verdict = log.check(entry)) {
                    is Verdict.Accepted -> log = verdict.log
                    else -> return verdict
                }
            }
            return Verdict.Accepted(log)
        }
    }
}
