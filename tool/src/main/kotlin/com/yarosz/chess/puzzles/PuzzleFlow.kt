package com.yarosz.chess.puzzles

import com.yarosz.chess.rules.Move
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Everything the puzzle mode shows and saves, as one immutable value. [data] holds what the save
 * file keeps; its `current` field is written from [current] by [toData]. [current] is the rated
 * Puzzle's Attempt; [replay] a Missed Puzzle replayed unrated on top of it (D2), which the file
 * doesn't keep. [upNext] is the Puzzle Next opens, chosen once the result is scored (D1).
 */
data class PuzzleState(
    val data: PuzzleData,
    val current: Attempt? = null,
    val currentDelta: Int? = null,
    val replay: Attempt? = null,
    val upNext: Puzzle? = null,
) {
    /** The Attempt on screen. */
    val attempt: Attempt? get() = replay ?: current

    val rated: Boolean get() = replay == null

    /** The seed screen comes first (D4, F6). */
    val needsSeed: Boolean get() = !data.seeded

    /** No Puzzle has ever been scored: the strip teaches the input until the first Move (F6). */
    val firstPuzzle: Boolean get() = data.finished.isEmpty() && data.history.isEmpty()

    fun toData(): PuzzleData = data.copy(
        current = current?.let {
            InProgress(
                id = it.puzzle.id,
                puzzleRating = it.puzzle.rating,
                state = it.state,
                moves = it.played.map(Move::uci),
                solutionShown = it.solutionShown,
                justWrong = it.justWrong,
                done = it.stage == Stage.DONE,
                delta = currentDelta,
            )
        },
    )

    /**
     * What a change must reach the file for: everything [toData] keeps except the Moves of the
     * Attempt on screen, which onAppPause writes. So every result, a Puzzle Hint, the Solution asked
     * for and a wrong Move in Try Mode are saved at once, and a kill at any of them relaunches into it.
     */
    val kept: PuzzleData get() = toData().let { it.copy(current = it.current?.copy(moves = emptyList())) }
}

/**
 * The puzzle mode's rules over a [PuzzleState]: opening the save file against the [pack], the Attempt's
 * transitions and their scoring (A3, A6, A7), selection (A7), Missed (D2, F4), Reset rating (F5)
 * and the Pack-update carry-over (F1). Pure: every call returns the next PuzzleState; [random] picks
 * among the candidates.
 */
class PuzzleFlow(private val pack: Pack, private val random: Random = Random.Default) {

    /** The PuzzleState for [saved] (null: a first launch), carried over to this Pack if it changed (F1). */
    fun open(saved: PuzzleData?): PuzzleState {
        var data = saved ?: PuzzleData()
        val sha = pack.manifest.packSha256
        val newPack = data.packSha256 != null && data.packSha256 != sha
        var current: Attempt? = null
        var delta: Int? = null
        val progress = data.current
        if (newPack) {
            val ids = pack.ids
            data = data.copy(missed = data.missed.filter { it.id in ids })
            // An Attempt still under way restarts from the setup Move on the new line, keeping its
            // state; one whose Puzzle left the Pack is dropped, and so is one already at its result.
            if (progress != null && !progress.done && progress.id in ids) {
                current = pack.puzzle(progress.id, progress.puzzleRating)?.let { Attempt(it, progress.state) }
                delta = progress.delta
            }
        } else if (progress != null) {
            val puzzle = pack.puzzle(progress.id, progress.puzzleRating)
            if (puzzle != null) {
                // Moves that don't follow the Solution come only from a damaged file. An Attempt under
                // way then restarts from the setup Move; one at its result stays there, with no Moves,
                // since it was scored and recorded already and playing it again would do both twice.
                current = Attempt.resume(puzzle, progress.state, progress.moves, progress.solutionShown, progress.done, progress.justWrong)
                    ?: if (progress.done) Attempt(puzzle, progress.state, stage = Stage.DONE, solutionShown = progress.solutionShown)
                    else Attempt(puzzle, progress.state)
                delta = progress.delta
            }
        }
        data = data.copy(packSha256 = sha, current = null)
        val session = PuzzleState(data, current, delta)
        return if (session.data.seeded && current == null) session.copy(current = pick(session)?.let(::Attempt)) else session
    }

    /** The seed screen's answer (D4): a Player Rating at [rating], RD 500. */
    fun seed(session: PuzzleState, rating: Double): PuzzleState {
        val data = session.data.withPlayer(Glicko.start(rating)).copy(seeded = true)
        val next = session.copy(data = data, upNext = null)
        return if (next.current == null) next.copy(current = pick(next)?.let(::Attempt)) else next
    }

    /**
     * "Reset rating" (F5): only the Player Rating, and the seed screen asks again. Finished Puzzles,
     * Missed and the history stay. A rated Attempt still Open goes back to the pool unplayed, and one
     * at its result gives way, so the next Puzzle suits the new rating; one scored but still under
     * way (Try Mode) stays.
     */
    fun resetRating(session: PuzzleState): PuzzleState {
        val data = session.data.withPlayer(Glicko.start()).copy(seeded = false)
        val current = session.current?.takeIf { it.underWay && it.state != AttemptState.OPEN }
        return PuzzleState(data, current, if (current == null) null else session.currentDelta)
    }

    /** The Menu's Pieces row (P2): the next Piece Set, saved with the rest; play is untouched. */
    fun nextPieceSet(session: PuzzleState): PuzzleState =
        session.copy(data = session.data.copy(pieceSet = session.data.pieceSet.next))

    fun play(session: PuzzleState, move: Move): PuzzleState = step(session) { it.play(move) }

    fun hint(session: PuzzleState): PuzzleState = step(session) { it.hint() }

    fun showSolution(session: PuzzleState): PuzzleState = step(session) { it.showSolution() }

    fun advance(session: PuzzleState): PuzzleState = step(session) { it.advance() }

    /**
     * Next (D1): after a replay, back to the rated Puzzle; after a rated result, the Puzzle chosen
     * when it was scored. A no-op while the Attempt is under way.
     */
    fun next(session: PuzzleState): PuzzleState {
        if (session.replay != null) return if (session.replay.underWay) session else session.copy(replay = null)
        if (session.current?.underWay == true) return session
        val puzzle = session.upNext ?: pick(session)
        return session.copy(current = puzzle?.let(::Attempt), currentDelta = null, upNext = null)
    }

    /** Replays a Missed Puzzle, unrated, on top of the rated one (D2). */
    fun replayMissed(session: PuzzleState, id: String): PuzzleState {
        val entry = session.data.missed.firstOrNull { it.id == id } ?: return session
        val puzzle = pack.puzzle(entry.id, entry.puzzleRating) ?: return session
        return session.copy(replay = Attempt(puzzle))
    }

    /**
     * A Puzzle for the Player Rating (A7): a random one within ±100, the window widening by 100
     * while it is empty, never one already finished or on screen. Null when the Pack is used up.
     */
    fun pick(session: PuzzleState): Puzzle? {
        val exclude = session.data.finished.toHashSet()
        session.current?.let { exclude += it.puzzle.id }
        val candidates = pack.candidates(session.data.rating.roundToInt(), exclude)
        return if (candidates.isEmpty()) null else candidates[random.nextInt(candidates.size)]
    }

    /**
     * Reads ahead the Band files [pick] will need for the Puzzle after the rated one on screen, so
     * that choosing it at the result (D1) reads no file on the caller's thread. The window is around
     * the Player Rating as the result leaves it: after a win or a loss while the Attempt is Open, and
     * unchanged for a Hinted end or a Next after a relaunch. For a background thread; changes nothing.
     */
    fun prefetchNext(session: PuzzleState) {
        val attempt = session.current ?: return
        val player = session.data.player
        val ratings = buildSet {
            add(player.rating)
            if (attempt.underWay && attempt.state == AttemptState.OPEN) {
                for (win in listOf(true, false)) add(rated(player, attempt.puzzle, win).rating)
            }
        }
        val exclude = session.data.finished.toHashSet().apply { add(attempt.puzzle.id) }
        for (rating in ratings) pack.candidates(rating.roundToInt(), exclude)
    }

    /** Reads ahead every Missed Puzzle, so that [replayMissed] reads no file (D2). For a background thread. */
    fun prefetchMissed(session: PuzzleState) = pack.prefetch(session.data.missed.associate { it.id to it.puzzleRating })

    private fun step(session: PuzzleState, change: (Attempt) -> Attempt): PuzzleState {
        val replay = session.replay
        if (replay != null) {
            val after = change(replay)
            var data = session.data
            // A clean replay takes the Puzzle out of Missed, silently (F4).
            if (replay.underWay && !after.underWay && after.clean) {
                data = data.copy(missed = data.missed.filterNot { it.id == after.puzzle.id })
            }
            return session.copy(data = data, replay = after)
        }
        val before = session.current ?: return session
        val after = change(before)
        var next = session.copy(current = after)
        val failedNow = before.state == AttemptState.OPEN && after.state == AttemptState.FAILED
        val endedNow = before.underWay && !after.underWay
        if (failedNow) next = score(next, after, win = false)
        if (endedNow && after.state == AttemptState.SOLVED) next = score(next, after, win = true)
        if (endedNow && after.state == AttemptState.HINTED) next = record(next, after, delta = 0)
        return next
    }

    /** One rating period: the Attempt against the Puzzle's rating and its own RD (A7, "Pack fill"). */
    private fun score(session: PuzzleState, attempt: Attempt, win: Boolean): PuzzleState {
        val old = session.data.player
        val new = rated(old, attempt.puzzle, win)
        val delta = new.rating.roundToInt() - old.rating.roundToInt()
        return record(session.copy(data = session.data.withPlayer(new)), attempt, delta)
    }

    private fun rated(player: Glicko, puzzle: Puzzle, win: Boolean): Glicko = Glicko2.update(
        player,
        listOf(Glicko2.Game(puzzle.rating.toDouble(), puzzle.ratingDeviation.toDouble(), if (win) 1.0 else 0.0)),
    )

    /** The result goes in the history, the Puzzle is finished, a Failed or Hinted one joins Missed, and Next is chosen. */
    private fun record(session: PuzzleState, attempt: Attempt, delta: Int): PuzzleState {
        val puzzle = attempt.puzzle
        val data = session.data
        val entry = HistoryEntry(puzzle.id, puzzle.rating, attempt.state, delta, attempt.solutionShown)
        val missed = if (attempt.state == AttemptState.SOLVED) data.missed else {
            (listOf(MissedEntry(puzzle.id, puzzle.rating, attempt.state)) + data.missed.filterNot { it.id == puzzle.id })
                .take(PuzzleData.MISSED_CAP)
        }
        val recorded = session.copy(
            data = data.copy(
                finished = if (puzzle.id in data.finished) data.finished else data.finished + puzzle.id,
                history = (listOf(entry) + data.history).take(PuzzleData.HISTORY_CAP),
                missed = missed,
            ),
            currentDelta = delta,
        )
        return recorded.copy(upNext = pick(recorded))
    }
}
