package com.yarosz.chess.puzzles

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The committed Pack (`tool/src/main/assets/pack`, built by `scripts/build-pack.py`): Light's asset
 * rules, the manifest's hashes, and every Puzzle checked by the rules core.
 */
class CommittedPackTest {

    private val assets = File("src/main/assets")
    private val dir = File(assets, Pack.DIR)
    private val pack = Pack { path -> File(assets, path).readBytes() }

    @Test
    fun `every asset has an allowed extension and stays under 5 MB`() {
        val files = assets.walkTopDown().filter { it.isFile }.toList()
        assertTrue(files.isNotEmpty())
        for (file in files) {
            assertTrue(file.extension in setOf("txt", "json"), "${file.name}: only .txt and .json in the Pack")
            assertTrue(file.length() < 5L * 1024 * 1024, "${file.name}: ${file.length()} bytes, over 5 MB")
        }
        val total = files.sumOf { it.length() }
        println("pack: ${files.size} files, $total bytes, largest ${files.maxOf { it.length() }} bytes")
        assertTrue(total < 10L * 1024 * 1024, "the Pack is $total bytes")
    }

    @Test
    fun `the manifest's hashes match the files`() {
        val manifest = pack.manifest
        val listed = manifest.bands.map { it.file }.toSet() + "manifest.json"
        assertEquals(listed, dir.list()!!.toSet(), "the manifest lists every Band file")
        for (band in manifest.bands) {
            val bytes = File(dir, band.file).readBytes()
            assertEquals(band.bytes, bytes.size.toLong(), band.file)
            assertEquals(band.sha256, sha256(bytes), band.file)
        }
        assertEquals(manifest.puzzles, manifest.bands.sumOf { it.puzzles })
        // The Pack SHA-256 covers the canonical JSON (sorted keys, no spaces) of every other field.
        val fields = Json.parseToJsonElement(File(dir, "manifest.json").readText()).jsonObject
        val rest = JsonObject(fields - "packSha256")
        assertEquals(manifest.packSha256, sha256(canonical(rest).toByteArray()))
    }

    @Test
    fun `About credits the pinned Lichess dump (D7, docs-pack)`() {
        assertEquals("2026-09-09", pack.manifest.source?.date)
        assertTrue(com.yarosz.chess.UiCopy.about(pack.manifest.source?.date, "").any { "dump of 2026-09-09" in it })
    }

    @Test
    fun `every Puzzle is legal, sorted, in its Band, and mates when it says so`() {
        val manifest = pack.manifest
        val ids = HashSet<String>()
        var mates = 0
        for ((i, band) in manifest.bands.withIndex()) {
            val puzzles = pack.puzzles(band)
            assertEquals(band.puzzles, puzzles.size, band.file)
            assertEquals(puzzles.sortedWith(compareBy({ it.rating }, { it.id })), puzzles, "${band.file} is sorted")
            assertEquals(band.minRating, puzzles.first().rating)
            assertEquals(band.maxRating, puzzles.last().rating)
            for (puzzle in puzzles) {
                assertTrue(ids.add(puzzle.id), "puzzle ${puzzle.id} is in the Pack twice")
                assertEquals(band, pack.bandFor(puzzle.rating), "puzzle ${puzzle.id}")
                assertTrue(i == 0 || puzzle.rating >= band.band, "puzzle ${puzzle.id} is below ${band.file}")
                // Moves[0] is the opponent's: after it, the solver is to move.
                assertEquals(puzzle.solver, puzzle.position.sideToMove, "puzzle ${puzzle.id}")
                if ("mate" in puzzle.themes) {
                    val end = puzzle.solution.fold(puzzle.position) { position, move -> position.play(move) }
                    assertTrue(end.isCheckmate, "puzzle ${puzzle.id} is tagged mate but doesn't mate")
                    mates++
                }
            }
        }
        println("pack: ${ids.size} puzzles valid, $mates mates checked, 0 invalid")
        assertEquals(manifest.puzzles, ids.size)
    }

    @Test
    fun `time to the first Puzzle`() {
        fun fresh() = Pack { path -> File(assets, path).readBytes() }
        fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000
        fresh().candidates(1500, emptySet()).first().position // warm the JVM
        val first = (1..5).map {
            val start = System.nanoTime()
            fresh().candidates(1500, emptySet()).first().position
            ms(start)
        }
        println("pack: manifest + Bands 1400-1600 read, first Puzzle parsed: ${first.sorted()} ms (JVM)")
        val band = pack.bandFor(1500)
        val start = System.nanoTime()
        fresh().puzzles(band).forEach { it.position }
        println("pack: every Puzzle in ${band.file} (${band.puzzles}) fully parsed: ${ms(start)} ms (JVM)")
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Python's `json.dumps(sort_keys=True, separators=(",", ":"))` for the manifest's ASCII values. */
    private fun canonical(e: JsonElement): String = when (e) {
        is JsonObject -> e.keys.sorted().joinToString(",", "{", "}") { JsonPrimitive(it).toString() + ":" + canonical(e.getValue(it)) }
        is JsonArray -> e.joinToString(",", "[", "]") { canonical(it) }
        JsonNull -> "null"
        is JsonPrimitive -> e.toString()
    }
}
