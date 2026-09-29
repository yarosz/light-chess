// Modified for the Chess Tool (github.com/yarosz/light-chess), 2026-09-28.
// Original: Pirarucu (github.com/ratosh/pirarucu) at 987dd02, GPL-3.0; this file stays GPL-3.0.
// Change: @Volatile stop; a node budget and excluded root moves.
package pirarucu.search

import pirarucu.board.Color
import pirarucu.game.GameConstants
import pirarucu.util.PlatformSpecific
import kotlin.math.max
import kotlin.math.min

class SearchOptions {

    // Search control
    @Volatile var stop = false
    // Stop once this many nodes are searched; 0 means no node budget.
    var nodeLimit = 0L
    // Root moves the ply-0 move loop skips (top-N sampling).
    var excludedRootMoves = IntArray(0)
    var hasTimeLimit = true
    var hasFixedTime = false
    var startTime = 0L
    var maxSearchTimeLimit = 0L
    var minSearchTimeLimit = 0L

    var minSearchTime = 0L
    var maxSearchTime = 0L
    var searchTimeIncrement = 0L

    var depth = GameConstants.MAX_PLIES - 1

    var movesToGo = 0L

    var whiteTime = 0L
    var blackTime = 0L
    var whiteIncrement = 0L
    var blackIncrement = 0L

    fun setTime(color: Int) {
        if (!hasTimeLimit || hasFixedTime) {
            return
        }
        val moves = when {
            movesToGo != 0L -> max(movesToGo * 2, movesToGo + MIN_GAME_MOVES)
            else -> GAME_MOVES
        }
        val usableTime = if (color == Color.WHITE) {
            whiteTime
        } else {
            blackTime
        }
        val increment = if (color == Color.WHITE) {
            whiteIncrement
        } else {
            blackIncrement
        }
        val expectedTime = usableTime + increment * MIN_GAME_MOVES

        minSearchTime = expectedTime / moves
        maxSearchTime = min(usableTime - MOVE_OVERHEAD, minSearchTime * MAX_TIME_RATIO)

        searchTimeIncrement = max(1, (maxSearchTime - minSearchTime) / INCREMENT_RATIO)
    }

    fun startControl() {
        stop = false
        startTime = PlatformSpecific.currentTimeMillis()
        if (!hasTimeLimit) {
            return
        }
        minSearchTimeLimit = startTime + minSearchTime
        maxSearchTimeLimit = startTime + maxSearchTime
    }

    companion object {
        // NOTE: this should be equal or below MIN_GAME_MOVES
        private const val MAX_TIME_RATIO = 10L

        private const val GAME_MOVES = 100L
        private const val MIN_GAME_MOVES = 25L

        private const val INCREMENT_RATIO = 40

        private const val MOVE_OVERHEAD = 200
    }
}
