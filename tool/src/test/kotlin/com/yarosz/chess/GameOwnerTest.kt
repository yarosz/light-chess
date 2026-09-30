package com.yarosz.chess

import com.yarosz.chess.engine.Engine
import com.yarosz.chess.engine.EngineHost
import com.yarosz.chess.engine.SearchProgress
import com.yarosz.chess.engine.SearchRequest
import com.yarosz.chess.engine.SearchResult
import com.yarosz.chess.engine.StopHandle
import com.yarosz.chess.games.GameChoices
import com.yarosz.chess.games.GameState
import com.yarosz.chess.games.SideChoice
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The game owner on the JVM, with an engine that searches until it is stopped: what leaving the board
 * (B6, N2) does to a Game Hint being found, and when a page lets the computer think (contradiction 3).
 */
class GameOwnerTest {

    /** An engine whose every search runs until it is stopped (or 10 s); [searches] counts them as they start. */
    class HeldEngine : Engine {
        val searches = Semaphore(0)

        override fun search(request: SearchRequest, handle: StopHandle, progress: (SearchProgress) -> Unit): SearchResult {
            searches.release()
            val deadline = System.currentTimeMillis() + 10_000
            while (!handle.stopped && System.currentTimeMillis() < deadline) Thread.sleep(2)
            return SearchResult("e2e4", 0, 1, 1, 0, fallback = false)
        }

        override fun stop(handle: StopHandle) {
            handle.stopped = true
        }

        override fun newGame() {}
    }

    private val dir: File = Files.createTempDirectory("game-owner").toFile()
    private val engine = HeldEngine()
    private val host = EngineHost { engine }

    /** The owners a test made, stopped before [dir] goes: a late save would write into a deleted directory. */
    private val puzzleOwners = mutableListOf<PuzzleOwner>()

    @AfterTest
    fun cleanUp() {
        host.stop()
        host.shutdown()
        for (owner in puzzleOwners) owner.close()
        ModeOwner.forget(dir)
        dir.deleteRecursively()
    }

    private fun puzzleOwner(): PuzzleOwner =
        PuzzleOwner(dir, { File("src/test/resources/fixture-pack", it).readBytes() }, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
            .also { puzzleOwners += it }

    private fun owner(): GameOwner {
        val owner = GameOwner(dir, { error("no asset $it") }, host, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        waitFor("games.json read") { owner.state.value != null }
        return owner
    }

    private fun GameOwner.now(): GameState = assertNotNull(state.value)

    @Test
    fun `a Game Hint being found when the board leaves is dropped, so the strip offers Hint on return (B6)`() {
        val game = owner()
        game.resume()
        assertTrue(game.start(GameChoices(level = 1, side = SideChoice.WHITE)))
        assertEquals(listOf(GameButton.HINT), GameStrip.of(game.now(), null).buttons)

        game.hint()
        assertTrue(game.now().hintPending)
        assertEquals(UiCopy.FINDING_HINT, GameStrip.of(game.now(), null).status)
        assertTrue(engine.searches.tryAcquire(5, TimeUnit.SECONDS), "the hint's search started")

        // The board leaves for Home (GameScreen's onScreenDestroy) and shows again.
        game.pause()
        game.resume()
        assertFalse(game.now().hintPending, "no Game Hint is pending once the board is back")
        assertEquals(listOf(GameButton.HINT), GameStrip.of(game.now(), null).buttons, "Hint is offered again")

        game.hint()
        assertTrue(game.now().hintPending, "and a tap on it asks again")
        assertTrue(engine.searches.tryAcquire(5, TimeUnit.SECONDS), "with a search of its own")
        game.pause()
    }

    @Test
    fun `New game opened from Home starts no search behind it, and over the board it lets the computer think (contradiction 3, N2)`() {
        val game = owner()
        // The user plays Black: the computer is to move as soon as something resumes the Game.
        assertTrue(game.start(GameChoices(level = 1, side = SideChoice.BLACK)))
        val puzzles = puzzleOwner()
        val modes = ModeOwner.of(dir)

        // Home's Play the computer before games.json is read, or with no Game in progress, opens New game.
        MenuViewModel(puzzles, game, modes, MenuPage.NEW_GAME, overGame = false).shown()
        assertFalse(engine.searches.tryAcquire(300, TimeUnit.MILLISECONDS), "no search behind a page opened from Home")

        // The same page opened from the board's Result (Next) or its Menu sits over the board.
        MenuViewModel(puzzles, game, modes, MenuPage.NEW_GAME, overGame = true).shown()
        assertTrue(engine.searches.tryAcquire(5, TimeUnit.SECONDS), "the computer thinks behind a page over its board")
        game.pause()
    }

    @Test
    fun `the shared engine thread can't be shut down`() {
        assertFailsWith<IllegalStateException> { EngineHost.shared.shutdown(0) }
    }

    companion object {
        fun waitFor(what: String, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 5_000
            while (!condition()) {
                check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
                Thread.sleep(5)
            }
        }
    }
}
