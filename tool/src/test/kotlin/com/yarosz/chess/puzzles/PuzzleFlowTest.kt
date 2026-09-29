package com.yarosz.chess.puzzles

import com.yarosz.chess.board.PieceSet
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PuzzleFlowTest {

    /** Puzzles every 50 points from 800 to 2000, two at each rating. */
    private val wide = TestPacks.of("A", (800..2000 step 50).flatMap { r -> listOf(TestPacks.line("a$r", r), TestPacks.line("b$r", r)) })
    private val flow = PuzzleFlow(wide, Random(7))

    private fun seeded(rating: Double = 1500.0) = flow.seed(flow.open(null), rating)

    /** Plays the rest of the Solution, correctly. */
    private fun solve(flow: PuzzleFlow, start: PuzzleState): PuzzleState {
        var s = start
        while (s.attempt!!.underWay) {
            val a = s.attempt!!
            s = if (a.userToMove) flow.play(s, a.puzzle.solution[a.played.size]) else flow.advance(s)
        }
        return s
    }

    /** Past the hold and the setup animation. */
    private fun ready(s: PuzzleState) = flow.advance(flow.advance(s))

    private fun wrong(s: PuzzleState): PuzzleState {
        val a = s.attempt!!
        val move = a.position.legalMoves.first { it != a.puzzle.solution[a.played.size] && !a.position.play(it).isCheckmate }
        return flow.play(s, move)
    }

    @Test
    fun `a first launch asks for the seed, then serves a Puzzle within 100`() {
        val first = flow.open(null)
        assertTrue(first.needsSeed)
        assertNull(first.attempt)
        assertTrue(first.firstPuzzle)
        for (seed in listOf(800.0, 1200.0, 1600.0, 2000.0, 1500.0)) {
            val s = flow.seed(first, seed)
            assertFalse(s.needsSeed)
            assertEquals(Glicko(seed, 500.0, 0.09), s.data.player)
            val rating = s.current!!.puzzle.rating
            assertTrue(abs(rating - seed) <= 100, "$rating for $seed")
            assertEquals(Stage.HOLD, s.current!!.stage)
        }
    }

    @Test
    fun `selection widens by 100 while the window is empty`() {
        val sparse = PuzzleFlow(TestPacks.of("S", listOf(TestPacks.line("low", 1000), TestPacks.line("high", 1750))), Random(1))
        val s = sparse.seed(sparse.open(null), 1500.0)
        assertEquals("high", s.current!!.puzzle.id, "1750 is 250 away, 1000 is 500 away")
    }

    @Test
    fun `a finished Puzzle is never served again, until the Pack runs out`() {
        val small = TestPacks.of("T", (1400..1600 step 50).map { TestPacks.line("p$it", it) })
        val f = PuzzleFlow(small, Random(3))
        var s = f.seed(f.open(null), 1500.0)
        val seen = mutableListOf<String>()
        while (s.current != null) {
            seen += s.current!!.puzzle.id
            s = f.next(solve(f, s))
        }
        assertEquals(5, seen.size)
        assertEquals(seen.toSet().size, seen.size, "no Puzzle twice: $seen")
        assertEquals(seen.toSet(), s.data.finished.toSet())
    }

    @Test
    fun `a solve raises the Player Rating once, records it and chooses the next Puzzle`() {
        val start = ready(seeded())
        val puzzle = start.current!!.puzzle
        val solved = solve(flow, start)
        assertEquals(AttemptState.SOLVED, solved.current!!.state)
        assertTrue(solved.data.rating > 1500)
        val delta = solved.data.rating.roundToInt() - 1500
        assertEquals(delta, solved.currentDelta)
        assertEquals(listOf(HistoryEntry(puzzle.id, puzzle.rating, AttemptState.SOLVED, delta)), solved.data.history)
        assertEquals(listOf(puzzle.id), solved.data.finished)
        assertEquals(emptyList(), solved.data.missed)
        val upNext = assertNotNull(solved.upNext, "preloaded once the result is scored")
        assertNotEquals(puzzle.id, upNext.id)
        val next = flow.next(solved)
        assertEquals(upNext, next.current!!.puzzle)
        assertNull(next.currentDelta)
        assertFalse(next.firstPuzzle)
    }

    @Test
    fun `a fail is scored once, at the wrong Move, and Try Mode changes nothing`() {
        val start = ready(seeded())
        val failed = wrong(start)
        val afterFail = failed.data.rating
        assertTrue(afterFail < 1500)
        assertEquals(afterFail.roundToInt() - 1500, failed.currentDelta)
        assertNotNull(failed.upNext, "the next Puzzle preloads once the fail is scored")
        assertEquals(1, failed.data.history.size)
        assertEquals(AttemptState.FAILED, failed.data.missed.single().state)
        val again = wrong(failed)
        val finished = solve(flow, flow.hint(again))
        assertEquals(afterFail, finished.data.rating)
        assertEquals(1, finished.data.history.size)
        assertEquals(AttemptState.FAILED, finished.current!!.state)
        assertEquals(Stage.DONE, finished.current!!.stage)
        assertEquals(failed.upNext, flow.next(finished).current!!.puzzle)
    }

    @Test
    fun `Next waits until the Attempt reaches its result`() {
        val failed = wrong(ready(seeded()))
        assertEquals(failed, flow.next(failed), "after a fail, Next appears once the Solution has played (D1)")
    }

    @Test
    fun `Solution while Open fails and scores the Attempt`() {
        val shown = flow.showSolution(ready(seeded()))
        assertTrue(shown.data.rating < 1500)
        val done = solve(flow, shown)
        assertEquals(shown.data.rating, done.data.rating)
        assertEquals(Stage.DONE, done.current!!.stage)
    }

    @Test
    fun `a Puzzle Hint leaves the Player Rating alone and sends the Puzzle to Missed`() {
        val start = ready(seeded())
        val done = solve(flow, flow.hint(start))
        assertEquals(1500.0, done.data.rating)
        assertEquals(500.0, done.data.deviation)
        val entry = done.data.history.single()
        assertEquals(AttemptState.HINTED, entry.state)
        assertEquals(0, entry.delta)
        assertEquals(start.current!!.puzzle.id, done.data.missed.single().id)
        assertEquals(listOf(start.current!!.puzzle.id), done.data.finished)
    }

    @Test
    fun `a Puzzle Hint after a fail is free`() {
        val failed = wrong(ready(seeded()))
        val hinted = flow.hint(failed)
        assertEquals(failed.data, hinted.data)
        assertEquals(AttemptState.FAILED, hinted.current!!.state)
    }

    @Test
    fun `Missed keeps the last 100, newest first`() {
        val old = (1..100).map { MissedEntry("old$it", 1500, AttemptState.FAILED) }
        val s = flow.open(PuzzleData(seeded = true, missed = old, packSha256 = "A"))
        val failed = wrong(ready(s))
        assertEquals(100, failed.data.missed.size)
        assertEquals(failed.current!!.puzzle.id, failed.data.missed.first().id)
        assertEquals("old99", failed.data.missed.last().id)
    }

    @Test
    fun `a Missed replay is unrated and leaves Missed only when clean`() {
        val failed = solve(flow, wrong(ready(seeded())))
        val id = failed.current!!.puzzle.id
        val scored = failed.data

        val dirty = solve(flow, wrong(ready(flow.replayMissed(failed, id))))
        assertFalse(dirty.rated)
        assertEquals(scored.rating, dirty.data.rating)
        assertEquals(scored.history, dirty.data.history)
        assertEquals(listOf(id), dirty.data.missed.map { it.id }, "a replay with a mistake stays in Missed")
        val back = flow.next(dirty)
        assertNull(back.replay)
        assertEquals(id, back.current!!.puzzle.id, "Next after a replay returns to the rated Puzzle")

        val clean = solve(flow, ready(flow.replayMissed(back, id)))
        assertEquals(emptyList(), clean.data.missed)
        assertEquals(scored.rating, clean.data.rating)
        assertEquals(scored.finished, clean.data.finished)
    }

    @Test
    fun `Reset rating resets only the Player Rating and asks the seed again`() {
        val played = flow.next(solve(flow, wrong(ready(seeded(1600.0)))))
        val reset = flow.resetRating(played)
        assertTrue(reset.needsSeed)
        assertEquals(Glicko.start(), reset.data.player)
        assertEquals(played.data.finished, reset.data.finished)
        assertEquals(played.data.missed, reset.data.missed)
        assertEquals(played.data.history, reset.data.history)
        assertNull(reset.current, "an Open, unplayed Attempt goes back to the pool")
        val reseeded = flow.seed(reset, 800.0)
        assertTrue(reseeded.current!!.puzzle.rating <= 900)

        val failed = wrong(ready(seeded()))
        assertEquals(failed.current, flow.resetRating(failed).current, "a scored Attempt in Try Mode stays")
        val done = solve(flow, failed)
        assertNull(flow.resetRating(done).current, "one at its result gives way to a Puzzle for the new rating")
    }

    @Test
    fun `the Pieces row cycles the Piece Set, saves it at once, and Reset rating keeps it (P2)`() {
        val start = ready(seeded())
        assertEquals(PieceSet.GEOMETRIC, start.data.pieceSet)
        val rounded = flow.nextPieceSet(start)
        assertEquals(PieceSet.ROUNDED, rounded.data.pieceSet)
        assertEquals(start.current, rounded.current, "the Attempt on screen is untouched")
        assertNotEquals(start.kept, rounded.kept, "the change reaches the file at once")
        assertEquals(PieceSet.GEOMETRIC, flow.nextPieceSet(rounded).data.pieceSet, "the last set wraps to the first")

        assertEquals(PieceSet.ROUNDED, flow.open(PuzzleData.decode(rounded.toData().encode())).data.pieceSet, "a relaunch keeps it")
        assertEquals(PieceSet.ROUNDED, flow.resetRating(rounded).data.pieceSet)
    }

    @Test
    fun `the save file round trip resumes the same Puzzle where it was`() {
        val mid = flow.advance(flow.play(wrong(ready(seeded())), seededMove()))
        val text = mid.toData().encode()
        val back = flow.open(PuzzleData.decode(text))
        val a = mid.current!!
        val b = back.current!!
        assertEquals(a.puzzle, b.puzzle)
        assertEquals(a.played, b.played)
        assertEquals(a.state, b.state)
        assertEquals(Stage.PLAY, b.stage)
        assertEquals(mid.data.copy(current = null), back.data)
        assertEquals(mid.currentDelta, back.currentDelta)
    }

    /**
     * The owner's save rule, without Android: the file is written whenever [PuzzleState.kept] changes,
     * as `PuzzleOwner.set` does, and never in onAppPause, which a kill skips. [kill] relaunches from
     * the file as it stands, through the JSON.
     */
    private class Disk(private val flow: PuzzleFlow, start: PuzzleState) {
        var state = start
            private set
        private var file = start.toData().encode()

        fun act(change: (PuzzleState) -> PuzzleState): Disk {
            val next = change(state)
            if (next.kept != state.kept) file = next.toData().encode()
            state = next
            return this
        }

        /** The clock: hold, setup, replies and the Solution, until the user is to move or the result shows. */
        fun settle(): Disk {
            while (state.attempt!!.let { !it.userToMove && it.underWay }) act(flow::advance)
            return this
        }

        fun kill(): PuzzleState = flow.open(PuzzleData.decode(file))
    }

    private fun Disk.wrongMove() = act { wrong(it) }

    private fun Disk.rightMove() = act { flow.play(it, it.attempt!!.puzzle.solution[it.attempt!!.played.size]) }

    @Test
    fun `a kill at a Failed result relaunches into the result, scored once`() {
        for (finish in listOf("solve", "solution")) {
            val disk = Disk(flow, seeded()).settle().wrongMove()
            val scored = disk.state.data
            if (finish == "solve") {
                while (disk.state.attempt!!.underWay) disk.rightMove().settle()
            } else {
                disk.act(flow::showSolution).settle()
            }
            assertEquals(Stage.DONE, disk.state.current!!.stage)
            val back = disk.kill()
            val a = back.current!!
            assertEquals(Stage.DONE, a.stage, "$finish: the result, not the Puzzle mid-way")
            assertEquals(AttemptState.FAILED, a.state)
            assertEquals(disk.state.current!!.played, a.played)
            assertEquals(finish == "solution", a.solutionShown)
            assertEquals(disk.state.currentDelta, back.currentDelta, "the strip reads \"Failed −N\" again")
            assertEquals(scored.rating, back.data.rating, "counted once")
            assertEquals(1, back.data.history.size)
            // Next goes on from there and scores nothing more.
            val next = flow.next(back)
            assertNotEquals(a.puzzle.id, next.current!!.puzzle.id)
            assertEquals(scored.rating, next.data.rating)
        }
    }

    @Test
    fun `a kill in Try Mode relaunches Failed, reading Try again, and never scores it twice`() {
        // A wrong Move after a correct one: the file holds the correct Moves and the wrong one's state.
        val disk = Disk(flow, seeded()).settle().rightMove().settle().wrongMove()
        val scored = disk.state
        val back = disk.kill()
        val a = back.current!!
        assertEquals(AttemptState.FAILED, a.state)
        assertEquals(scored.current!!.played, a.played)
        assertEquals(Stage.PLAY, a.stage)
        assertTrue(a.justWrong, "the strip reads \"Try again\", as before the kill")
        assertEquals(scored.data.rating, back.data.rating)
        assertEquals(scored.currentDelta, back.currentDelta)

        // Relaunched, the Attempt finishes in Try Mode without a second score, and a second kill at
        // its result lands on the result.
        val again = Disk(flow, back).rightMove().settle()
        assertEquals(Stage.DONE, again.state.current!!.stage)
        assertEquals(scored.data.rating, again.state.data.rating)
        assertEquals(1, again.state.data.history.size)
        assertEquals(Stage.DONE, again.kill().current!!.stage)

        // A wrong first Move: nothing played yet, so the Attempt restarts from its setup Move, still Failed.
        val first = Disk(flow, seeded()).settle().wrongMove().kill()
        assertEquals(AttemptState.FAILED, first.current!!.state)
        assertTrue(first.current!!.justWrong)
        assertTrue(first.data.rating < 1500)
        assertEquals(first.data.rating, Disk(flow, first).settle().rightMove().settle().state.data.rating)
    }

    @Test
    fun `a Puzzle Hint then the Solution is saved as shown, and the result reads Hinted, unrated`() {
        val disk = Disk(flow, seeded()).settle().act(flow::hint)
        assertEquals(AttemptState.HINTED, disk.kill().current!!.state, "a kill after the Hint can't make it rated again")
        disk.act(flow::showSolution)
        val mid = disk.kill().current!!
        assertTrue(mid.solutionShown, "saved when the Solution is asked for")
        assertEquals(Stage.SOLUTION, mid.stage)
        disk.settle()
        val back = disk.kill()
        val a = back.current!!
        assertEquals(Stage.DONE, a.stage)
        assertEquals(AttemptState.HINTED, a.state)
        assertTrue(a.solutionShown)
        assertEquals(1500.0, back.data.rating)
        assertEquals(listOf(HistoryEntry(a.puzzle.id, a.puzzle.rating, AttemptState.HINTED, 0, solutionShown = true)), back.data.history)
    }

    @Test
    fun `a result saved with Moves that don't follow the Solution stays at its result, scored once`() {
        for (state in listOf(AttemptState.SOLVED, AttemptState.FAILED, AttemptState.HINTED)) {
            val start = ready(seeded())
            val done = solve(flow, when (state) {
                AttemptState.FAILED -> wrong(start)
                AttemptState.HINTED -> flow.hint(start)
                else -> start
            })
            assertEquals(state, done.current!!.state)
            // A damaged file: the saved Moves can't be replayed against the Solution.
            val damaged = done.toData().let { it.copy(current = it.current!!.copy(moves = listOf("a1a2"))) }
            val back = flow.open(damaged)
            val a = back.current!!
            assertEquals(Stage.DONE, a.stage, "$state: at its result, not restarted from the setup Move")
            assertEquals(state, a.state)
            assertEquals(done.currentDelta, back.currentDelta)
            assertEquals(back, flow.advance(back), "$state: nothing left to play")
            val next = flow.next(back)
            assertNotEquals(a.puzzle.id, next.current!!.puzzle.id)
            assertEquals(done.data.rating, next.data.rating, "$state: not scored again")
            assertEquals(done.data.history, next.data.history, "$state: not recorded again")
            assertEquals(done.data.missed, next.data.missed)
        }
        // Under way, the same damage restarts the Attempt from its setup Move, as before.
        val mid = wrong(ready(seeded()))
        val damaged = mid.toData().let { it.copy(current = it.current!!.copy(moves = listOf("a1a2"))) }
        val restarted = flow.open(damaged).current!!
        assertEquals(Stage.HOLD, restarted.stage)
        assertEquals(AttemptState.FAILED, restarted.state)
    }

    @Test
    fun `the Band files for the next Puzzle are read ahead, so a result reads none`() {
        val lines = (800..2000 step 50).flatMap { r -> listOf(TestPacks.line("a$r", r), TestPacks.line("b$r", r)) }
        for (finish in listOf("solve", "wrong", "hint", "solution")) {
            for (ahead in listOf(false, true)) {
                val reads = mutableListOf<String>()
                val f = PuzzleFlow(TestPacks.of("A", lines, reads), Random(7))
                val start = f.advance(f.advance(f.seed(f.open(null), 1500.0)))
                if (ahead) f.prefetchNext(start)
                reads.clear()
                var s = when (finish) {
                    "wrong" -> start.attempt!!.let { a ->
                        f.play(start, a.position.legalMoves.first { it != a.puzzle.solution[0] && !a.position.play(it).isCheckmate })
                    }
                    "hint" -> f.hint(start)
                    "solution" -> f.showSolution(start)
                    else -> start
                }
                while (s.attempt!!.underWay) {
                    val a = s.attempt!!
                    s = if (a.userToMove) f.play(s, a.puzzle.solution[a.played.size]) else f.advance(s)
                }
                assertNotNull(s.upNext, finish)
                // A Hinted end leaves the rating, whose Bands the first pick read already.
                if (ahead) assertEquals(emptyList(), reads, "$finish: read ahead")
                else if (finish != "hint") assertTrue(reads.isNotEmpty(), "$finish: the test would see a read")
            }
        }
        // After a relaunch at the result, Next picks at the unchanged rating: read ahead too.
        val reads = mutableListOf<String>()
        val f = PuzzleFlow(TestPacks.of("A", lines, reads), Random(7))
        val saved = PuzzleData(seeded = true, packSha256 = "A", rating = 1900.0, finished = listOf("a1500"),
            current = InProgress("a1500", 1500, AttemptState.SOLVED, listOf("f3f2", "e1d1", "f2f1"), done = true, delta = 5))
        val back = f.open(saved)
        f.prefetchNext(back)
        reads.clear()
        assertNotNull(f.next(back).current)
        assertEquals(emptyList(), reads)
    }

    @Test
    fun `Missed Puzzles are read ahead, so opening one reads no file`() {
        val lines = (800..2000 step 50).flatMap { r -> listOf(TestPacks.line("a$r", r), TestPacks.line("b$r", r)) }
        val missed = listOf(MissedEntry("a900", 900, AttemptState.FAILED), MissedEntry("b1950", 1950, AttemptState.HINTED))
        for (ahead in listOf(false, true)) {
            val reads = mutableListOf<String>()
            val f = PuzzleFlow(TestPacks.of("A", lines, reads), Random(7))
            val s = f.open(PuzzleData(seeded = true, packSha256 = "A", finished = missed.map { it.id }, missed = missed))
            if (ahead) f.prefetchMissed(s)
            reads.clear()
            for (entry in missed) assertEquals(entry.id, f.replayMissed(s, entry.id).replay!!.puzzle.id)
            if (ahead) assertEquals(emptyList(), reads) else assertTrue(reads.isNotEmpty(), "the test would see a read")
        }
    }

    /** The first Solution Move of [Lines.MATE_IN_2], which every Puzzle in [wide] shares. */
    private fun seededMove() = Puzzle.parse(Lines.MATE_IN_2).solution[0]

    @Test
    fun `a new Pack carries finished, Missed and the Player Rating, and restarts the Attempt on its new line`() {
        val a = TestPacks.of("A", listOf(TestPacks.line("stays", 1500), TestPacks.line("leaves", 1510), TestPacks.line("other", 1490)))
        val fa = PuzzleFlow(a, Random(1))
        var s = fa.open(PuzzleData(seeded = true, packSha256 = "A", finished = listOf("leaves"), rating = 1700.0,
            missed = listOf(MissedEntry("leaves", 1510, AttemptState.FAILED), MissedEntry("stays", 1500, AttemptState.HINTED))))
        s = s.copy(current = Attempt(a.puzzle("stays")!!, AttemptState.FAILED).advance().advance(), currentDelta = -9)
        s = fa.play(s, s.current!!.puzzle.solution[0])
        val saved = s.toData()
        assertEquals(1, saved.current!!.moves.size)

        // The new Pack: "stays" has a new line (a mate in 1), "leaves" is gone.
        val b = TestPacks.of("B", listOf(Lines.TWO_MATES.replace("twoMates", "stays"), TestPacks.line("new", 1700)))
        val carried = PuzzleFlow(b, Random(1)).open(saved)
        assertEquals("B", carried.data.packSha256)
        assertEquals(listOf("leaves"), carried.data.finished, "finished ids stay even when the Puzzle left")
        assertEquals(listOf("stays"), carried.data.missed.map { it.id })
        assertEquals(1700.0, carried.data.rating)
        val restarted = carried.current!!
        assertEquals("stays", restarted.puzzle.id)
        assertEquals(Stage.HOLD, restarted.stage, "from the setup Move")
        assertEquals(emptyList(), restarted.played)
        assertEquals(AttemptState.FAILED, restarted.state, "Failed stays Failed")
        assertEquals("a1a8", restarted.puzzle.solution.single().uci, "the new line")
        assertEquals(-9, carried.currentDelta)

        // An Attempt whose Puzzle left the Pack is dropped for a fresh one.
        val gone = saved.copy(current = saved.current!!.copy(id = "leaves", puzzleRating = 1510))
        val replaced = PuzzleFlow(b, Random(1)).open(gone)
        assertEquals("new", replaced.current!!.puzzle.id)
        assertEquals(Stage.HOLD, replaced.current!!.stage)
        assertEquals(AttemptState.OPEN, replaced.current!!.state)
    }
}
