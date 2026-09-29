package com.yarosz.chess.puzzles

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PuzzleStoreTest {

    private val dir: File = Files.createTempDirectory("puzzle-store").toFile()
    private val main = File(dir, "puzzles.json")
    private val backup = File(dir, "puzzles.json.bak")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val sample = PuzzleData(
        rating = 1612.4, deviation = 88.0, volatility = 0.0901, seeded = true, packSha256 = "abc",
        finished = listOf("CkDUF", "2THoA"),
        current = InProgress("mREus", 1541, AttemptState.FAILED, listOf("f2g2", "g1h1"), solutionShown = false, done = false, delta = -9),
        missed = listOf(MissedEntry("mREus", 1541, AttemptState.FAILED)),
        history = listOf(HistoryEntry("mREus", 1541, AttemptState.FAILED, -9), HistoryEntry("CkDUF", 1500, AttemptState.SOLVED, 12)),
    )

    @Test
    fun `a first launch has no file`() {
        assertNull(PuzzleStore(dir).load())
    }

    @Test
    fun `save and load round trip every field`() {
        PuzzleStore(dir).save(sample)
        assertEquals(sample, PuzzleStore(dir).load())
        assertTrue(main.readText().contains("\"state\":\"Failed\""), "the file writes the glossary's state names")
    }

    @Test
    fun `the previous good file becomes the backup, and no temp file is left`() {
        val store = PuzzleStore(dir)
        store.save(sample)
        store.save(sample.copy(rating = 1700.0))
        assertEquals(sample, PuzzleData.decode(backup.readText()))
        assertEquals(1700.0, store.load()!!.rating)
        assertEquals(setOf("puzzles.json", "puzzles.json.bak"), dir.list()!!.toSet())
    }

    @Test
    fun `a corrupt main file falls back to the backup and never replaces it`() {
        val store = PuzzleStore(dir)
        store.save(sample)
        store.save(sample.copy(rating = 1700.0))
        main.writeText("{\"rating\": 17")
        assertEquals(sample, store.load(), "the backup")
        store.save(sample.copy(rating = 1800.0))
        assertEquals(sample, PuzzleData.decode(backup.readText()), "the corrupt file didn't become the backup")
        assertTrue(File(dir, "puzzles.json.corrupt").exists())
        assertEquals(1800.0, store.load()!!.rating)
    }

    @Test
    fun `unknown fields and a newer schema are read for what this build knows`() {
        main.writeText(
            """{"schemaVersion":7,"rating":1650.5,"deviation":60,"volatility":0.08,"seeded":true,"theme":"mate",
               |"finished":["a","b"],"current":{"id":"a","puzzleRating":1600,"state":"Open","clock":3},
               |"missed":[{"id":"b","puzzleRating":1500,"state":"Hinted","note":"x"}],"history":[],"streak":4}""".trimMargin(),
        )
        val data = PuzzleStore(dir).load()!!
        assertEquals(7, data.schemaVersion)
        assertEquals(1650.5, data.rating)
        assertEquals(listOf("a", "b"), data.finished)
        assertEquals(InProgress("a", 1600), data.current)
        assertEquals(listOf(MissedEntry("b", 1500, AttemptState.HINTED)), data.missed)
    }

    @Test
    fun `missing fields take their defaults`() {
        main.writeText("{}")
        assertEquals(PuzzleData(), PuzzleStore(dir).load())
    }

    @Test
    fun `a failed rename leaves the main file as it was`() {
        PuzzleStore(dir).save(sample)
        val failing = PuzzleStore(dir, rename = { from, to -> if (to.name == "puzzles.json") false else from.renameTo(to) })
        assertFailsWith<IOException> { failing.save(sample.copy(rating = 1.0)) }
        assertEquals(sample, PuzzleStore(dir).load())
    }
}
