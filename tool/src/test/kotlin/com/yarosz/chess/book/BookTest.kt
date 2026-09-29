package com.yarosz.chess.book

import com.yarosz.chess.rules.Move
import com.yarosz.chess.rules.PieceType
import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.Square
import com.yarosz.chess.rules.polyglotKey
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookTest {

    private val start = Position.START

    @Test
    fun `lookup returns every entry for the key with its weight, and nothing out of book`() {
        val afterE4 = start.after("e2e4")
        val book = bookOf(
            Triple(start, "e2e4", 300), Triple(start, "d2d4", 200), Triple(start, "g1f3", 1),
            Triple(afterE4, "c7c5", 7),
        )
        assertEquals(4, book.size)
        assertEquals(
            mapOf("e2e4" to 300, "d2d4" to 200, "g1f3" to 1),
            book.moves(start).associate { it.move.uci to it.weight },
        )
        assertEquals(listOf(BookMove(Move.parseUci("c7c5")!!, 7)), book.moves(afterE4))
        assertTrue(book.moves(start.after("d2d4")).isEmpty())
        assertTrue(Book(ByteArray(0)).moves(start).isEmpty())
    }

    @Test
    fun `binary search finds keys across the unsigned order, first and last`() {
        val keys = List(500) { Random(it).nextLong() } + listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE)
        val sorted = keys.distinct().sortedBy { it.toULong() }
        val book = Book(rawBook(sorted.map { Triple(it, 0x0123, 5) }))
        for (key in sorted) assertEquals(listOf(0x0123 to 5), book.entries(key), key.toULong().toString(16))
        assertTrue(book.entries(12345L).isEmpty())
    }

    @Test
    fun `the spec's move encoding, for plain moves`() {
        // e2e4: to e4 (file 4, row 3), from e2 (file 4, row 1).
        assertEquals(4 or (3 shl 3) or (4 shl 6) or (1 shl 9), Book.encodeMove(start, start.moveFromUci("e2e4")!!))
        assertEquals(start.moveFromUci("g1f3"), Book.decodeMove(start, 5 or (2 shl 3) or (6 shl 6)))
        assertNull(Book.decodeMove(start, 4 or (4 shl 3) or (4 shl 6) or (1 shl 9)), "e2e5 is not legal")
        assertNull(Book.decodeMove(start, 0), "a1a1 is ignored")
    }

    @Test
    fun `castling is king takes rook in the file and our king's step as a Move`() {
        val position = Position.fromFen("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1")
        val black = Position.fromFen("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R b KQkq - 0 1")
        val cases = listOf(
            Triple(position, "e1g1", "e1h1"), Triple(position, "e1c1", "e1a1"),
            Triple(black, "e8g8", "e8h8"), Triple(black, "e8c8", "e8a8"),
        )
        for ((p, ours, polyglot) in cases) {
            val move = p.moveFromUci(ours)!!
            val code = Book.encodeMove(p, move)
            val (from, to) = Square.parse(polyglot.take(2))!! to Square.parse(polyglot.drop(2))!!
            assertEquals(to.file or (to.rank shl 3) or (from.file shl 6) or (from.rank shl 9), code, ours)
            assertEquals(move, Book.decodeMove(p, code), polyglot)
        }
        // The king's two-square step is not Polyglot's castling form.
        val kingStep = 6 or (4 shl 6)
        assertNull(Book.decodeMove(position, kingStep))
        // King-takes-rook when castling is not allowed decodes to nothing.
        val noRights = Position.fromFen("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w - - 0 1")
        assertNull(Book.decodeMove(noRights, 7 or (4 shl 6)))
    }

    @Test
    fun `promotions carry the piece in bits 12 to 14`() {
        val position = Position.fromFen("1n5k/P7/8/8/8/8/8/K7 w - - 0 1")
        for ((type, code) in listOf(PieceType.KNIGHT to 1, PieceType.BISHOP to 2, PieceType.ROOK to 3, PieceType.QUEEN to 4)) {
            for (to in listOf("a8", "b8")) {
                val move = Move(Square.parse("a7")!!, Square.parse(to)!!, type)
                val encoded = Book.encodeMove(position, move)
                assertEquals(code, encoded shr 12, "$move")
                assertEquals(move, Book.decodeMove(position, encoded))
            }
        }
        assertNull(Book.decodeMove(position, Book.encodeMove(position, Move(Square.parse("a7")!!, Square.parse("a8")!!, PieceType.QUEEN)) and 0xfff))
        assertNull(Book.decodeMove(position, (Book.encodeMove(position, Move(Square.parse("a7")!!, Square.parse("a8")!!, PieceType.QUEEN)) and 0xfff) or (5 shl 12)))
    }

    @Test
    fun `encode then decode is the identity on every legal Move of random Games`() {
        val random = Random(7)
        repeat(40) {
            var position = start
            repeat(80) {
                val moves = position.legalMoves
                if (moves.isEmpty()) return@repeat
                for (m in moves) assertEquals(m, Book.decodeMove(position, Book.encodeMove(position, m)), "${position.fen} $m")
                position = position.play(moves[random.nextInt(moves.size)])
            }
        }
    }

    @Test
    fun `an entry whose move is not legal in the Position is skipped`() {
        val key = start.polyglotKey
        val e2e5 = 4 or (4 shl 3) or (4 shl 6) or (1 shl 9)
        val e2e4 = 4 or (3 shl 3) or (4 shl 6) or (1 shl 9)
        val book = Book(rawBook(listOf(Triple(key, e2e4, 9), Triple(key, e2e5, 9))))
        assertEquals(listOf("e2e4"), book.moves(start).map { it.move.uci })
    }

    @Test
    fun `entries are 16 bytes, big-endian, learn zero, and a partial entry is refused`() {
        val bytes = Book.entry(0x0102030405060708L, 0x0a0b, 0x0c0d)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 0x0a, 0x0b, 0x0c, 0x0d, 0, 0, 0, 0), bytes.map { it.toInt() and 0xff })
        assertFailsWith<IllegalArgumentException> { Book(ByteArray(17)) }
        assertEquals(1, Book.load { path -> assertEquals("book/book.bin", path); bytes }.size)
    }
}
