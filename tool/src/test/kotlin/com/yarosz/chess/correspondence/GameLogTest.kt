package com.yarosz.chess.correspondence

import com.yarosz.chess.relay.Append
import com.yarosz.chess.relay.EntryKind
import com.yarosz.chess.relay.LogEntry
import com.yarosz.chess.relay.Protocol
import com.yarosz.chess.relay.RematchRef
import com.yarosz.chess.rules.DrawReason
import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.Result
import com.yarosz.chess.rules.Side
import com.yarosz.chess.rules.WinReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [GameLog]: every entry checked with the rules core and the protocol before it joins the log. */
class GameLogTest {

    private val id = "a".repeat(64)
    private val t0 = 1_790_000_000_000L
    private val day = Protocol.DAY_MS
    private val empty = GameLog.start(id, t0, 3)

    /** The entry [side] would append as [append], stored at [time]. */
    private fun entry(append: Append, side: Side, time: Long = t0) =
        LogEntry(append.v, id, append.seq, append.ply, side, append.kind, append.uci, append.end, append.hash, append.rematch, time)

    private fun GameLog.move(uci: String, time: Long = t0): GameLog {
        val side = game.position.sideToMove
        val append = assertNotNull(draft(side, EntryKind.MOVE, move = game.position.moveFromUci(uci)), uci)
        return accepted(check(entry(append, side, time)))
    }

    private fun GameLog.moves(vararg uci: String) = uci.fold(this) { log, m -> log.move(m) }

    private fun GameLog.add(kind: EntryKind, side: Side, time: Long = t0, rematch: RematchRef? = null): GameLog =
        accepted(check(entry(assertNotNull(draft(side, kind, rematch = rematch, serverNow = time), "$kind by $side"), side, time)))

    private fun accepted(verdict: Verdict): GameLog {
        assertIs<Verdict.Accepted>(verdict, "$verdict")
        return verdict.log
    }

    private fun rejected(verdict: Verdict, why: String) {
        assertIs<Verdict.Rejected>(verdict)
        assertTrue(why in verdict.why, verdict.why)
    }

    private fun digest(vararg m: String) = m.fold(Position.START) { p, u -> p.play(checkNotNull(p.moveFromUci(u))) }.digest

    /** A raw entry at the log's next seq. */
    private fun GameLog.raw(side: Side, kind: String, uci: String? = null, end: Boolean? = null, hash: String = game.position.digest, ply: Int = game.ply, time: Long = t0, rematch: RematchRef? = null) =
        LogEntry("1.0", id, latestSeq + 1, ply, side, kind, uci, end, hash, rematch, time)

    @Test
    fun `a legal Move with the right digest joins the log`() {
        val log = empty.moves("e2e4", "e7e5", "g1f3")
        assertEquals(3L, log.latestSeq)
        assertEquals(3, log.game.ply)
        assertEquals(Side.BLACK, log.game.position.sideToMove)
    }

    @Test
    fun `an illegal Move, a wrong digest, a wrong ply or the wrong side is refused`() {
        rejected(empty.check(empty.raw(Side.WHITE, "move", "e2e5", hash = digest("e2e4"), ply = 1)), "not a legal Move")
        rejected(empty.check(empty.raw(Side.WHITE, "move", "e2e4", hash = digest("d2d4"), ply = 1)), "digest")
        rejected(empty.check(empty.raw(Side.WHITE, "move", "e2e4", hash = digest("e2e4"), ply = 2)), "ply")
        rejected(empty.check(empty.raw(Side.BLACK, "move", "e2e4", hash = digest("e2e4"), ply = 1)), "turn")
        // Castling onto the rook is legal Lichess input, but the wire form is the king's step only.
        val castle = empty.moves("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6")
        val kingStep = castle.game.position.moveFromUci("e1g1")!!
        rejected(castle.check(castle.raw(Side.WHITE, "move", "e1h1", hash = castle.game.position.play(kingStep).digest, ply = 7)), "not a legal Move")
    }

    @Test
    fun `the end flag must match the Result the core derives`() {
        val mate = empty.moves("f2f3", "e7e5", "g2g4")
        val position = mate.game.position.play(mate.game.position.moveFromUci("d8h4")!!)
        rejected(mate.check(mate.raw(Side.BLACK, "move", "d8h4", hash = position.digest, ply = 4)), "isn't marked end")
        val ended = accepted(mate.check(mate.raw(Side.BLACK, "move", "d8h4", end = true, hash = position.digest, ply = 4)))
        assertEquals(Result.Win(Side.BLACK, WinReason.CHECKMATE), ended.game.result)
        assertTrue(ended.closed)
        assertNull(ended.deadline)
        val open = empty.game.position.play(empty.game.position.moveFromUci("e2e4")!!)
        rejected(empty.check(empty.raw(Side.WHITE, "move", "e2e4", end = true, hash = open.digest, ply = 1)), "marked end")
        rejected(ended.check(ended.raw(Side.WHITE, "resign")), "over")
    }

    @Test
    fun `a draw is offered by the side that just moved, and answered by the side to move`() {
        rejected(empty.check(empty.raw(Side.BLACK, "drawOffer")), "just moved")
        val one = empty.move("e2e4")
        rejected(one.check(one.raw(Side.BLACK, "drawOffer")), "just moved")
        rejected(one.check(one.raw(Side.BLACK, "drawAccept")), "no draw offer")
        val offered = one.add(EntryKind.DRAW_OFFER, Side.WHITE)
        assertEquals(Side.WHITE, offered.game.openDrawOffer)
        rejected(offered.check(offered.raw(Side.WHITE, "drawOffer")), "already open")
        rejected(offered.check(offered.raw(Side.WHITE, "drawAccept")), "answered by the side to move")
        val declined = offered.add(EntryKind.DRAW_DECLINE, Side.BLACK)
        assertNull(declined.game.openDrawOffer)
        val agreed = offered.add(EntryKind.DRAW_ACCEPT, Side.BLACK)
        assertEquals(Result.Draw(DrawReason.AGREEMENT), agreed.game.result)
        // A Move declines the offer implicitly.
        assertNull(offered.move("e7e5").game.openDrawOffer)
    }

    @Test
    fun `a timeout claim needs the deadline to have passed in the Relay's time`() {
        val log = empty.move("e2e4", time = t0 + 1_000)
        val deadline = t0 + 1_000 + 3 * day
        assertEquals(deadline, log.deadline)
        rejected(log.check(log.raw(Side.WHITE, "claim", time = deadline - 1)), "before the deadline")
        rejected(log.check(log.raw(Side.BLACK, "claim", time = deadline)), "not to move")
        assertNull(log.draft(Side.WHITE, EntryKind.CLAIM, serverNow = deadline - 1))
        val claimed = log.add(EntryKind.CLAIM, Side.WHITE, time = deadline)
        assertEquals(Result.Win(Side.WHITE, WinReason.TIME), claimed.game.result)
        // Before the first Move, White's clock runs from the start.
        assertEquals(t0 + 3 * day, empty.deadline)
        assertEquals(Result.Win(Side.BLACK, WinReason.TIME), empty.add(EntryKind.CLAIM, Side.BLACK, time = t0 + 3 * day).game.result)
        // A late Move closes the claim window, and the other clock runs from it.
        val late = log.move("e7e5", time = deadline + 5)
        assertEquals(deadline + 5 + 3 * day, late.deadline)
        rejected(late.check(late.raw(Side.WHITE, "claim", time = deadline + 10)), "not to move")
    }

    @Test
    fun `resigning ends the Game at any point of an open log`() {
        assertEquals(Result.Win(Side.WHITE, WinReason.RESIGNATION), empty.add(EntryKind.RESIGN, Side.BLACK).game.result)
        val log = empty.move("e2e4").add(EntryKind.RESIGN, Side.WHITE)
        assertEquals(Result.Win(Side.BLACK, WinReason.RESIGNATION), log.game.result)
        assertEquals(1, log.game.ply)
    }

    @Test
    fun `a rematch is offered once the log is closed, once, and answered once by the other Seat`() {
        val ref = RematchRef("b".repeat(64), "t".repeat(43))
        rejected(empty.check(empty.raw(Side.WHITE, "rematchOffer", rematch = ref)), "isn't over")
        val over = empty.move("e2e4").add(EntryKind.RESIGN, Side.BLACK)
        rejected(over.check(over.raw(Side.WHITE, "rematchOffer")), "without its Game")
        rejected(over.check(over.raw(Side.WHITE, "rematchOffer", rematch = RematchRef("bad", "t".repeat(43)))), "malformed")
        rejected(over.check(over.raw(Side.WHITE, "rematchAccept")), "no rematch offer")
        val offered = over.add(EntryKind.REMATCH_OFFER, Side.BLACK, rematch = ref)
        assertEquals(Rematch(Side.BLACK, ref), offered.rematch)
        rejected(offered.check(offered.raw(Side.WHITE, "rematchOffer", rematch = ref)), "one rematch offer")
        rejected(offered.check(offered.raw(Side.BLACK, "rematchAccept")), "other Seat")
        val accepted = offered.add(EntryKind.REMATCH_ACCEPT, Side.WHITE)
        assertEquals(true, accepted.rematch?.accepted)
        rejected(accepted.check(accepted.raw(Side.WHITE, "rematchDecline")), "already answered")
        assertEquals(false, offered.add(EntryKind.REMATCH_DECLINE, Side.WHITE).rematch?.accepted)
    }

    @Test
    fun `the log's own bookkeeping is checked`() {
        val append = empty.draft(Side.WHITE, EntryKind.MOVE, move = empty.game.position.moveFromUci("e2e4"))!!
        rejected(empty.check(entry(append, Side.WHITE).copy(gameId = "c".repeat(64))), "another Game")
        rejected(empty.check(entry(append, Side.WHITE).copy(seq = 2)), "expected entry 1")
        rejected(empty.check(entry(append, Side.WHITE).copy(v = "2.0")), "protocol 2.0")
        rejected(empty.check(entry(append, Side.WHITE).copy(hash = "XYZ")), "isn't a digest")
        rejected(empty.check(empty.raw(Side.WHITE, "resign").copy(uci = "e2e4")), "only a move")
        rejected(empty.check(entry(append, Side.WHITE).copy(end = false)), "end is true or absent")
        assertIs<Verdict.UnknownKind>(empty.check(empty.raw(Side.WHITE, "chat")))
        // A later minor's version still reads.
        accepted(empty.check(entry(append, Side.WHITE).copy(v = "1.4")))
    }

    @Test
    fun `the phone's own draft is refused when the rules or the protocol refuse it`() {
        assertNull(empty.draft(Side.BLACK, EntryKind.MOVE, move = empty.game.position.moveFromUci("e2e4")))
        assertNull(empty.draft(Side.WHITE, EntryKind.DRAW_OFFER))
        assertNull(empty.draft(Side.WHITE, EntryKind.DRAW_ACCEPT))
        assertNull(empty.draft(Side.WHITE, EntryKind.REMATCH_OFFER, rematch = RematchRef("b".repeat(64), "t".repeat(43))))
        val resign = assertNotNull(empty.draft(Side.WHITE, EntryKind.RESIGN))
        assertEquals(Append(seq = 1, ply = 0, kind = "resign", hash = Position.START.digest), resign)
    }

    @Test
    fun `replay stops at the first refused entry`() {
        val log = empty.moves("e2e4", "e7e5")
        val bad = log.entries + log.raw(Side.WHITE, "drawAccept")
        rejected(GameLog.replay(id, t0, 3, bad), "entry 3")
        assertEquals(log.game, accepted(GameLog.replay(id, t0, 3, log.entries)).game)
    }
}
