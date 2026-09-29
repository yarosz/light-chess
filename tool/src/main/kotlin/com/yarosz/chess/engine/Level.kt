package com.yarosz.chess.engine

/**
 * How strongly the computer plays (CONTEXT.md), 1 to 8. A pure mapping to search settings, the same on
 * every phone (decision B2): a node budget per search, plus a pick among the near-best root Moves
 * (E3). Level 8 is the engine at full strength, capped by [ThinkTime] (E2).
 *
 * The values come from the Mac calibration in docs/levels.md; retune them there, not here alone.
 */
enum class Level(val number: Int, private val nodes: Long, private val depth: Int?, val topN: Int, val marginCp: Int) {
    ONE(1, nodes = 60, depth = 2, topN = 4, marginCp = 100),
    TWO(2, nodes = 400, depth = 4, topN = 4, marginCp = 150),
    THREE(3, nodes = 1_000, depth = null, topN = 3, marginCp = 80),
    FOUR(4, nodes = 2_500, depth = null, topN = 3, marginCp = 55),
    FIVE(5, nodes = 6_000, depth = null, topN = 2, marginCp = 35),
    SIX(6, nodes = 25_000, depth = null, topN = 2, marginCp = 20),
    SEVEN(7, nodes = 120_000, depth = null, topN = 2, marginCp = 15),
    EIGHT(8, nodes = 0, depth = null, topN = 1, marginCp = 0),
    ;

    /** The search settings of this Level. [thinkTime] only matters at Level 8. */
    fun settings(thinkTime: ThinkTime = ThinkTime.DEFAULT): LevelSettings =
        if (this == EIGHT) {
            LevelSettings(nodes = thinkTime.nodes, depth = null, topN = 1, marginCp = 0, wallMs = thinkTime.ms)
        } else {
            LevelSettings(nodes = nodes, depth = depth, topN = topN, marginCp = marginCp, wallMs = null)
        }

    companion object {
        fun of(number: Int): Level = entries.firstOrNull { it.number == number }
            ?: throw IllegalArgumentException("no Level $number")
    }
}

/**
 * How long the computer may think at Level 8 (decision E2): a wall-time cap on a node budget. The
 * budget is [ms] times [NODES_PER_MS], about twice what the LP3 searches in that time (558K nodes per
 * second), so on the phone the clock ends the search; a faster machine stops at the budget instead.
 */
enum class ThinkTime(val ms: Long) {
    THREE_SECONDS(3_000),
    TEN_SECONDS(10_000),
    THIRTY_SECONDS(30_000),
    ;

    val nodes: Long get() = ms * NODES_PER_MS

    companion object {
        val DEFAULT = THREE_SECONDS
        const val NODES_PER_MS = 1_000L
    }
}

/**
 * What one Level searches: every search gets [nodes] (and [depth], when set); up to [topN] searches
 * find the near-best root Moves, each excluding the Moves found before it, and a Move is picked at
 * random among those within [marginCp] of the best. [wallMs] caps the whole choice.
 */
data class LevelSettings(
    val nodes: Long,
    val depth: Int?,
    val topN: Int,
    val marginCp: Int,
    val wallMs: Long?,
) {
    init {
        require(nodes > 0 && topN >= 1 && marginCp >= 0) { "bad level settings $this" }
    }

    /** The most nodes one choice can search (each search may overshoot by a few quiescence nodes). */
    val maxNodes: Long get() = nodes * topN
}
