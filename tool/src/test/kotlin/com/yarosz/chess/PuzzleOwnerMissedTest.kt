package com.yarosz.chess

import com.yarosz.chess.GameOwnerTest.Companion.waitFor
import com.yarosz.chess.engine.EngineHost
import com.yarosz.chess.puzzles.AttemptState
import com.yarosz.chess.puzzles.MissedEntry
import com.yarosz.chess.puzzles.PuzzleData
import com.yarosz.chess.puzzles.PuzzleStore
import com.yarosz.chess.puzzles.TestPacks
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The puzzle owner on the JVM with an in-memory Pack: a Missed row whose Puzzle the Pack lost (N13),
 * and a tap on Missed that never reads a Band file on the calling (main) thread.
 */
class PuzzleOwnerMissedTest {
    private val dir: File = Files.createTempDirectory("puzzle-owner").toFile()
    private val host = EngineHost { GameOwnerTest.HeldEngine() }
    private val owners = mutableListOf<PuzzleOwner>()
    private var game: GameOwner? = null

    @AfterTest
    fun cleanUp() {
        host.stop()
        host.shutdown()
        for (owner in owners) owner.close()
        ModeOwner.forget(dir)
        dir.deleteRecursively()
    }

    private val lines = (1300..1700 step 50).map { TestPacks.line("p$it", it) }
    private val assets = TestPacks.assets("A", lines)

    /** Every Band file read, with the thread that read it. */
    private val reads = Collections.synchronizedList(mutableListOf<Pair<String, Thread>>())

    /** While set, a Band read waits for it: the Missed check is still running. */
    @Volatile
    private var hold: CountDownLatch? = null

    private val missed = listOf(MissedEntry("p1500", 1500, AttemptState.FAILED), MissedEntry("lost", 1500, AttemptState.HINTED))

    /** An owner on a save file with [missed] in the same Pack, so no carry-over drops "lost" (F1). */
    private fun owner(): PuzzleOwner {
        PuzzleStore(dir).save(PuzzleData(seeded = true, packSha256 = "A", finished = missed.map { it.id }, missed = missed))
        val owner = PuzzleOwner(dir, { path ->
            if (path.endsWith(".txt")) {
                reads += path to Thread.currentThread()
                hold?.await(5, TimeUnit.SECONDS)
            }
            assets(path)
        }, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        owners += owner
        waitFor("puzzles.json read") { owner.session.value != null }
        return owner
    }

    private fun missedPage(owner: PuzzleOwner, modes: ModeOwner): MenuViewModel {
        val game = game ?: GameOwner(dir, { error("no asset $it") }, host, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
            .also { game = it }
        waitFor("games.json read") { game.state.value != null }
        return MenuViewModel(owner, game, modes, MenuPage.MISSED, overGame = false)
    }

    @Test
    fun `a Missed row the Pack lost is marked gone, and its tap starts nothing and the page stays (N13)`() {
        val owner = owner()
        val modes = ModeOwner.of(dir)
        modes.set(Mode.GAME)
        val page = missedPage(owner, modes)
        waitFor("the Missed check") { "lost" in owner.missedGone.value }
        assertEquals(setOf("lost"), owner.missedGone.value)
        assertEquals(listOf("Missed · 1"), listOf(PuzzlesRows.of(owner.session.value, owner.missedGone.value)[1].text))

        assertFalse(page.replay("lost"), "the page stays: no result to go back with")
        assertNull(owner.session.value!!.replay, "no replay started")
        assertEquals(Mode.GAME, modes.mode.value, "and no mode written")

        assertTrue(page.replay("p1500"))
        assertEquals("p1500", owner.session.value!!.replay!!.puzzle.id)
        assertEquals(Mode.PUZZLES, modes.mode.value)
    }

    @Test
    fun `a tap while the Missed check runs reads no Band file on its thread, and works once the check ends`() {
        val owner = owner()
        val modes = ModeOwner.of(dir)
        val gate = CountDownLatch(1)
        hold = gate
        val page = missedPage(owner, modes)
        val tapper = Thread.currentThread()
        reads.clear()
        // Mid-check a tap replays a Puzzle already read ahead, or waits for the check (false, the
        // page stays); which one depends on how far the check got. Either way it reads no file.
        assertFalse(page.replay("lost"))
        page.replay("p1500")
        assertTrue(synchronized(reads) { reads.none { it.second == tapper } }, "no Band file read on the tapping thread: $reads")
        gate.countDown()
        hold = null
        waitFor("the Missed check") { "lost" in owner.missedGone.value }
        assertTrue(page.replay("p1500"))
        assertTrue(synchronized(reads) { reads.none { it.second == tapper } }, "the replay itself reads nothing on the tapping thread")
        assertEquals("p1500", owner.session.value!!.replay!!.puzzle.id)
    }
}
