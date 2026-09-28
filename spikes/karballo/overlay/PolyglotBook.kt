package karballo.book

import karballo.Board
import karballo.Move
import karballo.util.Utils

/**
 * Replaces FileBook (which re-read a classpath resource via javaClass.getResourceAsStream on every
 * move and scanned it linearly). Takes the Polyglot .bin bytes (e.g. from lightContext.readAsset)
 * and binary-searches the sorted 16-byte entries.
 */
class PolyglotBook(private val data: ByteArray) : Book {
    private val entries = data.size / 16

    private fun long(off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (data[off + i].toLong() and 0xff)
        return v
    }

    private fun short(off: Int): Int = ((data[off].toInt() and 0xff) shl 8) or (data[off + 1].toInt() and 0xff)

    private fun int2MoveString(move: Int): String {
        val sb = StringBuilder()
        sb.append('a' + (move shr 6 and 0x7))
        sb.append((move shr 9 and 0x7) + 1)
        sb.append('a' + (move and 0x7))
        sb.append((move shr 3 and 0x7) + 1)
        if (move shr 12 and 0x7 != 0) sb.append("nbrq"[(move shr 12 and 0x7) - 1])
        return sb.toString()
    }

    /** Lower bound of key, comparing unsigned (Polyglot files are sorted as unsigned 64-bit). */
    private fun firstIndex(key: Long): Int {
        var lo = 0
        var hi = entries
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (long(mid * 16).toULong() < key.toULong()) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun movesWithWeights(board: Board): List<Pair<Int, Int>> {
        val key = board.getKey()
        val out = ArrayList<Pair<Int, Int>>()
        var i = firstIndex(key)
        while (i < entries && long(i * 16) == key) {
            val off = i * 16
            val move = Move.getFromString(board, int2MoveString(short(off + 8)), true)
            if (board.getLegalMove(move) != Move.NONE) out.add(move to short(off + 10))
            i++
        }
        return out
    }

    override fun getMove(board: Board): Int {
        val moves = movesWithWeights(board)
        val total = moves.sumOf { it.second.toLong() }
        var r = (Utils.instance.randomFloat() * total).toLong()
        for ((m, w) in moves) {
            r -= w
            if (r <= 0) return m
        }
        return Move.NONE
    }
}
