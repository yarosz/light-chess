package com.yarosz.chess.puzzles

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A Glicko-2 rating: [rating] and its deviation on the Glicko scale, and the [volatility]. The
 * Player Rating is one of these (A7); a Puzzle's is its Puzzle Rating and RD from the Pack.
 */
data class Glicko(val rating: Double, val deviation: Double, val volatility: Double = START_VOLATILITY) {

    /** Still settling: shown with a question mark (A7). */
    val provisional: Boolean get() = deviation > PROVISIONAL_DEVIATION

    /** The Player Rating as the strip and the Menu show it: "1500?" while [provisional]. */
    val text: String get() = rating.roundToInt().toString() + if (provisional) "?" else ""

    companion object {
        const val START_RATING = 1500.0
        const val START_DEVIATION = 500.0
        const val START_VOLATILITY = 0.09
        const val MIN_DEVIATION = 45.0
        const val PROVISIONAL_DEVIATION = 75.0

        /** A new Player Rating at [rating] (the seed screen's choice, D4), fully uncertain. */
        fun start(rating: Double = START_RATING) = Glicko(rating, START_DEVIATION, START_VOLATILITY)
    }
}

/**
 * The Glicko-2 update (Glickman, "Example of the Glicko-2 system", 2012), step for step. One Puzzle
 * is one rating period (A7): the Tool calls [update] with one [Game] per scored Attempt. The
 * opponent's deviation is the Puzzle's own RD from the Pack, so a Puzzle whose rating is less sure
 * moves the Player Rating less ("Pack fill"); the Puzzle Rating itself never changes.
 */
object Glicko2 {
    /** The system constant τ: how fast volatility may change. Lichess rates puzzles with 0.75. */
    const val TAU = 0.75

    private const val SCALE = 173.7178
    private const val EPSILON = 0.000001

    /** One result in a rating period: 1.0 a win for the player, 0.0 a loss. */
    data class Game(val opponent: Double, val opponentDeviation: Double, val score: Double)

    /**
     * [player] after one rating period of [games]. The new deviation is kept between
     * [Glicko.MIN_DEVIATION] and [Glicko.START_DEVIATION] ([floor] and [ceiling]); pass 0 and
     * infinity for the plain system.
     */
    fun update(
        player: Glicko,
        games: List<Game>,
        tau: Double = TAU,
        floor: Double = Glicko.MIN_DEVIATION,
        ceiling: Double = Glicko.START_DEVIATION,
    ): Glicko {
        val mu = (player.rating - 1500) / SCALE
        val phi = player.deviation / SCALE
        if (games.isEmpty()) {
            val grown = sqrt(phi * phi + player.volatility * player.volatility) * SCALE
            return player.copy(deviation = grown.coerceIn(floor, ceiling))
        }
        var vInverse = 0.0
        var sum = 0.0
        for (game in games) {
            val muJ = (game.opponent - 1500) / SCALE
            val g = g(game.opponentDeviation / SCALE)
            val e = 1 / (1 + exp(-g * (mu - muJ)))
            vInverse += g * g * e * (1 - e)
            sum += g * (game.score - e)
        }
        val v = 1 / vInverse
        val delta = v * sum
        val sigma = volatility(phi, player.volatility, v, delta, tau)
        val phiStar = sqrt(phi * phi + sigma * sigma)
        val phiNew = 1 / sqrt(1 / (phiStar * phiStar) + 1 / v)
        val muNew = mu + phiNew * phiNew * sum
        return Glicko(SCALE * muNew + 1500, (SCALE * phiNew).coerceIn(floor, ceiling), sigma)
    }

    private fun g(phi: Double) = 1 / sqrt(1 + 3 * phi * phi / (PI * PI))

    /** Step 5: the new volatility, by the Illinois algorithm. */
    private fun volatility(phi: Double, sigma: Double, v: Double, delta: Double, tau: Double): Double {
        val a = ln(sigma * sigma)
        fun f(x: Double): Double {
            val ex = exp(x)
            val d = phi * phi + v + ex
            return ex * (delta * delta - phi * phi - v - ex) / (2 * d * d) - (x - a) / (tau * tau)
        }
        var lo = a
        var hi = if (delta * delta > phi * phi + v) {
            ln(delta * delta - phi * phi - v)
        } else {
            var k = 1
            while (f(a - k * tau) < 0) k++
            a - k * tau
        }
        var fLo = f(lo)
        var fHi = f(hi)
        while (abs(hi - lo) > EPSILON) {
            val c = lo + (lo - hi) * fLo / (fHi - fLo)
            val fC = f(c)
            if (fC * fHi <= 0) {
                lo = hi
                fLo = fHi
            } else {
                fLo /= 2
            }
            hi = c
            fHi = fC
        }
        return exp(lo / 2)
    }
}
