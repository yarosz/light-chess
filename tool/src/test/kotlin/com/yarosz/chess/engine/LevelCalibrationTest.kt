package com.yarosz.chess.engine

import com.yarosz.chess.engine.LevelCalibration.KarballoSide
import com.yarosz.chess.engine.LevelCalibration.LevelSide
import com.yarosz.chess.engine.LevelCalibration.RandomSide
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * The Level calibration on this machine's JVM (docs/levels.md). Skipped unless -Dcalibrate=<modes> is
 * set, a comma-separated list of:
 *
 * - `blunders`: each Level's average centipawn loss and >= 200 cp errors on 40 middlegame Positions,
 *   judged by the engine at depth 12 (`calibrate.seeds` picks per Position, default 5).
 * - `ladder`: each Level against the next, 40 Games (20 openings, colours swapped).
 * - `random`: Level 1 against a random mover, 40 Games.
 * - `anchor`: Levels against Karballo's Elo limiter; needs `-Dcalibrate.karballo=<spike serve bin>`
 *   and plays `calibrate.elos` (default 500,1000,1500) against the Levels in `calibrate.levels`.
 * - `stockfish`: Levels against Stockfish's UCI_Elo limiter (see [stockfish]); skipped when no
 *   `stockfish` is on PATH (`calibrate.stockfish=<bin>` names one), 1M nodes per Move unless
 *   `calibrate.stockfish.nodes` says otherwise.
 *
 * `calibrate.levels=1-4` limits the Levels, `calibrate.threads` the parallel Games (default 6),
 * `calibrate.rounds` repeats every match with new seeds (40 Games a round), and
 * `calibrate.set=3=400/4/150/4;5=...` tries other settings (nodes/topN/margin[/depth]) without
 * editing [Level]. Lines go to stdout and build/calibration.txt.
 */
class LevelCalibrationTest {

    private val modes = System.getProperty("calibrate")?.split(',')?.map { it.trim() }?.toSet().orEmpty()
    private val threads = System.getProperty("calibrate.threads")?.toInt() ?: 6
    private val rounds = System.getProperty("calibrate.rounds")?.toInt() ?: 1
    private val out = File("build/calibration.txt")

    private val levels: List<Level> = System.getProperty("calibrate.levels")?.let { spec ->
        val (from, to) = spec.split('-').map { it.toInt() }.let { it.first() to it.last() }
        (from..to).map { Level.of(it) }
    } ?: Level.entries

    private val overrides: Map<Int, LevelSettings> = System.getProperty("calibrate.set").orEmpty()
        .split(';').filter { it.isNotBlank() }.associate { entry ->
            val (level, values) = entry.split('=')
            val v = values.split('/')
            level.toInt() to LevelSettings(
                nodes = v[0].toLong(),
                depth = v.getOrNull(3)?.toInt(),
                topN = v[1].toInt(),
                marginCp = v[2].toInt(),
                wallMs = null,
            )
        }

    private fun settings(level: Level) = overrides[level.number] ?: LevelCalibration.calibrated(level)

    private fun name(level: Level) = "L${level.number}"

    private fun say(line: String) {
        println("Calibration: $line")
        out.appendText(line + "\n")
    }

    @Test
    fun `calibrate the Levels`() {
        assumeTrue("set -Dcalibrate=blunders,ladder,random,anchor,stockfish", modes.isNotEmpty())
        out.parentFile.mkdirs()
        say("--- ${java.time.LocalDateTime.now()} modes=$modes levels=${levels.map { it.number }} threads=$threads")
        for (level in levels) say("${name(level)} = ${settings(level)}")
        if ("blunders" in modes) blunders()
        if ("random" in modes) random()
        if ("ladder" in modes) ladder()
        if ("anchor" in modes) anchor()
        if ("stockfish" in modes) stockfish()
    }

    private fun blunders() {
        val seeds = System.getProperty("calibrate.seeds")?.toInt() ?: 5
        val judge = LevelCalibration.Judge(depth = 12)
        val positions = LevelCalibration.middlegames()
        for (level in levels) {
            val started = System.nanoTime()
            val profile = LevelCalibration.blunders(name(level), settings(level), positions, seeds, judge, threads)
            say(profile.line() + " (${(System.nanoTime() - started) / 1_000_000_000} s)")
        }
    }

    private fun random() {
        val result = LevelCalibration.match({ LevelSide("L1", settings(Level.ONE)) }, { RandomSide() }, threads)
        say(result.line())
    }

    private fun ladder() {
        for ((weaker, stronger) in levels.zipWithNext()) {
            val started = System.nanoTime()
            val result = LevelCalibration.match(
                { LevelSide(name(stronger), settings(stronger)) },
                { LevelSide(name(weaker), settings(weaker)) },
                threads,
                rounds,
            )
            say(result.line() + " (${(System.nanoTime() - started) / 1_000_000_000} s)")
            say(LevelCalibration.costLine(name(stronger), result.nodesA))
            say(LevelCalibration.costLine(name(weaker), result.nodesB))
        }
    }

    private fun anchor() {
        val bin = System.getProperty("calibrate.karballo")
        assumeTrue("set -Dcalibrate.karballo=<spikes/karballo/flat serve bin>", bin != null && File(bin).canExecute())
        val nodes = System.getProperty("calibrate.karballo.nodes")?.toInt() ?: KARBALLO_NODES
        val elos = System.getProperty("calibrate.elos")?.split(',')?.map { it.trim().toInt() } ?: listOf(500, 1000, 1500)
        for (elo in elos) {
            for (level in levels) {
                val started = System.nanoTime()
                var game = 0L
                val result = LevelCalibration.match(
                    { LevelSide(name(level), settings(level)) },
                    { KarballoSide(bin!!, elo, nodes = nodes, seed = synchronized(this) { ++game }) },
                    threads,
                    rounds,
                )
                say(result.line() + " (${(System.nanoTime() - started) / 1_000_000_000} s)")
            }
        }
    }

    /**
     * Levels against Stockfish's UCI_Elo limiter. `calibrate.plan=1-2@1320,1500;3@1400,1700:1000000`
     * names the anchors per Level, each at `calibrate.stockfish.nodes` unless `:nodes` follows it (its
     * name then says so: "sf-1700-1000k"). Without a plan, `calibrate.levels` × `calibrate.elos`
     * (default 1320,1600,2000). `calibrate.firstRound=n` starts at round n, so a later run plays new
     * seeds. Every pairing shares one pool; Games go to `calibrate.pgn` (default
     * build/calibration-stockfish.pgn).
     */
    private fun stockfish() {
        val bin = LevelCalibration.StockfishSide.find(System.getProperty("calibrate.stockfish"))
        assumeTrue("stockfish is not on PATH (or set -Dcalibrate.stockfish=<bin>)", bin != null)
        val nodes = System.getProperty("calibrate.stockfish.nodes")?.toLong() ?: STOCKFISH_NODES
        fun anchor(spec: String): Pair<Int, Long> =
            spec.trim().split(':').let { it[0].toInt() to (it.getOrNull(1)?.toLong() ?: nodes) }
        val plan: List<Pair<Level, Pair<Int, Long>>> = System.getProperty("calibrate.plan")?.let { spec ->
            spec.split(';').filter { it.isNotBlank() }.flatMap { entry ->
                val (range, elos) = entry.split('@')
                val (from, to) = range.split('-').map { it.trim().toInt() }.let { it.first() to it.last() }
                (from..to).flatMap { l -> elos.split(',').map { Level.of(l) to anchor(it) } }
            }
        } ?: run {
            val elos = System.getProperty("calibrate.elos")?.split(',')?.map(::anchor) ?: listOf(1320, 1600, 2000).map { it to nodes }
            levels.flatMap { level -> elos.map { level to it } }
        }
        fun anchorName(anchor: Pair<Int, Long>) =
            if (anchor.second == nodes) "sf-${anchor.first}" else "sf-${anchor.first}-${anchor.second / 1000}k"
        val pgn = File(System.getProperty("calibrate.pgn") ?: "build/calibration-stockfish.pgn")
        pgn.absoluteFile.parentFile.mkdirs()
        say("stockfish $bin, $nodes nodes per Move, plan ${plan.joinToString(" ") { (l, a) -> "L${l.number}@${anchorName(a)}" }}")
        val depths = plan.associate { (_, a) -> anchorName(a) to LevelCalibration.PickDepths() }
        val started = System.nanoTime()
        val results = LevelCalibration.matches(
            plan.map { (level, anchor) ->
                val anchorName = anchorName(anchor)
                LevelCalibration.Pairing(
                    { LevelSide(name(level), settings(level)) },
                    { LevelCalibration.StockfishSide(bin!!, anchorName, anchor.first, anchor.second, depths.getValue(anchorName)) },
                )
            },
            threads,
            rounds,
            firstRound = System.getProperty("calibrate.firstRound")?.toInt() ?: 0,
        ) { game ->
            val text = LevelCalibration.pgn(game, "Chess Level calibration, Stockfish $nodes nodes")
            synchronized(pgn) { pgn.appendText(text) }
        }
        for (result in results) say(result.line())
        for ((anchorName, d) in depths.toSortedMap()) say(d.line(anchorName))
        say("stockfish: ${results.sumOf { it.games }} Games in ${(System.nanoTime() - started) / 1_000_000_000} s")
    }

    companion object {
        /** Karballo's search per Move in the anchor (`calibrate.karballo.nodes`): ~200 ms on a Mac. */
        const val KARBALLO_NODES = 300_000

        /**
         * Stockfish's search per Move (`calibrate.stockfish.nodes`), as in the 2026-09-29 gauntlet:
         * reaches its limiter's pick depth on every Move up to UCI_Elo 2700 and 98% at 3000, about 2 s
         * on a loaded Mac core. Its settings were calibrated at 120 s + 1 s, several million nodes.
         */
        const val STOCKFISH_NODES = 1_000_000L
    }
}
