package com.yarosz.chess.puzzles

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The loader on a small fixture Pack: Bands 1400 (1440, 1460, 1478), 1500 (1500, 1521, 1541) and
 * 1800 (1860, 1880), with no 1600 or 1700 Band.
 */
class PackTest {

    private val reads = mutableListOf<String>()
    private val pack = Pack { path -> reads += path; File("src/test/resources/fixture-pack", path).readBytes() }

    private fun ratings(puzzles: List<Puzzle>) = puzzles.map { it.rating }

    @Test
    fun `the manifest lists the Bands`() {
        assertEquals(listOf(1400, 1500, 1800), pack.manifest.bands.map { it.band })
        assertEquals(8, pack.manifest.puzzles)
    }

    @Test
    fun `the Band for a Puzzle Rating`() {
        assertEquals(1400, pack.bandFor(1450).band)
        assertEquals(1500, pack.bandFor(1500).band)
        assertEquals(1500, pack.bandFor(1599).band)
        assertEquals(1400, pack.bandFor(200).band, "below the Pack: the lowest Band")
        assertEquals(1500, pack.bandFor(1650).band, "no 1600 Band: the Band below")
        assertEquals(1800, pack.bandFor(3000).band, "above the Pack: the top Band")
    }

    @Test
    fun `candidates are the Puzzles within 100 of the Player Rating, across Bands`() {
        assertEquals(listOf(1460, 1478, 1500, 1521, 1541), ratings(pack.candidates(1560, emptySet())))
        assertEquals(listOf(1440, 1460, 1478, 1500), ratings(pack.candidates(1400, emptySet())))
    }

    @Test
    fun `an empty window widens by 100 at a time`() {
        // 1700: ±100 is empty, ±200 reaches 1500..1900.
        assertEquals(listOf(1500, 1521, 1541, 1860, 1880), ratings(pack.candidates(1700, emptySet())))
        // 1000: empty until ±500 reaches 1440..1500.
        assertEquals(listOf(1440, 1460, 1478, 1500), ratings(pack.candidates(1000, emptySet())))
    }

    @Test
    fun `finished Puzzles are left out, and the window widens past them`() {
        val window = pack.candidates(1880, emptySet())
        assertEquals(listOf(1860, 1880), ratings(window))
        assertEquals(listOf(1860), ratings(pack.candidates(1880, setOf(window[1].id))))
        val finished = window.map { it.id }.toSet()
        assertEquals(listOf(1500, 1521, 1541), ratings(pack.candidates(1880, finished)), "±400 reaches the 1500s")
    }

    @Test
    fun `nothing is left when every Puzzle is finished`() {
        val all = pack.manifest.bands.flatMap { pack.puzzles(it) }.map { it.id }.toSet()
        assertEquals(8, all.size)
        assertEquals(emptyList(), pack.candidates(1500, all))
    }

    @Test
    fun `Bands load lazily and once`() {
        pack.candidates(1450, emptySet())
        assertEquals(listOf("pack/manifest.json", "pack/1400.txt", "pack/1500.txt"), reads)
        pack.candidates(1460, emptySet())
        pack.puzzles(pack.bandFor(1500))
        assertEquals(3, reads.size, "no Band file is read twice")
    }

    @Test
    fun `lookup by Lichess id`() {
        val puzzle = pack.puzzle("pO2J4", ratingHint = 1500)!!
        assertEquals(1521, puzzle.rating)
        assertEquals(listOf("pack/manifest.json", "pack/1500.txt"), reads, "the hinted Band is searched first")
        assertEquals(1860, pack.puzzle("2XYwz")?.rating, "found without a hint")
        assertEquals(1440, pack.puzzle("0Oukr")?.rating, "the first line of a Band file")
        assertNull(pack.puzzle("zzzzz"), "a Puzzle that isn't in the Pack")
        assertNull(pack.puzzle("pO2J"), "a prefix of an id is not the id")
    }

    @Test
    fun `lookup uses a Band already loaded`() {
        val loaded = pack.puzzles(pack.bandFor(1400))
        assertTrue(pack.puzzle("DNhzY", ratingHint = 1460) === loaded[1])
    }
}
