package com.yarosz.chess.book

import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.polyglotKey
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The committed Book (`tool/src/main/assets/book/`, built by `scripts/build-book.sh`, docs/book.md):
 * its size (book ruling 2), its order, its manifest, and every entry checked by the rules core.
 */
class CommittedBookTest {

    private val dir = File("src/main/assets/book")
    private val bytes = File(dir, "book.bin").readBytes()
    private val book = Book(bytes)
    private val manifest = Json.parseToJsonElement(File(dir, "book-manifest.json").readText()).jsonObject

    @Test
    fun `at most 40,000 entries, at most 640 KB`() {
        println("book: ${book.size} entries, ${bytes.size} bytes")
        assertTrue(book.size in 1..40_000, "${book.size} entries")
        assertTrue(bytes.size <= 640_000, "${bytes.size} bytes")
    }

    @Test
    fun `entries are sorted by unsigned key, with positive weights and learn 0`() {
        for (i in 1 until book.size) {
            assertTrue(book.keyAt(i - 1).toULong() <= book.keyAt(i).toULong(), "entry $i out of order")
        }
        for (i in 0 until book.size) {
            val off = i * Book.ENTRY_BYTES
            val weight = ((bytes[off + 10].toInt() and 0xff) shl 8) or (bytes[off + 11].toInt() and 0xff)
            assertTrue(weight > 0, "entry $i has weight 0")
            assertTrue((12..15).all { bytes[off + it].toInt() == 0 }, "entry $i has a learn value")
        }
    }

    @Test
    fun `every entry is a legal Move in a Position the Book reaches from the start`() {
        // Walk the Book's tree breadth first from the start Position, to the 20-ply limit.
        val reached = HashMap<Long, Position>()
        reached[Position.START.polyglotKey] = Position.START
        var frontier = listOf(Position.START)
        for (depth in 0 until 20) {
            val next = ArrayList<Position>()
            for (position in frontier) {
                val offered = book.moves(position)
                assertEquals(book.entries(position.polyglotKey).size, offered.size, "an entry is not legal in ${position.fen}")
                for (bookMove in offered) {
                    val child = position.play(bookMove.move)
                    if (depth + 1 < 20 && reached.putIfAbsent(child.polyglotKey, child) == null) next += child
                }
            }
            frontier = next
        }
        val keys = (0 until book.size).map { book.keyAt(it) }.toSet()
        val unreached = keys - reached.keys
        assertTrue(unreached.isEmpty(), "${unreached.size} of ${keys.size} book Positions are not reached from the start")
        println("book: ${keys.size} Positions, all reached from the start")
    }

    @Test
    fun `the start Position offers the main first Moves`() {
        val first = book.moves(Position.START).map { it.move.uci }
        println("book: from the start ${book.moves(Position.START).map { "${it.move.uci} ${it.weight}" }}")
        assertTrue("e2e4" in first && "d2d4" in first, "$first")
    }

    @Test
    fun `the manifest's hash and counts match the file`() {
        assertEquals(sha256(bytes), manifest["bookSha256"]!!.jsonPrimitive.content)
        assertEquals(book.size.toLong(), manifest["entries"]!!.jsonPrimitive.long)
        assertEquals(bytes.size.toLong(), manifest["bookBytes"]!!.jsonPrimitive.long)
        val source = manifest["source"]!!.jsonObject
        assertEquals("https://database.lichess.org/standard/lichess_db_standard_rated_2018-01.pgn.zst", source["url"]!!.jsonPrimitive.content)
        assertEquals("8ac6ff9d722a4bba1c1d72c700523408dff2e09cc52cbfe4e454289ca60e8d6b", source["sha256"]!!.jsonPrimitive.content)
    }

    private fun sha256(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
