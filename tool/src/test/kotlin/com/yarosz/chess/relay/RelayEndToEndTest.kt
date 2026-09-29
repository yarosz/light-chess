package com.yarosz.chess.relay

import com.yarosz.chess.correspondence.Correspondence
import com.yarosz.chess.correspondence.CorrespondenceStore
import com.yarosz.chess.correspondence.Delivery
import com.yarosz.chess.correspondence.Stage
import com.yarosz.chess.rules.DrawReason
import com.yarosz.chess.rules.Result
import com.yarosz.chess.rules.Side
import com.yarosz.chess.rules.WinReason
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * The Kotlin client against the real Worker, run locally (relay/README.md, "End-to-end test"):
 * two phones' sync engines over [OkHttpTransport] play through `wrangler dev --local`. Skipped
 * unless `-Drelay.e2e=http://127.0.0.1:8787` names the running Worker; it never runs in CI or
 * against a deployed Relay.
 */
class RelayEndToEndTest {

    private val url: String? = System.getProperty("relay.e2e")?.takeIf { it.isNotBlank() }

    private fun phone(): Correspondence {
        val dir = Files.createTempDirectory("relay-e2e").toFile().also { it.deleteOnExit() }
        return Correspondence(RelayClient(OkHttpTransport(url!!)), CorrespondenceStore(dir))
    }

    private fun done(delivery: Delivery) = assertIs<Delivery.Done>(delivery, "$delivery").game

    @Test
    fun `two phones play through the local Worker`() = runBlocking<Unit> {
        assumeTrue("set -Drelay.e2e=<local Worker URL> to run", url != null)
        val client = RelayClient(OkHttpTransport(url!!))
        val health = assertIs<RelayReply.Ok<Health>>(client.health()).value
        assertEquals(Protocol.VERSION, health.protocol)
        assertTrue(Protocol.MAJOR in health.majors)

        val a = phone()
        val b = phone()
        val invite = assertNotNull(done(a.createInvite(Side.WHITE, 1)))
        val id = invite.gameId
        val joined = assertNotNull(done(b.redeemInvite(invite.invite!!.code!!.lowercase())))
        assertEquals(Side.BLACK, joined.seat.side)
        a.syncAll()
        assertEquals(Stage.ACTIVE, a.game(id)!!.stage)

        suspend fun play(phone: Correspondence, uci: String) {
            phone.syncAll()
            val position = phone.game(id)!!.log!!.game.position
            done(phone.play(id, checkNotNull(position.moveFromUci(uci))))
        }
        play(a, "e2e4")
        play(b, "e7e5")
        done(b.offerDraw(id))
        a.syncAll()
        done(a.declineDraw(id))
        play(a, "d1h5")
        play(b, "b8c6")
        play(a, "f1c4")
        play(b, "g8f6")
        play(a, "h5f7")
        b.syncAll()
        assertEquals(Result.Win(Side.WHITE, WinReason.CHECKMATE), b.game(id)!!.log!!.game.result)
        assertEquals(a.game(id)!!.entries, b.game(id)!!.entries)
        assertEquals(true, a.game(id)!!.entries.last().end)

        // A repeat of a stored entry answers 200 with it: what a lost response's retry gets.
        val last = a.game(id)!!.entries.last()
        val again = client.append(id, a.game(id)!!.seat.secret, Append(seq = last.seq, ply = last.ply, kind = last.kind, uci = last.uci, end = last.end, hash = last.hash))
        assertEquals(200, assertIs<RelayReply.Ok<LogEntry>>(again).status)
        assertEquals(last, again.value)

        // A rematch through the old log, then a draw by agreement in it.
        done(b.offerRematch(id))
        a.syncAll()
        val rematch = assertNotNull(done(a.acceptRematch(id)))
        assertEquals(Side.BLACK, rematch.seat.side)
        b.syncAll()
        assertEquals(Stage.ACTIVE, b.game(rematch.gameId)!!.stage)
        val r = rematch.gameId
        b.syncAll()
        done(b.play(r, b.game(r)!!.log!!.game.position.moveFromUci("d2d4")!!))
        done(b.offerDraw(r))
        a.syncAll()
        done(a.acceptDraw(r))
        b.syncAll()
        assertEquals(Result.Draw(DrawReason.AGREEMENT), b.game(r)!!.log!!.game.result)

        // An invite cancelled before anyone redeems it.
        val spare = assertNotNull(done(a.createInvite(Side.BLACK)))
        assertEquals(null, done(a.cancelInvite(spare.gameId)))
        assertIs<RelayReply.Refused>(client.redeem(InviteCodes.normalize(spare.invite!!.code!!)!!))
    }
}
