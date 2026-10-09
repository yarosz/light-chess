package com.yarosz.chess

import com.yarosz.chess.puzzles.AttemptState
import com.yarosz.chess.puzzles.Glicko
import com.yarosz.chess.puzzles.HistoryEntry
import com.yarosz.chess.puzzles.MissedEntry
import com.yarosz.chess.puzzles.PuzzleData
import com.yarosz.chess.puzzles.PuzzleFlow
import com.yarosz.chess.puzzles.PuzzleState
import com.yarosz.chess.puzzles.TestPacks
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Puzzles page (N12): its first row for every state of the Puzzle flow and what a tap on it does,
 * Missed with a Puzzle the Pack lost (N13), Past Puzzles (N14) and the Player Rating page (N15).
 */
class PuzzlesPageTest {
    private val lines = (1300..1700 step 50).flatMap { r -> listOf(TestPacks.line("a$r", r), TestPacks.line("b$r", r)) }
    private val flow = PuzzleFlow(TestPacks.of("A", lines), Random(7))

    private fun seeded() = flow.seed(flow.open(null), 1500.0)

    /** Past the hold and the setup Move. */
    private fun ready(s: PuzzleState) = flow.advance(flow.advance(s))

    private fun solve(start: PuzzleState): PuzzleState {
        var s = start
        while (s.attempt!!.underWay) {
            val a = s.attempt!!
            s = if (a.userToMove) flow.play(s, a.puzzle.solution[a.played.size]) else flow.advance(s)
        }
        return s
    }

    private fun wrong(s: PuzzleState): PuzzleState {
        val a = s.attempt!!
        val move = a.position.legalMoves.first { it != a.puzzle.solution[a.played.size] && !a.position.play(it).isCheckmate }
        return flow.play(s, move)
    }

    private fun first(s: PuzzleState?) = PuzzlesRows.of(s).first()

    @Test
    fun `an Attempt under way reads Continue Puzzle, and a tap leaves it as it is`() {
        for (s in listOf(seeded(), ready(seeded()))) {
            assertEquals(PuzzlesStart.CONTINUE, PuzzlesRows.start(s))
            assertEquals(PuzzlesRow("Continue Puzzle", PuzzlesEntry.START), first(s))
            assertSame(s, flow.toRated(s))
        }
    }

    @Test
    fun `Try Mode is under way too`() {
        val trying = wrong(ready(seeded()))
        assertEquals(AttemptState.FAILED, trying.current!!.state)
        assertTrue(trying.current!!.underWay)
        assertEquals(UiCopy.CONTINUE_PUZZLE, first(trying).text)
        assertSame(trying, flow.toRated(trying))
    }

    @Test
    fun `at a Result it reads Next Puzzle, and a tap moves to the next Puzzle before the board opens`() {
        val solved = solve(ready(seeded()))
        assertFalse(solved.current!!.underWay)
        assertEquals(PuzzlesStart.NEXT, PuzzlesRows.start(solved))
        assertEquals("Next Puzzle", first(solved).text)
        val next = flow.toRated(solved)
        assertNotEquals(solved.current!!.puzzle.id, next.current!!.puzzle.id)
        assertTrue(next.current!!.underWay)
        assertEquals(flow.next(solved).current, next.current, "the same as the strip's Next")
        assertEquals(UiCopy.CONTINUE_PUZZLE, first(next).text)
    }

    @Test
    fun `during a Missed replay it reads Back to the rated Puzzle, and a tap ends the replay whatever its stage`() {
        val failed = solve(wrong(ready(seeded())))
        val rated = flow.toRated(failed)
        val ratedId = rated.current!!.puzzle.id
        val missedId = failed.current!!.puzzle.id
        for (replaying in listOf(flow.replayMissed(rated, missedId), ready(flow.replayMissed(rated, missedId)), solve(ready(flow.replayMissed(rated, missedId))))) {
            assertEquals(missedId, replaying.attempt!!.puzzle.id, "the replay is on screen")
            assertEquals(PuzzlesStart.BACK_TO_RATED, PuzzlesRows.start(replaying))
            assertEquals("Back to the rated Puzzle", first(replaying).text)
            val back = flow.toRated(replaying)
            assertNull(back.replay)
            assertEquals(ratedId, back.attempt!!.puzzle.id, "the board opens on the rated Puzzle")
            assertEquals(rated.current, back.current, "which is as it was")
            assertEquals(rated.data.rating, back.data.rating, "and nothing was scored")
        }
        // A replay left under way stays in Missed; a clean one had left (F4).
        assertEquals(listOf(missedId), flow.toRated(ready(flow.replayMissed(rated, missedId))).data.missed.map { it.id })
        assertEquals(emptyList(), flow.toRated(solve(ready(flow.replayMissed(rated, missedId)))).data.missed)
    }

    @Test
    fun `before the seed and after Reset rating it reads Start, and the board asks the seed`() {
        val first = flow.open(null)
        assertEquals(PuzzlesStart.START, PuzzlesRows.start(first))
        assertEquals("Start", first(first).text)
        assertSame(first, flow.toRated(first))
        val reset = flow.resetRating(solve(ready(seeded())))
        assertTrue(reset.needsSeed)
        assertEquals(PuzzlesStart.START, PuzzlesRows.start(reset))
        assertSame(reset, flow.toRated(reset))
        // Reset in Try Mode keeps the scored Attempt, but the seed question comes first (D4).
        val trying = flow.resetRating(wrong(ready(seeded())))
        assertNotNull(trying.current)
        assertEquals(PuzzlesStart.START, PuzzlesRows.start(trying))
        assertTrue(reset.seedScreen && trying.seedScreen, "the board asks the seed")
    }

    @Test
    fun `a Missed replay started before the seed shows first, and Start ends it for the seed screen (N22)`() {
        val failed = solve(wrong(ready(seeded())))
        val missedId = failed.current!!.puzzle.id
        val reset = flow.resetRating(flow.toRated(failed))
        assertNull(reset.replay)
        val replaying = flow.replayMissed(reset, missedId)
        assertEquals(missedId, replaying.attempt!!.puzzle.id)
        assertTrue(replaying.needsSeed)
        assertFalse(replaying.seedScreen, "the board shows the replay the Missed row opened, not the seed screen")
        assertEquals(PuzzlesStart.START, PuzzlesRows.start(replaying), "the row says what a tap does: the seed screen")
        val started = flow.toRated(replaying)
        assertNull(started.replay, "Start ends the replay")
        assertTrue(started.seedScreen, "and the board asks the seed")
        // Next at the replay's end goes to the seed screen too.
        val next = flow.next(solve(ready(replaying)))
        assertNull(next.replay)
        assertTrue(next.seedScreen)
    }

    @Test
    fun `with the Pack used up it is a plain line, in the strip's words`() {
        val one = PuzzleFlow(TestPacks.of("B", listOf(TestPacks.line("only", 1500))), Random(1))
        val done = one.next(solve(one, ready(one, one.seed(one.open(null), 1500.0))))
        assertNull(done.current)
        assertEquals(PuzzlesStart.FINISHED, PuzzlesRows.start(done))
        assertEquals(PuzzlesRow(UiCopy.PACK_FINISHED, PuzzlesEntry.START, tappable = false), first(done))
        assertSame(done, one.toRated(done))
    }

    @Test
    fun `with the Pack used up a Missed replay still reads the finished line, never Back to the rated Puzzle (N22)`() {
        val one = PuzzleFlow(TestPacks.of("B", listOf(TestPacks.line("only", 1500))), Random(1))
        val seeded = one.seed(one.open(null), 1500.0)
        val failed = solve(one, wrongIn(one, ready(one, seeded)))
        val done = one.next(failed)
        assertNull(done.current, "the Pack is used up")
        val replaying = one.replayMissed(done, "only")
        assertNotNull(replaying.replay)
        assertEquals(PuzzlesStart.FINISHED, PuzzlesRows.start(replaying))
        assertEquals(PuzzlesRow(UiCopy.PACK_FINISHED, PuzzlesEntry.START, tappable = false), first(replaying))
    }

    private fun wrongIn(f: PuzzleFlow, s: PuzzleState): PuzzleState {
        val a = s.attempt!!
        val move = a.position.legalMoves.first { it != a.puzzle.solution[a.played.size] && !a.position.play(it).isCheckmate }
        return f.play(s, move)
    }

    private fun ready(f: PuzzleFlow, s: PuzzleState) = f.advance(f.advance(s))

    private fun solve(f: PuzzleFlow, start: PuzzleState): PuzzleState {
        var s = start
        while (s.attempt!!.underWay) {
            val a = s.attempt!!
            s = if (a.userToMove) f.play(s, a.puzzle.solution[a.played.size]) else f.advance(s)
        }
        return s
    }

    @Test
    fun `before puzzles json is read the first row reads Continue Puzzle, and the others show no status`() {
        assertEquals(listOf("Continue Puzzle", "Missed", "Past Puzzles", "Player Rating"), PuzzlesRows.of(null).map { it.text })
    }

    @Test
    fun `the page's rows, the first, Missed with its count, Past Puzzles, the Player Rating (N12)`() {
        val failed = solve(wrong(ready(seeded())))
        val rows = PuzzlesRows.of(failed)
        assertEquals(listOf(PuzzlesEntry.START, PuzzlesEntry.MISSED, PuzzlesEntry.PAST_PUZZLES, PuzzlesEntry.PLAYER_RATING), rows.map { it.entry })
        assertEquals(listOf("Next Puzzle", "Missed · 1", "Past Puzzles", "Player Rating · ${failed.data.player.text}"), rows.map { it.text })
        assertTrue(Regex("""\(\d+\)""").matches(failed.data.player.text), "provisional, in parentheses (Q1)")
        val room = 360f - 2 * 24f
        for (start in PuzzlesStart.entries) {
            val text = UiCopy.puzzlesStart(start)
            assertTrue(AkkuratProxy.width(text) <= room, "\"$text\" fits one line")
        }
        val widest = UiCopy.ratingRow(Glicko(2999.0, 500.0).text)
        assertEquals("Player Rating · (2999)", widest)
        assertTrue(AkkuratProxy.width(widest) <= room, "\"$widest\" fits one line (Q1)")
    }

    @Test
    fun `a Missed Puzzle the Pack no longer has is a lightened line, and its replay doesn't start (N13)`() {
        val missed = listOf(MissedEntry("a1500", 1500, AttemptState.FAILED), MissedEntry("lost", 1500, AttemptState.HINTED))
        // The same Pack (no carry-over): only the page's own check can see it.
        val s = flow.open(PuzzleData(seeded = true, packSha256 = "A", finished = missed.map { it.id }, missed = missed))
        assertEquals(2, s.data.missed.size)
        assertEquals(setOf("lost"), flow.prefetchMissed(s))
        assertTrue(flow.readAhead("a1500"))
        assertFalse(flow.readAhead("lost"))
        assertSame(s, flow.replayMissed(s, "lost"), "the owner tells a replay that didn't start by identity")
        assertNotNull(flow.replayMissed(s, "a1500").replay)
        assertEquals(emptySet(), flow.prefetchMissed(s, known = setOf("lost")), "an id known gone isn't looked for again")

        val rows = PuzzlesPages.missed(s.data.missed, gone = setOf("lost"))
        assertEquals(listOf(MenuItem("1500 · Failed", "a1500"), MenuItem<String>("1500 · Hinted", lighten = true)), rows)
        assertEquals(listOf(MenuItem("1500 · Failed", "a1500"), MenuItem("1500 · Hinted", "lost")), PuzzlesPages.missed(s.data.missed, emptySet()))
        assertEquals(listOf(MenuItem<String>(UiCopy.NO_MISSED, lighten = true)), PuzzlesPages.missed(emptyList(), emptySet()))

        // Missed's count on the Puzzles page counts only the rows that replay.
        assertEquals("Missed · 2", PuzzlesRows.of(s)[1].text)
        assertEquals("Missed · 1", PuzzlesRows.of(s, gone = setOf("lost"))[1].text)
        assertEquals("Missed · 0", PuzzlesRows.of(s, gone = setOf("lost", "a1500"))[1].text)
    }

    @Test
    fun `the Missed check reads each Band file at most once, whatever the number of lost Puzzles (N13)`() {
        val reads = mutableListOf<String>()
        val wide = (800..2400 step 50).map { TestPacks.line("p$it", it) }
        val f = PuzzleFlow(TestPacks.of("A", wide, reads), Random(3))
        val lost = (1..6).map { MissedEntry("lost$it", 800 + it * 250, AttemptState.FAILED) }
        val kept = listOf(MissedEntry("p1200", 1200, AttemptState.HINTED), MissedEntry("p2000", 2000, AttemptState.FAILED))
        val s = f.open(PuzzleData(seeded = true, packSha256 = "A", finished = (lost + kept).map { it.id }, missed = lost + kept))
        reads.clear()
        assertEquals(lost.map { it.id }.toSet(), f.prefetchMissed(s))
        val bands = reads.filter { it.endsWith(".txt") }
        assertEquals(bands.distinct(), bands, "no Band file read twice: $bands")
        assertTrue(bands.size > 6, "a lost Puzzle could be in any Band, so each unloaded one is read: $bands")
        // The Puzzles found are read ahead, so a tap reads nothing.
        reads.clear()
        for (entry in kept) assertEquals(entry.id, f.replayMissed(s, entry.id).replay!!.puzzle.id)
        assertEquals(emptyList(), reads)
        // The next open, with the lost ones known: nothing to read, the kept ones found already.
        assertEquals(emptySet(), f.prefetchMissed(s, known = lost.map { it.id }.toSet()))
        assertEquals(emptyList(), reads)
        assertTrue(kept.all { f.readAhead(it.id) })
    }

    @Test
    fun `Past Puzzles lists the rated Attempts newest first, read-only (N14)`() {
        var s = seeded()
        val ids = mutableListOf<String>()
        repeat(3) { i ->
            val a = ready(s)
            ids += a.current!!.puzzle.id
            s = flow.toRated(if (i == 1) solve(wrong(a)) else solve(a))
        }
        assertEquals(ids.reversed(), s.data.history.map { it.id }, "newest first")
        val rows = PuzzlesPages.past(s.data.history)
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.entry == null && it.lighten }, "not tappable")
        assertEquals(UiCopy.historyRow(s.data.history[1].puzzleRating, AttemptState.FAILED, s.data.history[1].delta, false), rows[1].text)
        assertTrue(rows[1].text.contains("Failed −"), rows[1].text)
        assertTrue(rows[0].text.contains("Solved +"), rows[0].text)
        assertEquals(listOf(MenuItem<Nothing>(UiCopy.NO_HISTORY, lighten = true)), PuzzlesPages.past(emptyList()))
    }

    @Test
    fun `Past Puzzles keeps 100 (N14)`() {
        assertEquals(100, PuzzleData.HISTORY_CAP)
        val old = (1..100).map { HistoryEntry("h$it", 1500, AttemptState.SOLVED, 5) }
        val s = flow.open(PuzzleData(seeded = true, packSha256 = "A", history = old))
        val scored = solve(ready(s))
        assertEquals(100, scored.data.history.size)
        assertEquals(scored.current!!.puzzle.id, scored.data.history.first().id)
        assertEquals("h99", scored.data.history.last().id)
    }

    @Test
    fun `a file of 50 Past Puzzles, the old cap, still reads and grows (N14)`() {
        val fifty = PuzzleData(seeded = true, packSha256 = "A", history = (1..50).map { HistoryEntry("h$it", 1500, AttemptState.SOLVED, 5) })
        val read = PuzzleData.decode(fifty.encode())!!
        assertEquals(fifty.history, read.history)
        assertEquals(51, solve(ready(flow.open(read))).data.history.size)
    }

    @Test
    fun `the save file stays small with 100 Past Puzzles and 100 Missed`() {
        val full = PuzzleData(
            seeded = true,
            packSha256 = "0".repeat(64),
            finished = (1..3000).map { "p%04d".format(it) },
            missed = (1..PuzzleData.MISSED_CAP).map { MissedEntry("m%04d".format(it), 2999, AttemptState.HINTED) },
            history = (1..PuzzleData.HISTORY_CAP).map { HistoryEntry("h%04d".format(it), 2999, AttemptState.FAILED, -999, solutionShown = true) },
        )
        val bytes = full.encode().toByteArray().size
        assertTrue(bytes < 64 * 1024, "puzzles.json is $bytes bytes")
        val history = PuzzleData(history = full.history).encode().length - PuzzleData().encode().length
        assertTrue(history < 10 * 1024, "100 Past Puzzles take $history bytes")
    }

    @Test
    fun `the Player Rating page, the rating, the static line while provisional, and Reset rating (N15, F5)`() {
        val provisional = Glicko(1176.0, 200.0)
        assertEquals(
            listOf(MenuItem("(1176)"), MenuItem(UiCopy.PROVISIONAL_NOTE, lighten = true), MenuItem(UiCopy.RESET_RATING, PlayerRatingEntry.RESET)),
            PuzzlesPages.playerRating(provisional, confirming = false),
        )
        assertEquals("The parentheses go after about 50 rated Puzzles.", UiCopy.PROVISIONAL_NOTE)
        val settled = Glicko(1176.0, 60.0)
        assertEquals(listOf(MenuItem("1176"), MenuItem(UiCopy.RESET_RATING, PlayerRatingEntry.RESET)), PuzzlesPages.playerRating(settled, false))
        assertEquals(MenuItem(UiCopy.RESET_CONFIRM, PlayerRatingEntry.RESET), PuzzlesPages.playerRating(provisional, confirming = true).last())
        // No history on the page any more: it is Past Puzzles' (N14).
        assertTrue(PuzzlesPages.playerRating(provisional, false).none { it.text.contains(" · ") })
        val room = 360f - 2 * 24f
        val words = UiCopy.PROVISIONAL_NOTE.split(' ')
        var lines = 1
        var width = 0f
        for (word in words) {
            val w = AkkuratProxy.width(if (width == 0f) word else " $word")
            if (width + w > room) {
                lines++
                width = AkkuratProxy.width(word)
            } else {
                width += w
            }
        }
        assertTrue(lines <= 2, "the line takes $lines Menu lines")
    }
}
