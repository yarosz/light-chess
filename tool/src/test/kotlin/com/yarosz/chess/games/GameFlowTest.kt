package com.yarosz.chess.games

import com.yarosz.chess.book.after
import com.yarosz.chess.book.bookOf
import com.yarosz.chess.engine.EngineHost
import com.yarosz.chess.engine.Level
import com.yarosz.chess.engine.LevelPlayer
import com.yarosz.chess.engine.PirarucuEngine
import com.yarosz.chess.engine.ThinkTime
import com.yarosz.chess.rules.DrawAcceptance
import com.yarosz.chess.rules.DrawOffer
import com.yarosz.chess.rules.DrawReason
import com.yarosz.chess.rules.DrawRefusal
import com.yarosz.chess.rules.Game
import com.yarosz.chess.rules.Move
import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.Resignation
import com.yarosz.chess.rules.Result
import com.yarosz.chess.rules.Side
import com.yarosz.chess.rules.WinReason
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The game screen's state machine (v2 PR 4 brief, "Verification"): the turn flow and locking, Move now,
 * Takeback in both states, Game Hints, draw offers, Resign, automatic draws, Results, resume mid-think,
 * seed replay, the Book to engine handover, and one Game at a time.
 */
class GameFlowTest {

    private fun started(level: Int = 3, side: SideChoice = SideChoice.WHITE, seed: Long = 11L, thinkTime: Int = 3): GameState =
        GameFlow.start(GameFlow.open(null), GameChoices(level, side, thinkTime), seed, "2026.09.28")

    private fun GameState.user(uci: String): GameState {
        val move = checkNotNull(record!!.game.position.moveFromUci(uci)) { uci }
        return GameFlow.play(this, move).also { assertTrue(it !== this, "the user's $uci was refused") }
    }

    /** The computer's [uci] for the turn it is asked, with [score] as its true eval (its own view). */
    private fun GameState.computer(uci: String, score: Int? = 0): GameState {
        val turn = checkNotNull(GameFlow.computerReply(this, book = null)) { "not the computer's Move" }
        return GameFlow.computerMoved(this, turn, uci, score).also { assertTrue(it !== this, "the computer's $uci was refused") }
    }

    private fun uci(state: GameState) = state.record!!.game.moves.map(Move::uci)

    /** A Game in progress from [fen], the user playing [side], as if resumed. */
    private fun from(fen: String, side: Side = Side.WHITE, level: Int = 4): GameState {
        val record = GameRecord(Game.of(Position.fromFen(fen)), side, level = level, seed = 5L)
        return GameState(GameData().save(record), record)
    }

    @Test
    fun `the turn flow, the user moves, the computer thinks, then the user again`() {
        val state = started()
        assertEquals(Phase.USER, state.phase)
        assertNull(GameFlow.computerReply(state, null), "not the computer's Move")
        val thinking = state.user("e2e4")
        assertEquals(Phase.COMPUTER, thinking.phase)
        val turn = assertNotNull(GameFlow.computerReply(thinking, null))
        assertEquals(Level.THREE, turn.request.level)
        assertEquals(11L, turn.request.gameSeed)
        assertEquals(listOf("e2e4"), turn.request.moves)
        val back = GameFlow.computerMoved(thinking, turn, "e7e5", trueScore = 20)
        assertEquals(Phase.USER, back.phase)
        assertEquals(listOf("e2e4", "e7e5"), uci(back))
        assertEquals(mapOf(2 to -20), back.record!!.evals, "Black's +20 is -20 from White's view")
        // Every change is saved as the Game in progress.
        assertEquals(back.record, back.data.resume())
    }

    @Test
    fun `playing Black, the computer moves first`() {
        val state = started(side = SideChoice.BLACK)
        assertEquals(Side.BLACK, state.record!!.userSide)
        assertEquals(Phase.COMPUTER, state.phase)
        assertEquals(Side.BLACK, state.bottom, "the user's Side at the bottom")
        assertEquals(Side.WHITE, GameFlow.flip(state).bottom, "the Menu's board flip")
    }

    @Test
    fun `Random picks a Side from the seed`() {
        val sides = (0L until 40L).map { started(side = SideChoice.RANDOM, seed = it).record!!.userSide }.toSet()
        assertEquals(Side.entries.toSet(), sides)
        assertEquals(started(side = SideChoice.RANDOM, seed = 3L).record!!.userSide, started(side = SideChoice.RANDOM, seed = 3L).record!!.userSide)
    }

    @Test
    fun `the board is locked while the computer thinks`() {
        val thinking = started().user("e2e4")
        val move = thinking.record!!.game.position.moveFromUci("e7e5")!!
        assertSame(thinking, GameFlow.play(thinking, move), "no Move for the user while the computer thinks")
        assertSame(thinking, GameFlow.askHint(thinking), "no Game Hint while the computer thinks")
        assertFalse(thinking.canOfferDraw)
    }

    @Test
    fun `a stale reply is ignored`() {
        val thinking = started().user("e2e4")
        val turn = GameFlow.computerReply(thinking, null)!!
        val takenBack = GameFlow.takeback(thinking)
        assertSame(takenBack, GameFlow.computerMoved(takenBack, turn, "e7e5", 0))
        val replayed = takenBack.user("d2d4")
        assertSame(replayed, GameFlow.computerMoved(replayed, turn, "e7e5", 0), "a reply for another Game Event list")
    }

    @Test
    fun `Move now stops the computer's search, which plays what it has found`() = runBlocking {
        val host = EngineHost { PirarucuEngine() }
        val thinking = started(level = 8, thinkTime = 30).user("e2e4")
        val turn = GameFlow.computerReply(thinking, null)!!
        assertEquals(ThinkTime.THIRTY_SECONDS, turn.request.thinkTime)
        val started = System.nanoTime()
        val move = async { host.play(turn.request) }
        delay(300)
        host.stop()
        val played = move.await()
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(played.moveNow, "the choice ended by Move now")
        assertTrue(ms < 5_000, "Move now at 30 s Think Time took $ms ms")
        val next = GameFlow.computerMoved(thinking, turn, played.move, played.trueScore)
        assertEquals(Phase.USER, next.phase)
    }

    @Test
    fun `a Takeback on the user's turn cuts the user's Move and the reply, and counts`() {
        val state = started().user("e2e4").computer("e7e5").user("g1f3").computer("b8c6")
        assertTrue(state.canTakeBack)
        val back = GameFlow.takeback(state)
        assertEquals(listOf("e2e4", "e7e5"), uci(back))
        assertEquals(1, back.record!!.takebacks)
        assertEquals(Phase.USER, back.phase)
        assertEquals(mapOf(2 to 0), back.record!!.evals)
        assertEquals(back.record, back.data.resume())
    }

    @Test
    fun `a Takeback while the computer thinks cuts only the user's Move`() {
        val thinking = started().user("e2e4").computer("e7e5").user("g1f3")
        assertEquals(Phase.COMPUTER, thinking.phase)
        assertTrue(thinking.canTakeBack)
        val back = GameFlow.takeback(thinking)
        assertEquals(listOf("e2e4", "e7e5"), uci(back))
        assertEquals(Phase.USER, back.phase)
        assertEquals(1, back.record!!.takebacks)
    }

    @Test
    fun `no Takeback before the user's first Move or after the Result`() {
        val fresh = started()
        assertFalse(fresh.canTakeBack)
        assertSame(fresh, GameFlow.takeback(fresh))
        val black = started(side = SideChoice.BLACK).computer("e2e4")
        assertFalse(black.canTakeBack, "the computer's first Move isn't the user's")
        val mated = started().user("f2f3").computer("e7e5").user("g2g4").computer("d8h4")
        assertEquals(Phase.OVER, mated.phase)
        assertFalse(mated.canTakeBack)
    }

    @Test
    fun `a Game Hint is the Level-8 search's Move, shown, and counted once found`() {
        val state = started().user("e2e4").computer("e7e5")
        val asked = GameFlow.askHint(state)
        assertTrue(asked.hintPending)
        assertSame(asked, GameFlow.askHint(asked), "one search at a time")
        val request = assertNotNull(GameFlow.hintRequest(asked))
        assertEquals(ThinkTime.DEFAULT.nodes, request.limits.nodes)
        assertEquals(ThinkTime.DEFAULT.ms, request.limits.wallMs)
        assertEquals(0, asked.record!!.gameHints, "not counted until shown")
        val shown = GameFlow.hintFound(asked, asked.record!!.game, "g1f3")
        assertEquals("g1f3", shown.hint?.uci)
        assertFalse(shown.hintPending)
        assertEquals(1, shown.record!!.gameHints)
        assertEquals(1, shown.data.resume()!!.gameHints, "the count is in the saved record")
        assertNull(GameFlow.hideHint(shown).hint)
        // The user's Move clears it.
        assertNull(shown.user("g1f3").hint)
    }

    @Test
    fun `a Game Hint the user moved past isn't shown or counted`() {
        val asked = GameFlow.askHint(started())
        val moved = asked.user("d2d4")
        assertFalse(moved.hintPending)
        val late = GameFlow.hintFound(moved, asked.record!!.game, "e2e4")
        assertSame(moved, late)
        assertEquals(0, late.record!!.gameHints)
    }

    @Test
    fun `the Game Hint stays the engine's even in the Book`() {
        val book = bookOf(Triple(Position.START, "e2e4", 100), Triple(Position.START.after("e2e4"), "e7e5", 100))
        val state = started(level = 5).user("e2e4")
        assertEquals("e7e5", GameFlow.computerReply(state, book)!!.bookMove?.uci, "the computer plays from the Book")
        val back = state.computer("e7e5")
        assertNotNull(GameFlow.hintRequest(GameFlow.askHint(back)), "the hint searches regardless of the Book")
    }

    @Test
    fun `a draw offer early in the Game is declined at once`() {
        val state = started().user("e2e4").computer("e7e5", score = -300)
        assertTrue(state.canOfferDraw)
        val answered = GameFlow.offerDraw(state)
        assertEquals(DrawResponse.DECLINED, answered.drawResponse)
        assertEquals(Phase.USER, answered.phase)
        assertEquals(listOf(DrawOffer(Side.WHITE), DrawRefusal(Side.BLACK)), answered.record!!.game.events.takeLast(2))
        assertFalse(answered.canOfferDraw, "the re-offer gate closes")
        assertEquals(12, answered.drawOfferFromMove)
        assertNull(answered.user("g1f3").drawResponse, "the answer shows until the next Move")
    }

    @Test
    fun `past move 30 the computer accepts when its true eval is -50 or worse`() {
        val fen = "6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 31"
        assertEquals(DrawResponse.DECLINED, GameFlow.offerDraw(from(fen)).drawResponse, "no eval yet: declined")
        // The computer (Black) searched h7h6 and found -60 for itself: +60 from White's view, whatever
        // Move its Level then played.
        val before = Position.fromFen("6k1/5ppp/8/8/8/8/5PPP/3R2K1 b - - 0 30")
        val record = GameRecord(Game.of(before), Side.WHITE, level = 1, seed = 1L).withComputerMove(before.moveFromUci("h7h6")!!, eval = 60)
        assertEquals(listOf(-60), GameFlow.computerEvals(record))
        val agreed = GameFlow.offerDraw(GameState(GameData().save(record), record))
        assertEquals(DrawResponse.AGREED, agreed.drawResponse)
        assertEquals(listOf(DrawOffer(Side.WHITE), DrawAcceptance(Side.BLACK)), agreed.record!!.game.events.takeLast(2))
        assertEquals(Result.Draw(DrawReason.AGREEMENT), agreed.record!!.game.result)
        assertEquals(Phase.OVER, agreed.phase)
        assertNull(agreed.data.current)
        assertEquals(agreed.record, agreed.data.history().first(), "the finished Game goes to history")
        // The same eval at move 30 is declined, and so is -49.
        assertFalse(DrawJudge.accepts(Position.fromFen("6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 30"), listOf(-60)))
        assertFalse(DrawJudge.accepts(Position.fromFen(fen), listOf(-49)))
        assertTrue(DrawJudge.accepts(Position.fromFen(fen), listOf(-50)))
    }

    @Test
    fun `the dead-level rule, three level evals, move 40, no more than a rook and a minor each`() {
        val even = "6k1/5ppp/8/3b4/8/8/5PPP/3R2K1 w - - 0 40"
        assertTrue(DrawJudge.accepts(Position.fromFen(even), listOf(200, 10, -15, 5)))
        assertFalse(DrawJudge.accepts(Position.fromFen(even), listOf(10, 20, 5)), "one eval 20 cp off level")
        assertFalse(DrawJudge.accepts(Position.fromFen(even), listOf(0, 0)), "only two searches")
        assertFalse(DrawJudge.accepts(Position.fromFen("6k1/5ppp/8/3b4/8/8/5PPP/3R2K1 w - - 0 39"), listOf(0, 0, 0)), "before move 40")
        assertFalse(DrawJudge.accepts(Position.fromFen("6k1/5ppp/8/3q4/8/8/5PPP/3R2K1 w - - 0 45"), listOf(0, 0, 0)), "a queen")
        assertFalse(DrawJudge.accepts(Position.fromFen("r4rk1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 45"), listOf(0, 0, 0)), "two rooks")
        assertTrue(DrawJudge.accepts(Position.fromFen("r5k1/5ppp/8/3b4/8/8/5PPP/2BR2K1 w - - 0 45"), listOf(0, 0, 0)), "a rook and a minor each")
    }

    @Test
    fun `a re-offer is allowed only after 10 more Moves`() {
        var state = GameFlow.offerDraw(started()).also { assertEquals(DrawResponse.DECLINED, it.drawResponse) }
        var moves = 0
        val line = listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6", "d2d3", "f8c5", "c2c3", "d7d6",
            "b1d2", "a7a6", "a2a4", "h7h6", "h2h3", "c8e6", "d1e2", "d8e7", "b2b4", "c5a7")
        for ((i, uci) in line.withIndex()) {
            if (i % 2 == 0) {
                if (moves < 10) assertFalse(state.canOfferDraw, "after $moves Moves")
                state = state.user(uci)
                moves++
            } else {
                state = state.computer(uci)
            }
        }
        assertTrue(state.canOfferDraw, "10 Moves on")
    }

    @Test
    fun `Resign asks for a second tap, which ends the Game`() {
        val state = started().user("e2e4").computer("e7e5")
        val asked = GameFlow.resign(state)
        assertEquals(Confirm.RESIGN, asked.confirming)
        assertNull(asked.record!!.game.result)
        assertNull(GameFlow.cancelConfirm(asked).confirming, "any other tap forgets it")
        assertNull(asked.user("g1f3").confirming)
        val resigned = GameFlow.resign(asked)
        assertEquals(Result.Win(Side.BLACK, WinReason.RESIGNATION), resigned.record!!.game.result)
        assertEquals(Resignation(Side.WHITE), resigned.record!!.game.events.last())
        assertEquals(Phase.OVER, resigned.phase)
        assertNull(resigned.data.current)
        assertSame(resigned, GameFlow.resign(resigned), "no Resign once it's over")
    }

    @Test
    fun `Resign works while the computer thinks`() {
        val thinking = started().user("e2e4")
        val resigned = GameFlow.resign(GameFlow.resign(thinking))
        assertEquals(Phase.OVER, resigned.phase)
    }

    @Test
    fun `automatic draws come from the rules core`() {
        var state = started()
        for ((user, computer) in listOf("g1f3" to "g8f6", "f3g1" to "f6g8", "g1f3" to "g8f6", "f3g1" to "f6g8")) {
            state = state.user(user)
            if (state.phase == Phase.OVER) break
            state = state.computer(computer)
        }
        assertEquals(Result.Draw(DrawReason.REPETITION), state.record!!.game.result)
        assertEquals(Phase.OVER, state.phase)
        assertNull(GameFlow.computerReply(state, null))

        val stalemate = from("k7/8/8/2Q5/8/8/8/7K w - - 0 1").user("c5b6")
        assertEquals(Result.Draw(DrawReason.STALEMATE), stalemate.record!!.game.result)
        val material = from("8/8/8/4k3/8/8/3pK3/8 w - - 0 1").user("e2d2")
        assertEquals(Result.Draw(DrawReason.INSUFFICIENT_MATERIAL), material.record!!.game.result)
        val fifty = from("8/8/8/4k3/8/8/4K3/R7 w - - 99 60").user("a1a2")
        assertEquals(Result.Draw(DrawReason.FIFTY_MOVE_RULE), fifty.record!!.game.result)
    }

    @Test
    fun `a Result by the computer's checkmate ends the Game and goes to history`() {
        val mated = started().user("f2f3").computer("e7e5").user("g2g4").computer("d8h4")
        assertEquals(Result.Win(Side.BLACK, WinReason.CHECKMATE), mated.record!!.game.result)
        assertFalse(mated.inProgress)
        assertEquals(1, mated.data.finished.size)
        // A relaunch shows the finished Game with its Result.
        val reopened = GameFlow.open(GameData.decode(mated.data.encode()))
        assertEquals(Phase.OVER, reopened.phase)
        assertEquals(mated.record!!.game, reopened.record!!.game)
    }

    @Test
    fun `resume mid-think asks the computer for the same Move, with the same seed`() {
        val thinking = started(level = 6, seed = 99L).user("d2d4").computer("d7d5").user("c2c4")
        val before = GameFlow.computerReply(thinking, null)!!
        // onAppPause saved it; the process dies; the file is read back.
        val resumed = GameFlow.open(GameData.decode(thinking.data.encode()))
        assertEquals(Phase.COMPUTER, resumed.phase)
        val after = GameFlow.computerReply(resumed, null)!!
        assertEquals(before, after)
        assertEquals(99L, after.request.gameSeed)
        assertTrue(after.freshEngine, "Levels 1-7 search from a fresh engine, so the Move replays exactly")
        assertFalse(GameFlow.computerReply(started(level = 8).user("e2e4"), null)!!.freshEngine, "Level 8 keeps its table")
    }

    @Test
    fun `seed replay, the same Game and seed give the same Move on a fresh engine`() {
        val thinking = started(level = 3, seed = 1234L).user("e2e4").computer("c7c5").user("g1f3")
        val turn = GameFlow.computerReply(thinking, null)!!
        val engine = PirarucuEngine()
        val first = LevelPlayer(engine).play(turn.request)
        // Something else warms the engine in between (a Game Hint, another Game).
        LevelPlayer(engine).play(turn.request.copy(moves = listOf("d2d4"), gameSeed = 5L))
        engine.newGame()
        assertEquals(first.move, LevelPlayer(engine).play(turn.request).move)
        // A Takeback, then the same Move again: the same request.
        val again = GameFlow.takeback(thinking).user("g1f3")
        assertEquals(turn, GameFlow.computerReply(again, null))
    }

    @Test
    fun `the Book to engine handover`() {
        val start = Position.START
        // Every Move so far must be a book Move, the user's too (book ruling 4).
        val book = bookOf(
            Triple(start, "e2e4", 100),
            Triple(start.after("e2e4"), "c7c5", 100),
            Triple(start.after("e2e4", "c7c5"), "g1f3", 100),
            Triple(start.after("e2e4", "c7c5"), "b1c3", 50),
            Triple(start.after("e2e4", "c7c5", "g1f3"), "d7d6", 100),
        )
        // Level 1 never plays from the Book.
        assertNull(GameFlow.computerReply(started(level = 1).user("e2e4"), book)!!.bookMove)
        val inBook = started(level = 2).user("e2e4")
        assertEquals("c7c5", GameFlow.computerReply(inBook, book)!!.bookMove?.uci)
        val deeper = inBook.computer("c7c5", score = null).user("g1f3")
        assertEquals("d7d6", GameFlow.computerReply(deeper, book)!!.bookMove?.uci)
        assertTrue(deeper.record!!.evals.isEmpty(), "a book Move has no eval")
        // The computer leaves the Book (the Book has nothing after 2... d6), then the engine plays on.
        val out = deeper.computer("d7d6", score = null).user("d2d4")
        assertNull(GameFlow.computerReply(out, book)!!.bookMove)
        // The user leaves the Book: engine, even if a later Position is in the Book.
        val left = inBook.computer("c7c5", score = null).user("a2a3")
        assertNull(GameFlow.computerReply(left, book)!!.bookMove)
        // The seed is the Game's: a resume picks the same book Move.
        val resumed = GameFlow.open(GameData.decode(inBook.data.encode()))
        assertEquals(GameFlow.computerReply(inBook, book), GameFlow.computerReply(resumed, book))
    }

    @Test
    fun `one Game at a time, replacing it needs a second tap and saves it unfinished`() {
        val playing = started().user("e2e4").computer("e7e5")
        assertTrue(playing.inProgress)
        val asked = GameFlow.start(playing, GameChoices(level = 5), seed = 2L, date = "2026.09.29")
        assertEquals(Confirm.REPLACE, asked.confirming)
        assertEquals(playing.record, asked.record, "nothing replaced yet")
        assertEquals(playing.data, asked.data)
        val replaced = GameFlow.start(asked, GameChoices(level = 5), seed = 2L, date = "2026.09.29")
        assertNull(replaced.confirming)
        assertEquals(5, replaced.record!!.level)
        assertEquals(emptyList(), replaced.record!!.game.events)
        val old = replaced.data.history().single()
        assertNull(old.game.result, "saved as unfinished (*)")
        assertEquals(listOf("e2e4", "e7e5"), old.game.moves.map(Move::uci))
        assertEquals(GameChoices(level = 5), replaced.data.choices, "the choices are remembered")
        // With no Game in progress, Start starts at once.
        val done = GameFlow.resign(GameFlow.resign(replaced))
        assertEquals(1, GameFlow.start(done, GameChoices(), 3L, "2026.09.29").record!!.level)
    }

    @Test
    fun `Think Time applies to the Game in progress at Level 8, and to the next Games`() {
        val state = started(level = 8)
        assertEquals(3, state.record!!.thinkTimeSeconds)
        val longer = GameFlow.setThinkTime(state, 30)
        assertEquals(30, longer.record!!.thinkTimeSeconds)
        assertEquals(30, longer.data.choices.thinkTimeSeconds)
        assertEquals(ThinkTime.THIRTY_SECONDS, GameFlow.computerReply(longer.user("e2e4"), null)!!.request.thinkTime)
        val lower = GameFlow.setThinkTime(started(level = 4), 10)
        assertNull(lower.record!!.thinkTimeSeconds, "Think Time changes nothing below Level 8")
        assertNull(started(level = 7).record!!.thinkTimeSeconds)
    }

    @Test
    fun `keep the screen on while the computer thinks, and on the user's Move while input is recent`() {
        val user = started()
        assertTrue(user.awake(recentInput = true))
        assertFalse(user.awake(recentInput = false))
        val thinking = user.user("e2e4")
        assertTrue(thinking.awake(recentInput = false))
        val over = GameFlow.resign(GameFlow.resign(thinking))
        assertFalse(over.awake(recentInput = true))
    }

    @Test
    fun `a file with bad choices opens with defaults`() {
        val data = GameData(choices = GameChoices(level = 12, thinkTimeSeconds = 7))
        assertEquals(GameChoices(level = 1, thinkTimeSeconds = 3), GameFlow.open(data).data.choices)
        assertNull(GameFlow.open(null).record, "no Game ever: the new-game page")
    }
}
