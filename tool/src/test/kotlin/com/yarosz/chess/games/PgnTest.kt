package com.yarosz.chess.games

import com.yarosz.chess.rules.DrawAcceptance
import com.yarosz.chess.rules.DrawOffer
import com.yarosz.chess.rules.DrawReason
import com.yarosz.chess.rules.DrawRefusal
import com.yarosz.chess.rules.Game
import com.yarosz.chess.rules.GameEvent
import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.RandomGames
import com.yarosz.chess.rules.Resignation
import com.yarosz.chess.rules.Result
import com.yarosz.chess.rules.Side
import com.yarosz.chess.rules.WinReason
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PgnTest {

    /** A Game from [fen] (or the start) through [uci] Moves, each checked legal by the core. */
    private fun game(vararg uci: String, fen: String? = null): Game {
        var game = Game.of(fen?.let(Position::fromFen) ?: Position.START)
        for (text in uci) game += checkNotNull(game.position.moveFromUci(text)) { "$text in ${game.position.fen}" }
        return game
    }

    private fun tags(pgn: String): Map<String, String> =
        Regex("""^\[(\w+) "(.*)"]$""", RegexOption.MULTILINE).findAll(pgn).associate { it.groupValues[1] to it.groupValues[2] }

    private fun movetext(pgn: String): String = pgn.substringAfter("\n\n").trim().replace('\n', ' ')

    private fun assertRoundTrip(record: GameRecord): String {
        val pgn = Pgn.write(record)
        val back = Pgn.read(pgn)
        assertEquals(record, back, pgn)
        assertEquals(record.game.positions, back.game.positions)
        assertEquals(record.game.result, back.game.result)
        assertEquals(pgn, Pgn.write(back))
        for (line in pgn.substringAfter("\n\n").lines()) assertTrue(line.length < 80, "movetext line over 79 columns: $line")
        return pgn
    }

    /**
     * 200 seeded random Games, some from a set-up Position, each ended by the rules, left in progress,
     * resigned or drawn by agreement, with random counters; written and read back equal.
     */
    @Test
    fun `random Games round-trip through PGN`() {
        val results = sortedMapOf<String, Int>()
        for (seed in 0 until 200) {
            val random = Random(seed)
            val start = if (seed % 4 == 0) {
                RandomGames.game(seed + 10_000, maxPlies = 11 + random.nextInt(40)).position.takeIf { it.legalMoves.isNotEmpty() && !it.hasInsufficientMaterial }
                    ?: Position.START
            } else Position.START
            var game = RandomGames.game(seed, start = start, maxPlies = 1 + random.nextInt(RandomGames.MAX_PLIES))
            if (!game.isOver) {
                val toMove = game.position.sideToMove
                game = when (random.nextInt(3)) {
                    0 -> game + Resignation(if (random.nextBoolean()) toMove else toMove.opponent)
                    1 -> game.openDrawOffer?.let { game + DrawAcceptance(it.opponent) } ?: (game + DrawOffer(toMove) + DrawAcceptance(toMove.opponent))
                    else -> game
                }
            }
            results.merge(game.result?.let { Pgn.termination(it) } ?: "*", 1, Int::plus)
            val record = GameRecord(
                game = game,
                userSide = if (random.nextBoolean()) Side.WHITE else Side.BLACK,
                level = if (random.nextInt(10) == 0) null else 1 + random.nextInt(8),
                thinkTimeSeconds = listOf(null, 3, 10, 30).random(random),
                takebacks = random.nextInt(5),
                gameHints = random.nextInt(5),
                date = if (random.nextBoolean()) GameRecord.UNKNOWN_DATE else "2026.%02d.%02d".format(1 + random.nextInt(12), 1 + random.nextInt(28)),
                seed = if (random.nextBoolean()) null else random.nextLong(),
                evals = (1..game.ply).filter { random.nextInt(3) == 0 }.associateWith { random.nextInt(-32_000, 32_000) },
            )
            assertRoundTrip(record)
        }
        println("PGN round trips: 200, endings: $results")
        assertTrue(results.size >= 6, "the random Games should reach most endings: $results")
    }

    @Test
    fun `the Seven Tag Roster comes first, then Termination and our tags`() {
        val record = GameRecord(game("e2e4", "e7e5"), userSide = Side.BLACK, level = 8, thinkTimeSeconds = 10, takebacks = 2, gameHints = 1, date = "2026.09.28")
        val pgn = assertRoundTrip(record + Resignation(Side.WHITE))
        assertEquals(
            """
            [Event "Game against the computer"]
            [Site "?"]
            [Date "2026.09.28"]
            [Round "-"]
            [White "Computer"]
            [Black "You"]
            [Result "0-1"]
            [Termination "Black wins by resignation"]
            [Level "8"]
            [ThinkTime "10"]
            [Takebacks "2"]
            [GameHints "1"]

            1. e4 e5 0-1
            """.trimIndent() + "\n",
            pgn,
        )
    }

    @Test
    fun `each Result has its token and Termination`() {
        val cases = listOf(
            game("f2f3", "e7e5", "g2g4", "d8h4") to ("0-1" to "Black wins by checkmate"),
            (game("e2e4") + Resignation(Side.BLACK)) to ("1-0" to "White wins by resignation"),
            (game("e2e4") + DrawOffer(Side.BLACK) + DrawAcceptance(Side.WHITE)) to ("1/2-1/2" to "Draw by agreement"),
            game("g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1", "f6g8") to ("1/2-1/2" to "Draw by threefold repetition"),
            game("a1a2", fen = "8/8/8/4k3/8/8/4K3/R7 w - - 99 60") to ("1/2-1/2" to "Draw by the 50-move rule"),
            game("e2d2", fen = "8/8/8/4k3/8/8/3pK3/8 w - - 0 1") to ("1/2-1/2" to "Draw by insufficient material"),
            game("c5b6", fen = "k7/8/8/2Q5/8/8/8/7K w - - 0 1") to ("1/2-1/2" to "Draw by stalemate"),
        )
        val reached = mutableSetOf<Result>()
        for ((g, expected) in cases) {
            val pgn = assertRoundTrip(GameRecord(g, level = 3))
            assertEquals(expected.first, tags(pgn)["Result"], pgn)
            assertEquals(expected.second, tags(pgn)["Termination"], pgn)
            assertTrue(movetext(pgn).endsWith(expected.first), pgn)
            reached += g.result!!
        }
        val all = WinReason.entries.map { it.name } + DrawReason.entries.map { it.name }
        assertEquals(all.toSet(), reached.map { if (it is Result.Win) it.by.name else (it as Result.Draw).by.name }.toSet())

        val unfinished = assertRoundTrip(GameRecord(game("e2e4")))
        assertEquals("*", tags(unfinished)["Result"])
        assertNull(tags(unfinished)["Termination"])
        assertEquals("1. e4 *", movetext(unfinished))
    }

    @Test
    fun `draw offers and answers are comment commands in place`() {
        val g = game("e2e4") + DrawOffer(Side.BLACK)
        val refused = g + DrawRefusal(Side.WHITE)
        val record = GameRecord(refused + checkNotNull(refused.position.moveFromUci("e7e5")) + DrawOffer(Side.WHITE))
        val pgn = assertRoundTrip(record)
        assertEquals("1. e4 {[%draw offer black]} {[%draw refuse white]} 1... e5 {[%draw offer white]} *", movetext(pgn))
    }

    @Test
    fun `a set-up start writes SetUp and FEN, and the first Move's number`() {
        val fen = "4k3/8/8/8/8/8/4P3/4K2R b K - 3 12"
        val pgn = assertRoundTrip(GameRecord(game("e8d7", "h1h7", fen = fen)))
        assertEquals("1", tags(pgn)["SetUp"])
        assertEquals(fen, tags(pgn)["FEN"])
        assertEquals("12... Kd7 13. Rh7+ *", movetext(pgn))

        val standard = Pgn.write(GameRecord(game("e2e4")))
        assertFalse("SetUp" in tags(standard) || "FEN" in tags(standard))
    }

    @Test
    fun `castling, promotion and disambiguation SAN`() {
        fun sans(g: Game) = movetext(assertRoundTrip(GameRecord(g)))
        assertEquals(
            "1. e4 e5 2. Nf3 Nc6 3. Bc4 Bc5 4. O-O *",
            sans(game("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "f8c5", "e1g1")),
        )
        assertEquals("1. O-O-O O-O *", sans(game("e1c1", "e8g8", fen = "4k2r/8/8/8/8/8/8/R3K3 w Qk - 0 1")))
        assertEquals("1. b8=Q *", sans(game("b7b8q", fen = "8/1P5p/8/8/8/8/k7/4K3 w - - 0 1")))
        assertEquals("1. b8=N *", sans(game("b7b8n", fen = "8/1P5p/8/8/8/8/k7/4K3 w - - 0 1")))
        assertEquals("1. bxc8=R *", sans(game("b7c8r", fen = "2r5/1P6/8/8/8/8/k7/4K3 w - - 0 1")))
        assertEquals("1. Nbc3 *", sans(game("b1c3", fen = "k7/8/8/8/8/8/8/1N1N2K1 w - - 0 1")))
        assertEquals("1. R4a3 *", sans(game("a4a3", fen = "7k/8/8/8/R7/8/R7/6K1 w - - 0 1")))
        assertEquals("1. Qh4e1 *", sans(game("h4e1", fen = "8/k7/8/8/4Q2Q/8/8/2K4Q w - - 0 1")))
    }

    /** Morphy's Opera Game, hand-written with the extras other PGN carries. */
    private val opera = """
        % a line for another program
        [Event "Paris"]
        [Site "Paris FRA"]
        [Date "1858.??.??"]
        [Round "?"]
        [White "Paul Morphy"]
        [Black "Duke Karl / Count Isouard"]
        [Result "1-0"]
        [ECO "C41"]
        [Annotator "someone \"quoted\""]

        {The Opera Game.} 1.e4 e5 2.Nf3 d6 3.d4 Bg4 $6 {[%clk 0:10:00] pins} 4.dxe5 Bxf3
        5.Qxf3 dxe5 6.Bc4 Nf6 7.Qb3 Qe7 8.Nc3 (8.Qxb7 Qb4+ (8...Qb4+?! {nested} 9.Qxb4)) 8...c6
        9.Bg5 b5?! 10.Nxb5! cxb5 11.Bxb5+ Nbd7 12.O-O-O Rd8 ; a rest-of-line comment
        13.Rxd7 13...Rxd7 14.Rd1 Qe6 15.Bxd7+ Nxd7 16.Qb8+!! Nxb8 17.Rd8# 1-0
    """.trimIndent()

    @Test
    fun `hand-written PGN with comments, NAGs and variations reads, skipping them`() {
        val record = Pgn.read(opera)
        assertEquals(33, record.game.ply)
        assertEquals(Result.Win(Side.WHITE, WinReason.CHECKMATE), record.game.result)
        assertEquals("1858.??.??", record.date)
        assertEquals(Side.WHITE, record.userSide)
        assertNull(record.level)
        assertEquals(0, record.takebacks)
        assertEquals(record.game, Pgn.read(Pgn.write(record)).game)
        assertTrue("17. Rd8# 1-0" in Pgn.write(record))
    }

    @Test
    fun `a decisive Result without checkmate is the loser's resignation`() {
        val record = Pgn.read("[Result \"0-1\"]\n\n1. e4 e5 2. Qh5 0-1\n")
        assertEquals(Resignation(Side.WHITE), record.game.events.last())
        assertEquals(Result.Win(Side.BLACK, WinReason.RESIGNATION), record.game.result)
    }

    @Test
    fun `illegal SAN and Results the Game doesn't reach are rejected`() {
        val bad = listOf(
            "1. e4 e5 2. Ke3 *", // illegal
            "1. e4 Zz9 *", // not SAN
            "[Result \"1/2-1/2\"]\n\n1. e4 1/2-1/2", // a draw nothing in the Game shows
            "[Result \"1-0\"]\n\n1. f3 e5 2. g4 Qh4# 1-0", // Black mated White
            "[Result \"*\"]\n\n1. f3 e5 2. g4 Qh4# *", // over, but written as unfinished
            "[Result \"1-0\"]\n\n1. e4 *", // the tag and the movetext disagree
            "1. e4 {[%draw accept white]} *", // no offer to accept
            "1. e4 (1. d4 *", // an open variation
            "1. e4 * 2. e5", // text after the result
            "[Level \"9\"]\n\n*",
            "[Takebacks \"-1\"]\n\n*",
            "[FEN \"8/8/8/8/8/8/8/8 w - - 0 1\"]\n\n*",
        )
        for (text in bad) assertFailsWith<PgnException>(text) { Pgn.read(text) }
    }

    @Test
    fun `Black's name decides which Side the user played`() {
        assertEquals(Side.BLACK, Pgn.read("[White \"Computer\"]\n[Black \"You\"]\n\n*").userSide)
        assertEquals(Side.WHITE, Pgn.read("[White \"You\"]\n[Black \"Computer\"]\n\n*").userSide)
    }

    @Test
    fun `an empty movetext is a Game at its start`() {
        val record = Pgn.read(Pgn.write(GameRecord(Game.of(), level = 1)))
        assertEquals(emptyList<GameEvent>(), record.game.events)
        assertEquals(1, record.level)
    }

    @Test
    fun `the seed is a tag and each eval follows its Move, in pawns from White's view`() {
        val record = GameRecord(game("e2e4", "e7e5", "g1f3"), userSide = Side.BLACK, level = 5, seed = -42L, evals = mapOf(1 to 25, 3 to -7))
        val pgn = assertRoundTrip(record)
        assertEquals("-42", tags(pgn)["Seed"])
        assertEquals("1. e4 {[%eval 0.25]} 1... e5 2. Nf3 {[%eval -0.07]} *", movetext(pgn))
        // Lichess's own eval comments read the same way; a mate score (#3) is skipped.
        val lichess = Pgn.read("[Result \"*\"]\n\n1. e4 { [%eval 0.18] [%clk 0:03:00] } 1... e5 { [%eval #3] } *")
        assertEquals(mapOf(1 to 18), lichess.evals)
        assertFailsWith<PgnException> { Pgn.read("[Seed \"x\"]\n\n*") }
    }
}
