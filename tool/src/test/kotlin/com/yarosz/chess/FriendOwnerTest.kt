package com.yarosz.chess

import com.thelightphone.sdk.LightJobResult
import com.yarosz.chess.correspondence.Correspondence
import com.yarosz.chess.correspondence.CorrespondenceStore
import com.yarosz.chess.correspondence.Phone
import com.yarosz.chess.correspondence.Stage
import com.yarosz.chess.relay.FakeRelay
import com.yarosz.chess.relay.Protocol
import com.yarosz.chess.relay.RelayClient
import com.yarosz.chess.relay.RelayRequest
import com.yarosz.chess.relay.RelayResponse
import com.yarosz.chess.relay.RelayTransport
import com.yarosz.chess.relay.Weather
import com.yarosz.chess.rules.Side
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * Play a friend's process-wide owner on the JVM (W1, W7, W8, F11): what it sends, when it schedules
 * the LightWork jobs, the confirmation before a Move goes, and the Menu's count.
 */
class FriendOwnerTest {

    /** The LightWork calls the owner made, in order. */
    private class RecordingJobs : SyncJobs {
        val calls = mutableListOf<String>()
        override fun periodic(on: Boolean) {
            calls += if (on) "periodic on" else "periodic off"
        }

        override fun soon() {
            calls += "soon"
        }
    }

    /** Every request that reached the transport; [offline] makes each one fail as no connection would. */
    private class CountingTransport(private val relay: FakeRelay) : RelayTransport {
        var calls = 0
        var offline = false
        override suspend fun exchange(request: RelayRequest): RelayResponse {
            calls++
            if (offline) throw IOException("offline")
            return relay.handle(request)
        }
    }

    private val relay = FakeRelay()
    private val dir: File = Files.createTempDirectory("friend-owner").toFile()
    private val transport = CountingTransport(relay)
    private val jobs = RecordingJobs()
    private val friend = Phone(relay, 7, "friend")

    private fun owner(): FriendOwner {
        val correspondence = Correspondence(RelayClient(transport), CorrespondenceStore(dir)) { relay.now }
        return FriendOwner(correspondence, jobs, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), Dispatchers.Unconfined)
    }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
        friend.clean()
    }

    @Test
    fun `with an empty store, nothing is sent and no LightWork job is scheduled (W1)`() = runBlocking<Unit> {
        val owner = owner()
        owner.sync()
        assertNull(owner.syncNow(), "nothing to do")
        assertEquals(0, transport.calls)
        assertEquals(emptyList(), jobs.calls)
        assertEquals(0, owner.state.value.yourMove)
        assertFalse(owner.state.value.full)
    }

    @Test
    fun `with the Relay URL empty, Play a friend doesn't exist (W8)`() = runBlocking<Unit> {
        val filesDir = File(dir, "files").also { it.mkdirs() }
        assertNull(FriendOwner.of(filesDir, { error("no job may be scheduled") }, url = ""))
        assertFalse(File(dir, "no_backup").exists(), "no store was opened")
        assertIs<LightJobResult.Success>(FriendJobs.run(null, periodic = true))
        assertIs<LightJobResult.Success>(FriendJobs.run(null, periodic = false))
        assertTrue(UiCopy.PRIVACY in UiCopy.about(null, "", friends = false))
        assertFalse(UiCopy.about(null, "", friends = false).any { "Relay" in it })
        assertTrue(UiCopy.PRIVACY_FRIENDS in UiCopy.about(null, "", friends = true))
    }

    @Test
    fun `a Game waiting on the opponent keeps the hourly job, until none waits (W7)`() = runBlocking<Unit> {
        val owner = owner()
        var created: String? = null
        owner.create(Side.WHITE, 3) { created = (it as? com.yarosz.chess.correspondence.Delivery.Done)?.game?.gameId }
        val id = assertNotNull(created)
        assertEquals(listOf("periodic on"), jobs.calls, "the invite waits on the friend")
        val code = assertNotNull(owner.state.value.game(id)?.invite?.code)
        friend.c.redeemInvite(code)
        owner.syncNow()
        assertTrue(owner.state.value.game(id)?.yourMove == true)
        assertEquals(listOf("periodic on", "periodic off"), jobs.calls, "the user's Move waits on nobody")
        assertEquals(1, owner.state.value.yourMove, "the Menu's Your move: 1")

        owner.choose(id, assertNotNull(owner.state.value.game(id)?.log).game.position.moveFromUci("e2e4")!!)
        owner.send(id)
        assertEquals(listOf("periodic on", "periodic off", "periodic on"), jobs.calls)
        owner.resign(id)
        assertEquals(Stage.OVER, owner.state.value.game(id)?.stage)
        assertEquals("periodic off", jobs.calls.last())
        // The periodic job itself, finding nothing to wait for, cancels its own schedule.
        assertIs<LightJobResult.Success>(FriendJobs.run(owner, periodic = true))
        assertEquals("periodic off", jobs.calls.last())
    }

    @Test
    fun `a chosen Move is sent only on Send, and Undo takes it back (F11)`() = runBlocking<Unit> {
        val id = friendStarted()
        val owner = owner()
        val move = owner.state.value.game(id)!!.log!!.game.position.moveFromUci("d2d4")!!
        owner.choose(id, move)
        assertEquals(move, owner.state.value.chosen[id])
        assertTrue(relay.entries(id).isEmpty(), "nothing sent before Send")
        owner.undo(id)
        assertTrue(owner.state.value.chosen.isEmpty())
        owner.choose(id, move)
        owner.send(id)
        assertEquals(listOf("d2d4"), relay.entries(id).map { it.uci })
        assertTrue(owner.state.value.chosen.isEmpty())
        assertTrue(owner.state.value.sending.isEmpty())
        assertFalse(owner.state.value.game(id)!!.yourMove)
    }

    @Test
    fun `a Move that can't be sent schedules the one-off job, which retries until it goes (C8)`() = runBlocking<Unit> {
        val id = friendStarted()
        val owner = owner()
        transport.offline = true
        owner.choose(id, owner.state.value.game(id)!!.log!!.game.position.moveFromUci("e2e4")!!)
        owner.send(id)
        assertNotNull(owner.state.value.game(id)?.pending, "saved, not sent")
        assertTrue("soon" in jobs.calls)
        assertEquals(UiCopy.NOT_SENT, FriendStrip.of(owner.state.value.game(id)!!, relay.now).status)
        assertIs<LightJobResult.Retry>(FriendJobs.run(owner, periodic = false))
        transport.offline = false
        assertIs<LightJobResult.Success>(FriendJobs.run(owner, periodic = false))
        assertNull(owner.state.value.game(id)?.pending)
        assertEquals(1, relay.entries(id).size)
        assertEquals(1, jobs.calls.count { it == "soon" }, "the job never enqueues itself")
    }

    @Test
    fun `a refusal shows its copy for a few seconds (W10)`() = runBlocking<Unit> {
        val id = friendStarted()
        val owner = owner()
        transport.offline = true
        owner.offerDraw(id)
        assertEquals(UiCopy.refusal(com.yarosz.chess.correspondence.Refusal.NOT_ALLOWED), owner.state.value.notices[id]?.text, "a draw is offered after one's own Move")
    }

    @Test
    fun `Your move counts only the user's Move and not a Stopped Game (W10)`() = runBlocking<Unit> {
        val id = friendStarted()
        val owner = owner()
        assertEquals(1, owner.state.value.yourMove)
        relay.delete(id)
        owner.syncNow()
        assertNotNull(owner.state.value.game(id)?.halt)
        assertEquals(0, owner.state.value.yourMove)
        assertEquals(UiCopy.GAME_DELETED, FriendStrip.of(owner.state.value.game(id)!!, relay.now).status)
    }

    /** A Game the friend created as Black and this owner's phone took as White, through the same store. */
    private suspend fun friendStarted(): String {
        val invite = assertNotNull((friend.c.createInvite(Side.BLACK, Protocol.DEFAULT_DAYS_PER_MOVE) as com.yarosz.chess.correspondence.Delivery.Done).game)
        val here = Correspondence(RelayClient(transport), CorrespondenceStore(dir)) { relay.now }
        here.redeemInvite(assertNotNull(invite.invite?.code))
        friend.c.syncAll()
        transport.calls = 0
        return invite.gameId
    }
}
