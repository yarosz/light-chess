package com.yarosz.chess.book

import com.yarosz.chess.rules.Move
import com.yarosz.chess.rules.PieceType
import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.Square
import com.yarosz.chess.rules.polyglotKey

/** One Move the Book offers in a Position, with its weight (1 to 65,535; higher is played more). */
data class BookMove(val move: Move, val weight: Int)

/**
 * The opening Book: a Polyglot book (https://hgm.nubati.net/book_format.html) of 16-byte entries,
 * big-endian, sorted by key: key (u64, [polyglotKey]), move (u16), weight (u16), learn (u32, 0).
 * The Tool ships one in `assets/book/book.bin` (docs/book.md); the build writes it with [entry]
 * and reads it back through this class, so both sides share one reader.
 *
 * Every Move the Book returns is checked against the rules core: an entry whose move is not legal
 * in the Position (a key collision, a bad file) is skipped.
 */
class Book(private val bytes: ByteArray) {
    init {
        require(bytes.size % ENTRY_BYTES == 0) { "a Polyglot book is a whole number of 16-byte entries, not ${bytes.size} bytes" }
    }

    val size: Int get() = bytes.size / ENTRY_BYTES

    /** The legal Moves the Book offers in [position], in file order. Empty when it is out of book. */
    fun moves(position: Position): List<BookMove> =
        entries(position.polyglotKey).mapNotNull { (code, weight) ->
            if (weight == 0) null else decodeMove(position, code)?.let { BookMove(it, weight) }
        }

    /** The raw (move code, weight) entries stored for [key], in file order. */
    fun entries(key: Long): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>(4)
        var i = firstIndex(key)
        while (i < size && keyAt(i) == key) {
            out += short(i * ENTRY_BYTES + 8) to short(i * ENTRY_BYTES + 10)
            i++
        }
        return out
    }

    fun keyAt(index: Int): Long {
        var v = 0L
        val off = index * ENTRY_BYTES
        for (b in 0 until 8) v = (v shl 8) or (bytes[off + b].toLong() and 0xff)
        return v
    }

    /** The first entry whose key is >= [key], comparing unsigned as Polyglot sorts. */
    private fun firstIndex(key: Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keyAt(mid).toULong() < key.toULong()) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun short(off: Int): Int = ((bytes[off].toInt() and 0xff) shl 8) or (bytes[off + 1].toInt() and 0xff)

    companion object {
        const val ENTRY_BYTES = 16
        const val ASSET = "book/book.bin"

        /** Reads the shipped Book through the screen's `readAsset`. */
        fun load(readAsset: (String) -> ByteArray): Book = Book(readAsset(ASSET))

        // Polyglot's promotion field: none 0, knight 1, bishop 2, rook 3, queen 4.
        private val PROMOTION_CODES = listOf(null, PieceType.KNIGHT, PieceType.BISHOP, PieceType.ROOK, PieceType.QUEEN)

        /**
         * The Polyglot move code for a legal [move] in [position]: to file (bits 0-2), to row (3-5),
         * from file (6-8), from row (9-11), promotion (12-14). Castling is written as the king taking
         * its own rook (e1h1, e1a1, e8h8, e8a8), as the spec requires.
         */
        fun encodeMove(position: Position, move: Move): Int {
            require(move in position.legalMoves) { "illegal move ${move.uci} in ${position.fen}" }
            val king = position.pieceAt(move.from)?.type == PieceType.KING
            val to = when {
                king && move.to.index - move.from.index == 2 -> Square(move.from.index + 3)
                king && move.from.index - move.to.index == 2 -> Square(move.from.index - 4)
                else -> move.to
            }
            val promotion = PROMOTION_CODES.indexOf(move.promotion)
            return to.file or (to.rank shl 3) or (move.from.file shl 6) or (move.from.rank shl 9) or (promotion shl 12)
        }

        /**
         * The legal Move a Polyglot move [code] names in [position], or null. The king-takes-rook
         * castling form becomes our king's step (e1h1 -> e1g1) through [Position.moveFromUci].
         */
        fun decodeMove(position: Position, code: Int): Move? {
            val to = Square.of(code and 7, (code shr 3) and 7)
            val from = Square.of((code shr 6) and 7, (code shr 9) and 7)
            val promotionCode = (code shr 12) and 7
            if (from == to || promotionCode >= PROMOTION_CODES.size) return null
            // Polyglot writes castling only as king-takes-rook; a king's two-square step is not a book move.
            val king = position.pieceAt(from)?.type == PieceType.KING
            if (king && (to.index - from.index == 2 || from.index - to.index == 2)) return null
            val uci = from.name + to.name + (PROMOTION_CODES[promotionCode]?.letter ?: "")
            return position.moveFromUci(uci)
        }

        /** One 16-byte book entry: key, move code, weight, learn = 0, big-endian. */
        fun entry(key: Long, code: Int, weight: Int): ByteArray {
            require(code in 0..0xffff && weight in 1..0xffff)
            val out = ByteArray(ENTRY_BYTES)
            for (b in 0 until 8) out[b] = (key ushr (56 - 8 * b)).toByte()
            out[8] = (code shr 8).toByte()
            out[9] = code.toByte()
            out[10] = (weight shr 8).toByte()
            out[11] = weight.toByte()
            return out
        }
    }
}
