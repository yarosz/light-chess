package com.yarosz.chess

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Home's rows (N11), where Chess opens (N2, N16) and where each row leads. */
class HomeTest {

    @Test
    fun `Home lists five rows in N11's order`() {
        val rows = HomeRows.of(yourMove = 2)
        assertEquals(
            listOf("Puzzles", "Play the computer", "Play a friend · Your move: 2", "Games", "About"),
            rows.map { it.text },
        )
        assertEquals(HomeEntry.entries, rows.map { it.entry }, "one row per entry, in the enum's order")
    }

    @Test
    fun `Pieces, the Player Rating and Missed have left Home (N11, Q1)`() {
        val texts = HomeRows.of(0).map { it.text }
        assertTrue(texts.none { Regex("""\d{3,4}""").containsMatchIn(it) }, "no Home row shows the rating: $texts")
        for (gone in listOf(UiCopy.PIECES, UiCopy.PLAYER_RATING, UiCopy.MISSED)) {
            assertTrue(texts.none { it.startsWith(gone) }, "\"$gone\" is not on Home")
        }
    }

    @Test
    fun `Play a friend shows its count only above 0, and not at all without the Relay (W6, W8)`() {
        assertTrue(UiCopy.PLAY_FRIEND in HomeRows.of(0).map { it.text })
        val off = HomeRows.of(null)
        assertTrue(off.none { it.entry == HomeEntry.PLAY_FRIEND })
        assertEquals(4, off.size)
        // Games and About are always there, Puzzles included (N1).
        assertTrue(off.any { it.entry == HomeEntry.GAMES } && off.any { it.entry == HomeEntry.ABOUT })
    }

    @Test
    fun `every Home row fits one line at the LP3's width`() {
        val room = 360f - 2 * 24f
        for (row in HomeRows.of(5)) assertTrue(AkkuratProxy.width(row.text) <= room, "\"${row.text}\" (${AkkuratProxy.width(row.text)} dp)")
    }

    /**
     * N11 with N10's rule: five rows under the top bar fit the LP3's app area, so Home leaves the wheel
     * with LightOS. The rows measure about 53 dp (a `Copy` line and 12 dp above and below), the bar 40.
     */
    @Test
    fun `Home fits one screen on the LP3, so the wheel stays with LightOS (N11, N10)`() {
        val rowDp = 29f + 2 * 12f
        val rows = HomeRows.of(5).size
        assertTrue(40f + rows * rowDp <= 389f, "${40f + rows * rowDp} dp of 389")
    }

    @Test
    fun `Chess opens on the place last used, the Puzzle board over the Puzzles page (N16, A10)`() {
        assertEquals(listOf(Place.HOME, Place.PUZZLES, Place.PUZZLE), Navigation.launch(Mode.PUZZLES, friendsOn = true))
        assertEquals(listOf(Place.HOME, Place.COMPUTER), Navigation.launch(Mode.GAME, friendsOn = true))
        assertEquals(listOf(Place.HOME, Place.PLAY_FRIEND), Navigation.launch(Mode.FRIEND, friendsOn = true))
        // With the Relay URL empty the friend mode shows the Puzzle (Y6), over its page.
        assertEquals(listOf(Place.HOME, Place.PUZZLES, Place.PUZZLE), Navigation.launch(Mode.FRIEND, friendsOn = false))
    }

    @Test
    fun `the launch stack follows mode txt, and a missing file opens the Puzzle (R4 10)`() {
        val puzzle = listOf(Place.HOME, Place.PUZZLES, Place.PUZZLE)
        for ((written, stack) in listOf(null to puzzle, "PUZZLES" to puzzle, "GAME" to listOf(Place.HOME, Place.COMPUTER), "FRIEND" to listOf(Place.HOME, Place.PLAY_FRIEND), "nonsense" to puzzle)) {
            val dir = Files.createTempDirectory("mode").toFile()
            try {
                if (written != null) dir.resolve(ModeOwner.FILE).writeText(written)
                assertEquals(stack, Navigation.launch(ModeOwner.of(dir).mode.value, friendsOn = true), "mode.txt $written")
            } finally {
                ModeOwner.forget(dir)
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun `a Home row leads one step up, Puzzles to its page, Play the computer to the Game in progress or the new-game page (N11, R4 11)`() {
        assertEquals(Place.COMPUTER, Navigation.open(HomeEntry.PLAY_COMPUTER, gameInProgress = true))
        assertEquals(Place.NEW_GAME, Navigation.open(HomeEntry.PLAY_COMPUTER, gameInProgress = false))
        assertEquals(Place.PUZZLES, Navigation.open(HomeEntry.PUZZLES, false), "the page, not the board (N12)")
        assertEquals(Place.PLAY_FRIEND, Navigation.open(HomeEntry.PLAY_FRIEND, false))
        assertEquals(Place.GAMES, Navigation.open(HomeEntry.GAMES, false))
        assertEquals(Place.ABOUT, Navigation.open(HomeEntry.ABOUT, false))
        for (entry in HomeEntry.entries) for (inProgress in listOf(true, false)) {
            assertTrue(Navigation.open(entry, inProgress) != Place.HOME, "no row leads back to Home")
        }
    }

    @Test
    fun `the Puzzles page's rows lead to the board and to its pages (N16)`() {
        assertEquals(Place.PUZZLE, PuzzlesRows.open(PuzzlesEntry.START))
        assertEquals(Place.MISSED, PuzzlesRows.open(PuzzlesEntry.MISSED))
        assertEquals(Place.PAST_PUZZLES, PuzzlesRows.open(PuzzlesEntry.PAST_PUZZLES))
        assertEquals(Place.PLAYER_RATING, PuzzlesRows.open(PuzzlesEntry.PLAYER_RATING))
    }

    @Test
    fun `the places write mode txt, the pages never (N2, N16)`() {
        assertEquals(Mode.PUZZLES, Navigation.mode(Place.PUZZLES))
        assertEquals(Mode.PUZZLES, Navigation.mode(Place.PUZZLE))
        assertEquals(Mode.GAME, Navigation.mode(Place.COMPUTER))
        assertEquals(Mode.FRIEND, Navigation.mode(Place.PLAY_FRIEND))
        for (page in listOf(Place.HOME, Place.NEW_GAME, Place.PLAYER_RATING, Place.MISSED, Place.PAST_PUZZLES, Place.GAMES, Place.ABOUT)) {
            assertNull(Navigation.mode(page), "$page")
        }
    }

    /**
     * The SDK's back stack as LightActivity keeps it: a level's [HomeNavigator] pushes a screen with the
     * callback its page runs when it goes back with a result; [done] is that (a page popped, then its
     * callback), [back] system Back (popped, no result). Each screen that opens places (Home, the
     * Puzzles page) has a navigator of its own, pushing onto the same stack. [modes] records what
     * reaches `mode.txt`.
     */
    private class Stack {
        val places = mutableListOf(Place.HOME)
        val modes = mutableListOf<Mode>()
        private val callbacks = mutableListOf<(() -> Unit)?>(null)
        private fun navigator() = HomeNavigator(setMode = { modes += it }) { place, onDone ->
            places += place
            callbacks += onDone
        }

        /** Home's navigator, and the Puzzles page's (a screen of its own, the same stack). */
        val home = navigator()
        val puzzles = navigator()

        fun done() {
            places.removeAt(places.lastIndex)
            callbacks.removeAt(callbacks.lastIndex)?.invoke()
        }

        fun back() {
            places.removeAt(places.lastIndex)
            callbacks.removeAt(callbacks.lastIndex)
        }
    }

    @Test
    fun `the launch puts the whole launch stack over Home and writes no mode, and back walks down the levels (N16)`() {
        for (mode in Mode.entries) for (friendsOn in listOf(true, false)) {
            val stack = Stack()
            stack.home.launch(mode, friendsOn)
            val launched = Navigation.launch(mode, friendsOn)
            assertEquals(launched, stack.places, "$mode, Relay ${if (friendsOn) "set" else "empty"}")
            assertEquals(emptyList(), stack.modes, "the launch reads mode.txt, never writes it")
            for (depth in launched.indices.reversed().drop(1)) {
                stack.back()
                assertEquals(launched.take(depth + 1), stack.places, "back from the launch's top goes one level down")
            }
            assertEquals(listOf(Place.HOME), stack.places)
        }
    }

    @Test
    fun `Start replaces the new-game page with the board, so back from the board goes to Home (N2)`() {
        val stack = Stack()
        stack.home.open(Place.NEW_GAME)
        assertEquals(listOf(Place.HOME, Place.NEW_GAME), stack.places)
        assertEquals(emptyList(), stack.modes, "the page writes no mode")
        stack.done()
        assertEquals(listOf(Place.HOME, Place.COMPUTER), stack.places)
        assertEquals(listOf(Mode.GAME), stack.modes)
        stack.back()
        assertEquals(listOf(Place.HOME), stack.places, "not back to the form")

        // System back from the form carries no result: Home, and no board.
        stack.home.open(Place.NEW_GAME)
        stack.back()
        assertEquals(listOf(Place.HOME), stack.places)
    }

    @Test
    fun `from the Puzzles page, a Missed replay and Reset rating replace their page with the board (N16)`() {
        for (page in listOf(Place.MISSED, Place.PLAYER_RATING)) {
            val stack = Stack()
            stack.home.open(Place.PUZZLES)
            stack.puzzles.open(page)
            assertEquals(listOf(Place.HOME, Place.PUZZLES, page), stack.places)
            stack.done()
            assertEquals(listOf(Place.HOME, Place.PUZZLES, Place.PUZZLE), stack.places, "$page")
            assertEquals(listOf(Mode.PUZZLES, Mode.PUZZLES), stack.modes, "$page: the Puzzles page, then the board")
            stack.back()
            assertEquals(listOf(Place.HOME, Place.PUZZLES), stack.places, "back from the board goes to the Puzzles page")
            stack.back()
            assertEquals(listOf(Place.HOME), stack.places)
        }
    }

    @Test
    fun `the Puzzles page's first row puts the board over it, and Past Puzzles only goes back (N16)`() {
        val stack = Stack()
        stack.home.open(Place.PUZZLES)
        stack.puzzles.open(PuzzlesRows.open(PuzzlesEntry.START))
        assertEquals(listOf(Place.HOME, Place.PUZZLES, Place.PUZZLE), stack.places)
        stack.back()
        stack.puzzles.open(Place.PAST_PUZZLES)
        stack.done()
        assertEquals(listOf(Place.HOME, Place.PUZZLES), stack.places, "Past Puzzles replaces nothing")
    }

    @Test
    fun `the stack is never deeper than root, place, page, detail (N16)`() {
        // The deepest: Home, the Puzzles page, the board (its Menu the detail over it).
        for (page in Place.entries) {
            val stack = Stack()
            stack.home.open(Place.PUZZLES)
            stack.puzzles.open(page)
            if (stack.places.size == 3) stack.done()
            assertTrue(stack.places.size <= 3, "${stack.places}")
        }
    }

    @Test
    fun `a place opened from Home writes its mode, a page doesn't, and Games and About replace nothing (N2)`() {
        val stack = Stack()
        for ((place, mode) in listOf(Place.PUZZLES to Mode.PUZZLES, Place.COMPUTER to Mode.GAME, Place.PLAY_FRIEND to Mode.FRIEND)) {
            stack.home.open(place)
            assertEquals(mode, stack.modes.last(), "$place")
            stack.back()
        }
        for (page in listOf(Place.GAMES, Place.ABOUT)) {
            stack.home.open(page)
            stack.done()
            assertEquals(listOf(Place.HOME), stack.places, "$page goes back to Home")
        }
        assertEquals(3, stack.modes.size, "only the three places wrote")
        stack.home.open(Place.HOME)
        assertEquals(listOf(Place.HOME), stack.places, "Home is never pushed over itself")
    }
}
