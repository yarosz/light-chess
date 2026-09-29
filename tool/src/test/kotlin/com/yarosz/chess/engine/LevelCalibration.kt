package com.yarosz.chess.engine

import com.yarosz.chess.rules.Game
import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.Result
import com.yarosz.chess.rules.Side
import com.yarosz.chess.rules.san
import java.io.BufferedReader
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The Mac calibration of the Levels (docs/levels.md): matches between players, and the blunder profile.
 * Everything is node-based and seeded, so a run replays exactly on any machine; only the time differs.
 */
object LevelCalibration {

    /** The LP3's measured search speed (v2 PR 2, non-debuggable build, P50). */
    const val LP3_NODES_PER_SECOND = 558_000L

    /** Level 8 as the LP3 plays it at the default Think time: 3 s of its nodes, without the clock. */
    val LEVEL_8_ON_LP3: LevelSettings =
        Level.EIGHT.settings().copy(nodes = 3 * LP3_NODES_PER_SECOND, wallMs = null)

    /** Settings for a Level as calibrated: Level 8 at the LP3's 3 s, every other Level as it ships. */
    fun calibrated(level: Level): LevelSettings = if (level == Level.EIGHT) LEVEL_8_ON_LP3 else level.settings()

    /** 20 short, balanced openings (6 plies); each is played twice, the colours swapped. */
    val OPENINGS: List<List<String>> = listOf(
        "e2e4 e7e5 g1f3 b8c6 f1b5 a7a6", // Ruy Lopez
        "e2e4 c7c5 g1f3 d7d6 d2d4 c5d4", // Sicilian
        "d2d4 d7d5 c2c4 e7e6 b1c3 g8f6", // Queen's Gambit Declined
        "d2d4 g8f6 c2c4 g7g6 b1c3 f8g7", // King's Indian
        "e2e4 e7e6 d2d4 d7d5 b1c3 f8b4", // French, Winawer
        "e2e4 c7c6 d2d4 d7d5 e4e5 c8f5", // Caro-Kann, advance
        "c2c4 e7e5 b1c3 g8f6 g2g3 d7d5", // English
        "g1f3 d7d5 g2g3 g8f6 f1g2 e7e6", // Reti
        "e2e4 e7e5 g1f3 b8c6 f1c4 f8c5", // Italian
        "d2d4 g8f6 c2c4 e7e6 g1f3 b7b6", // Queen's Indian
        "e2e4 d7d5 e4d5 d8d5 b1c3 d5a5", // Scandinavian
        "e2e4 g7g6 d2d4 f8g7 b1c3 d7d6", // Modern
        "d2d4 d7d5 c2c4 c7c6 g1f3 g8f6", // Slav
        "e2e4 e7e5 g1f3 g8f6 f3e5 d7d6", // Petrov
        "d2d4 f7f5 g2g3 g8f6 f1g2 e7e6", // Dutch
        "e2e4 c7c5 b1c3 b8c6 g2g3 g7g6", // Closed Sicilian
        "d2d4 g8f6 c2c4 e7e6 b1c3 f8b4", // Nimzo-Indian
        "e2e4 e7e5 f2f4 e5f4 g1f3 d7d6", // King's Gambit
        "c2c4 c7c5 g1f3 g8f6 b1c3 b8c6", // Symmetrical English
        "e2e4 d7d6 d2d4 g8f6 b1c3 e7e5", // Pirc / Philidor
    ).map { it.split(' ') }

    /** One move of a player: the Move, its search's score for the mover (if it has one) and its nodes. */
    data class Played(val uci: String, val score: Int?, val nodes: Long)

    /** A player in a calibration Game. [newGame] gets the Game's seed. */
    interface Player : AutoCloseable {
        val name: String
        fun newGame(seed: Long)
        fun move(startFen: String, moves: List<String>): Played
        override fun close() {}
    }

    class LevelSide(override val name: String, private val settings: LevelSettings) : Player {
        private val engine = PirarucuEngine()
        private val player = LevelPlayer(engine)
        private var seed = 0L

        override fun newGame(seed: Long) {
            engine.newGame()
            this.seed = seed
        }

        override fun move(startFen: String, moves: List<String>): Played {
            val chosen = player.play(startFen, moves, settings, seed)
            return Played(chosen.move, chosen.trueScore, chosen.nodes)
        }
    }

    /** Plays a legal Move at random: the floor that Level 1 must beat. */
    class RandomSide : Player {
        override val name = "random"
        private var random = Random(0)
        override fun newGame(seed: Long) {
            random = Random(seed)
        }

        override fun move(startFen: String, moves: List<String>): Played {
            var position = Position.fromFen(startFen)
            for (m in moves) position = position.play(position.moveFromUci(m)!!)
            return Played(position.legalMoves.random(random).uci, null, 0)
        }
    }

    /**
     * Karballo's Elo limiter, from the spike's line server (spikes/karballo/flat/serve), in its own
     * process: Karballo is MIT and stays out of the Tool's build. [elo] 0 = full strength.
     */
    class KarballoSide(bin: String, private val elo: Int, private val nodes: Int, seed: Long) : Player {
        override val name = if (elo == 0) "karballo" else "karballo-$elo"
        private val process = ProcessBuilder(bin, seed.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        private val input = OutputStreamWriter(process.outputStream)
        private val output: BufferedReader = process.inputStream.bufferedReader()

        private fun ask(line: String): String {
            input.write(line + "\n")
            input.flush()
            return checkNotNull(output.readLine()) { "karballo exited" }
        }

        override fun newGame(seed: Long) {
            check(ask("new $elo") == "ok")
        }

        override fun move(startFen: String, moves: List<String>): Played {
            val (uci, score) = ask("go $nodes\t$startFen\t${moves.joinToString(" ")}").split(' ')
            return Played(uci, score.toInt(), nodes.toLong())
        }

        override fun close() {
            runCatching { input.write("quit\n"); input.flush() }
            process.destroy()
        }
    }

    /** How often a Stockfish anchor's search reached the depth where its limiter picks its Move. */
    class PickDepths {
        val moves = AtomicLong()
        val short = AtomicLong()

        fun line(name: String) = "%s: pick depth reached on %d of %d Moves (%.2f%% short)"
            .format(name, moves.get() - short.get(), moves.get(), 100.0 * short.get() / moves.get().coerceAtLeast(1))
    }

    /**
     * Stockfish's Elo limiter (UCI_LimitStrength, UCI_Elo), an external process over UCI: Threads 1,
     * Hash 16, [nodes] per Move. Stockfish is a Mac calibration tool only, never part of the Tool's build.
     *
     * The limiter searches with MultiPV 4 and picks its Move when the search completes depth
     * 1 + level, where level = clamp(((37.2473 e - 40.8525) e + 22.2943) e - 0.311438, 0, 19) and
     * e = (UCI_Elo - 1320) / (3190 - 1320) (Stockfish's search.h). Deeper iterations don't change the
     * Move, so a node budget that reaches the pick depth plays as the limiter was calibrated; [depths]
     * counts the Moves that fell short. The pick's random term is seeded by Stockfish's clock, so its
     * Games don't replay exactly; ours do.
     */
    class StockfishSide(
        bin: String,
        override val name: String,
        private val elo: Int,
        private val nodes: Long,
        private val depths: PickDepths,
    ) : Player {
        private val pickDepth = 1 + pickLevel(elo)
        private val process = ProcessBuilder(bin).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        private val input = OutputStreamWriter(process.outputStream)
        private val output: BufferedReader = process.inputStream.bufferedReader()

        init {
            send("uci")
            await("uciok")
            send("setoption name Threads value 1")
            send("setoption name Hash value 16")
            send("setoption name UCI_LimitStrength value true")
            send("setoption name UCI_Elo value $elo")
        }

        private fun send(line: String) {
            input.write(line + "\n")
            input.flush()
        }

        private fun await(token: String) {
            while (true) {
                val line = checkNotNull(output.readLine()) { "stockfish exited" }
                if (line.trim() == token) return
            }
        }

        override fun newGame(seed: Long) {
            send("ucinewgame")
            send("isready")
            await("readyok")
        }

        override fun move(startFen: String, moves: List<String>): Played {
            send("position fen $startFen" + if (moves.isEmpty()) "" else " moves " + moves.joinToString(" "))
            send("go nodes $nodes")
            var depth = 0
            var score: Int? = null
            var searched = 0L
            while (true) {
                val line = checkNotNull(output.readLine()) { "stockfish exited" }
                val words = line.split(' ')
                if (words.first() == "bestmove") {
                    depths.moves.incrementAndGet()
                    // A mate or a single legal Move can end the search before the pick depth.
                    if (depth < pickDepth && score?.let { kotlin.math.abs(it) < MATE_SCORE / 2 } != false) depths.short.incrementAndGet()
                    return Played(words[1], score, searched)
                }
                if (words.first() != "info" || "multipv" !in words || words[words.indexOf("multipv") + 1] != "1") continue
                if ("depth" in words) depth = words[words.indexOf("depth") + 1].toInt()
                if ("nodes" in words) searched = words[words.indexOf("nodes") + 1].toLong()
                if ("score" in words) {
                    val kind = words[words.indexOf("score") + 1]
                    val value = words[words.indexOf("score") + 2].toInt()
                    score = if (kind == "mate") (if (value > 0) MATE_SCORE - value else -MATE_SCORE - value) else value
                }
            }
        }

        override fun close() {
            runCatching { send("quit") }
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroy()
        }

        companion object {
            const val MATE_SCORE = 30_000

            /** Stockfish's skill level for [elo] (search.h, `Skill`), truncated as `time_to_pick` does. */
            fun pickLevel(elo: Int): Int {
                val e = (elo - 1320).toDouble() / (3190 - 1320)
                return ((((37.2473 * e - 40.8525) * e + 22.2943) * e - 0.311438).coerceIn(0.0, 19.0)).toInt()
            }

            /** The `stockfish` on PATH, or [override]; null when there is none. */
            fun find(override: String?): String? {
                if (override != null) return override.takeIf { File(it).canExecute() }
                return System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
                    .map { File(it, "stockfish") }.firstOrNull { it.canExecute() }?.path
            }
        }
    }

    /** How one Game went: [whiteScore] is 1, 0.5 or 0; [reason] names the ending. */
    data class Outcome(
        val whiteScore: Double,
        val plies: Int,
        val reason: String,
        val nodes: Map<String, List<Long>>,
        val moves: List<String>,
    )

    const val MAX_PLIES = 300
    const val ADJUDICATE_CP = 1_000
    const val ADJUDICATE_PLIES = 10

    /**
     * One Game from the start Position after [opening]. It ends by our rules core (mate, stalemate,
     * repetition, 50 moves, material), at [MAX_PLIES] as a draw, or by adjudication when both players'
     * own searches have agreed on a [ADJUDICATE_CP] edge for [ADJUDICATE_PLIES] plies.
     */
    fun playGame(white: Player, black: Player, opening: List<String>, seed: Long): Outcome {
        white.newGame(seed)
        black.newGame(seed xor 0x5DEECE66DL)
        var game = Game.of()
        val moves = ArrayList<String>()
        for (uci in opening) {
            game += game.position.moveFromUci(uci)!!
            moves += uci
        }
        val nodes = mapOf(white.name to ArrayList<Long>(), black.name to ArrayList<Long>())
        val whiteView = ArrayList<Int?>()
        while (true) {
            game.result?.let { result ->
                val score = when (result) {
                    is Result.Win -> if (result.winner == Side.WHITE) 1.0 else 0.0
                    is Result.Draw -> 0.5
                }
                return Outcome(score, moves.size, result.toString(), nodes, moves)
            }
            if (moves.size >= MAX_PLIES) return Outcome(0.5, moves.size, "ply limit", nodes, moves)
            val recent = whiteView.takeLast(ADJUDICATE_PLIES)
            if (recent.size == ADJUDICATE_PLIES && recent.all { it != null }) {
                if (recent.all { it!! >= ADJUDICATE_CP }) return Outcome(1.0, moves.size, "adjudicated", nodes, moves)
                if (recent.all { it!! <= -ADJUDICATE_CP }) return Outcome(0.0, moves.size, "adjudicated", nodes, moves)
            }
            val whiteToMove = game.position.sideToMove == Side.WHITE
            val mover = if (whiteToMove) white else black
            val played = mover.move(Position.START_FEN, moves)
            val move = requireNotNull(game.position.moveFromUci(played.uci)) { "${mover.name} played illegal ${played.uci}" }
            game += move
            moves += played.uci
            nodes.getValue(mover.name) += played.nodes
            whiteView += played.score?.let { if (whiteToMove) it else -it }
        }
    }

    data class MatchResult(
        val a: String,
        val b: String,
        val games: Int,
        val wins: Int,
        val draws: Int,
        val losses: Int,
        val adjudicated: Int,
        val avgPlies: Int,
        val nodesA: List<Long>,
        val nodesB: List<Long>,
    ) {
        val score: Double get() = wins + draws / 2.0

        /** A's Elo advantage over B, and a 95% interval from the per-game results. */
        fun elo(): Triple<Double, Double, Double> {
            val n = games.toDouble()
            val mean = score / n
            val variance = (wins * (1 - mean) * (1 - mean) + draws * (0.5 - mean) * (0.5 - mean) + losses * mean * mean) / n
            val se = sqrt(variance / n)
            return Triple(eloOf(mean, n), eloOf(mean - 1.96 * se, n), eloOf(mean + 1.96 * se, n))
        }

        fun line(): String {
            val (elo, lo, hi) = elo()
            return "%s vs %s: +%d =%d -%d (%.1f/%d, %d adjudicated, %d plies avg) Elo %+.0f [%+.0f, %+.0f]"
                .format(a, b, wins, draws, losses, score, games, adjudicated, avgPlies, elo, lo, hi)
        }
    }

    fun eloOf(score: Double, games: Double): Double {
        val s = score.coerceIn(0.5 / games, 1 - 0.5 / games)
        return -400 * log10(1 / s - 1)
    }

    /**
     * [OPENINGS] twice each per round (other seeds each round), colours swapped, between two players made fresh for every Game (fresh
     * engines, so Games are independent and can run in parallel on [threads] threads).
     */
    fun match(a: () -> Player, b: () -> Player, threads: Int, rounds: Int = 1, openings: List<List<String>> = OPENINGS): MatchResult =
        matches(listOf(Pairing(a, b)), threads, rounds, openings).single()

    /** A match to play: players made fresh for every Game by [a] and [b]. */
    class Pairing(val a: () -> Player, val b: () -> Player)

    /** One Game as played: who had White, the opening's index and seed, and how it went. */
    data class PlayedGame(val white: String, val black: String, val opening: Int, val seed: Long, val outcome: Outcome)

    /**
     * Every pairing's Games in one pool of [threads], so no thread idles at the end of a match. The seeds
     * are [match]'s, so a pairing replays alone or with others. [onGame] sees each Game as it ends, on
     * the pool's threads. [firstRound] > 0 skips the seeds of earlier rounds, so a later run adds new Games.
     */
    fun matches(
        pairings: List<Pairing>,
        threads: Int,
        rounds: Int = 1,
        openings: List<List<String>> = OPENINGS,
        firstRound: Int = 0,
        onGame: (PlayedGame) -> Unit = {},
    ): List<MatchResult> {
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val games = (firstRound until firstRound + rounds).flatMap { r -> openings.indices.flatMap { i -> listOf(Triple(r, i, true), Triple(r, i, false)) } }
            val jobs = pairings.map { ArrayList<Future<Triple<Double, Outcome, Pair<String, String>>>>() }
            // Game by Game across the pairings, so every pairing progresses at the same rate.
            for ((round, i, aWhite) in games) {
                pairings.forEachIndexed { p, pairing ->
                    jobs[p] += pool.submit(Callable {
                        pairing.a().use { pa ->
                            pairing.b().use { pb ->
                                val seed = 100_000L * round + 1_000L * i + if (aWhite) 1 else 2
                                val outcome = if (aWhite) playGame(pa, pb, openings[i], seed) else playGame(pb, pa, openings[i], seed)
                                val (white, black) = if (aWhite) pa.name to pb.name else pb.name to pa.name
                                onGame(PlayedGame(white, black, i, seed, outcome))
                                val aScore = if (aWhite) outcome.whiteScore else 1 - outcome.whiteScore
                                Triple(aScore, outcome, pa.name to pb.name)
                            }
                        }
                    })
                }
            }
            return jobs.map { list ->
                val results = list.map { it.get() }
                val (na, nb) = results.first().third
                MatchResult(
                    a = na,
                    b = nb,
                    games = results.size,
                    wins = results.count { it.first == 1.0 },
                    draws = results.count { it.first == 0.5 },
                    losses = results.count { it.first == 0.0 },
                    adjudicated = results.count { it.second.reason == "adjudicated" },
                    avgPlies = results.sumOf { it.second.plies } / results.size,
                    nodesA = results.flatMap { it.second.nodes.getValue(na) },
                    nodesB = results.flatMap { it.second.nodes.getValue(nb) },
                )
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /** [game] as PGN, its Moves in SAN from our rules core. */
    fun pgn(game: PlayedGame, event: String): String {
        val result = when (game.outcome.whiteScore) {
            1.0 -> "1-0"
            0.0 -> "0-1"
            else -> "1/2-1/2"
        }
        var position = Position.fromFen(Position.START_FEN)
        val tokens = ArrayList<String>()
        game.outcome.moves.forEachIndexed { ply, uci ->
            val move = position.moveFromUci(uci)!!
            if (ply % 2 == 0) tokens += "${ply / 2 + 1}."
            tokens += position.san(move)
            position = position.play(move)
        }
        tokens += result
        return buildString {
            val tags = listOf(
                "Event" to event,
                "Round" to game.seed.toString(),
                "White" to game.white,
                "Black" to game.black,
                "Result" to result,
                "Opening" to (game.opening + 1).toString(),
                "Termination" to game.outcome.reason,
                "PlyCount" to game.outcome.plies.toString(),
            )
            for ((tag, value) in tags) append("[$tag \"$value\"]\n")
            append('\n')
            var width = 0
            for (token in tokens) {
                if (width > 0 && width + 1 + token.length > 79) {
                    append('\n')
                    width = 0
                }
                if (width > 0) {
                    append(' ')
                    width++
                }
                append(token)
                width += token.length
            }
            append("\n\n")
        }
    }

    /** One Level's blunder profile over the middlegame Positions, judged by [judge]. */
    data class Blunders(val name: String, val samples: Int, val avgCpLoss: Double, val blunders: Int, val bestMoves: Int) {
        /** Blunders scaled to one pass over 40 Positions. */
        val per40: Double get() = blunders * 40.0 / samples

        fun line() = "%s: avg cp loss %.0f, blunders (>= 200 cp) %.1f/40, best Move %d%%"
            .format(name, avgCpLoss, per40, bestMoves * 100 / samples)
    }

    /** Scores Positions at a fixed depth with a fresh engine each time (so the order doesn't matter). */
    class Judge(private val depth: Int) {
        private val cache = HashMap<Pair<String, String?>, Int>()

        /** The score of [fen] (after [move], for the side that played it) at [depth] plies in all. */
        @Synchronized
        fun score(fen: String, move: String? = null): Int = cache.getOrPut(fen to move) {
            val engine = PirarucuEngine()
            if (move == null) {
                engine.search(SearchRequest(fen, limits = SearchLimits(depth = depth))).score
            } else {
                -engine.search(SearchRequest(fen, listOf(move), SearchLimits(depth = depth - 1))).score
            }
        }
    }

    /** Terminal Positions (mate or stalemate after the Move) need no search. */
    private fun terminalScore(fen: String, move: String): Int? {
        val position = Position.fromFen(fen)
        val after = position.play(position.moveFromUci(move)!!)
        return when {
            after.isCheckmate -> 30_000
            after.isStalemate -> 0
            else -> null
        }
    }

    fun blunders(name: String, settings: LevelSettings, positions: List<String>, seeds: Int, judge: Judge, threads: Int): Blunders {
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val jobs = positions.flatMap { fen -> (0 until seeds).map { fen to it.toLong() } }.map { (fen, seed) ->
                pool.submit(Callable {
                    val chosen = LevelPlayer(PirarucuEngine()).play(fen, emptyList(), settings, seed)
                    val best = judge.score(fen)
                    val after = terminalScore(fen, chosen.move) ?: judge.score(fen, chosen.move)
                    val loss = (best - after).coerceIn(0, 1_000)
                    loss to (after >= best)
                })
            }
            val results = jobs.map { it.get() }
            return Blunders(
                name = name,
                samples = results.size,
                avgCpLoss = results.sumOf { it.first }.toDouble() / results.size,
                blunders = results.count { it.first >= 200 },
                bestMoves = results.count { it.second },
            )
        } finally {
            pool.shutdownNow()
        }
    }

    /** Mean and 90th percentile of [nodes], and the P90 as LP3 milliseconds. */
    fun costLine(name: String, nodes: List<Long>): String {
        if (nodes.isEmpty()) return "$name: no moves"
        val sorted = nodes.sorted()
        val p90 = sorted[(sorted.size * 9 / 10).coerceAtMost(sorted.size - 1)]
        val mean = nodes.average()
        return "%s: nodes/move mean %.0f, P90 %d, max %d; LP3 ~%.0f ms mean, ~%.0f ms P90"
            .format(name, mean, p90, sorted.last(), mean * 1000 / LP3_NODES_PER_SECOND, p90 * 1000.0 / LP3_NODES_PER_SECOND)
    }

    fun middlegames(): List<String> =
        File("src/test/resources/engine/middlegames.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
}
