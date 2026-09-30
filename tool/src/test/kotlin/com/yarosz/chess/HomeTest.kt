package com.yarosz.chess

import com.yarosz.chess.board.PieceSet
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Home's rows (N1), where Chess opens (N2) and where each row leads. */
class HomeTest {

    @Test
    fun `Home lists every place in N1's order, with its status`() {
        val rows = HomeRows.of(rating = "1727?", missed = 3, yourMove = 2, pieceSet = PieceSet.GEOMETRIC)
        assertEquals(
            listOf(
                "Puzzles", "Player Rating · 1727?", "Missed · 3", "Play the computer", "Play a friend · Your move: 2",
                "Games", "Pieces · Geometric", "About",
            ),
            rows.map { it.text },
        )
        assertEquals(HomeEntry.entries, rows.map { it.entry }, "one row per entry, in the enum's order")
    }

    @Test
    fun `the rating shows once, on its own row under Puzzles (N1)`() {
        val rows = HomeRows.of("1500?", 0, null, PieceSet.ROUNDED).map { it.text }
        assertEquals(1, rows.count { "1500?" in it })
        assertEquals(UiCopy.PUZZLES, rows[0])
        assertEquals(UiCopy.ratingRow("1500?"), rows[1])
        assertTrue("Pieces · Rounded" in rows)
    }

    @Test
    fun `Play a friend shows its count only above 0, and not at all without the Relay (W6, W8)`() {
        assertTrue(UiCopy.PLAY_FRIEND in HomeRows.of("1500?", 0, 0, PieceSet.GEOMETRIC).map { it.text })
        val off = HomeRows.of("1500?", 0, null, PieceSet.GEOMETRIC)
        assertTrue(off.none { it.entry == HomeEntry.PLAY_FRIEND })
        // Games and About are always there, Puzzles included (N1).
        assertTrue(off.any { it.entry == HomeEntry.GAMES } && off.any { it.entry == HomeEntry.ABOUT })
    }

    @Test
    fun `before puzzles json is read the rows show no status, and Pieces waits for its set (M4)`() {
        val rows = HomeRows.of(null, null, null, null)
        assertEquals(listOf("Puzzles", "Player Rating", "Missed", "Play the computer", "Games", "About"), rows.map { it.text })
    }

    @Test
    fun `every Home row fits one line at the LP3's width`() {
        val room = 360f - 2 * 24f
        val widest = HomeRows.of("2999?", 50, 5, PieceSet.GEOMETRIC) + HomeRows.of("2999?", 50, 5, PieceSet.ROUNDED)
        for (row in widest) assertTrue(AkkuratProxy.width(row.text) <= room, "\"${row.text}\" (${AkkuratProxy.width(row.text)} dp)")
    }

    @Test
    fun `Chess opens on the place last used, on top of Home (N2, A10)`() {
        assertEquals(listOf(Place.HOME, Place.PUZZLE), Navigation.launch(Mode.PUZZLES, friendsOn = true))
        assertEquals(listOf(Place.HOME, Place.COMPUTER), Navigation.launch(Mode.GAME, friendsOn = true))
        assertEquals(listOf(Place.HOME, Place.PLAY_FRIEND), Navigation.launch(Mode.FRIEND, friendsOn = true))
        // With the Relay URL empty the friend mode shows the Puzzle (Y6).
        assertEquals(listOf(Place.HOME, Place.PUZZLE), Navigation.launch(Mode.FRIEND, friendsOn = false))
    }

    @Test
    fun `the launch stack follows mode txt, and a missing file opens the Puzzle (R4 10)`() {
        for ((written, place) in listOf(null to Place.PUZZLE, "PUZZLES" to Place.PUZZLE, "GAME" to Place.COMPUTER, "FRIEND" to Place.PLAY_FRIEND, "nonsense" to Place.PUZZLE)) {
            val dir = Files.createTempDirectory("mode").toFile()
            try {
                if (written != null) dir.resolve(ModeOwner.FILE).writeText(written)
                assertEquals(listOf(Place.HOME, place), Navigation.launch(ModeOwner.of(dir).mode.value, friendsOn = true), "mode.txt $written")
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun `a Home row leads one step up, Play the computer to the Game in progress or else the new-game page (R4 11)`() {
        assertEquals(Place.COMPUTER, Navigation.open(HomeEntry.PLAY_COMPUTER, gameInProgress = true))
        assertEquals(Place.NEW_GAME, Navigation.open(HomeEntry.PLAY_COMPUTER, gameInProgress = false))
        assertEquals(Place.PUZZLE, Navigation.open(HomeEntry.PUZZLES, false))
        assertEquals(Place.PLAYER_RATING, Navigation.open(HomeEntry.PLAYER_RATING, false))
        assertEquals(Place.MISSED, Navigation.open(HomeEntry.MISSED, false))
        assertEquals(Place.PLAY_FRIEND, Navigation.open(HomeEntry.PLAY_FRIEND, false))
        assertEquals(Place.GAMES, Navigation.open(HomeEntry.GAMES, false))
        assertEquals(Place.ABOUT, Navigation.open(HomeEntry.ABOUT, false))
        assertNull(Navigation.open(HomeEntry.PIECES, false), "Pieces changes the set in place")
        for (entry in HomeEntry.entries) for (inProgress in listOf(true, false)) {
            assertTrue(Navigation.open(entry, inProgress) != Place.HOME, "no row leads back to Home")
        }
    }

    @Test
    fun `only the three places write mode txt, the pages never (N2)`() {
        assertEquals(Mode.PUZZLES, Navigation.mode(Place.PUZZLE))
        assertEquals(Mode.GAME, Navigation.mode(Place.COMPUTER))
        assertEquals(Mode.FRIEND, Navigation.mode(Place.PLAY_FRIEND))
        for (page in listOf(Place.HOME, Place.NEW_GAME, Place.PLAYER_RATING, Place.MISSED, Place.GAMES, Place.ABOUT)) {
            assertNull(Navigation.mode(page), "$page")
        }
    }
}
