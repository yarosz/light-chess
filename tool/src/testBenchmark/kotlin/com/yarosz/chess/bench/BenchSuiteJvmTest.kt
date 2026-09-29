package com.yarosz.chess.bench

import com.yarosz.chess.engine.EngineHost
import com.yarosz.chess.engine.PirarucuEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The phone benchmark's suite on this JVM (`mise run bench-jvm`, `:tool:testBenchmarkUnitTest`).
 */
class BenchSuiteJvmTest {

    /** The whole suite, for comparison with the LP3. Skipped unless -Dbench.runs=<n>; writes build/bench-jvm.txt. */
    @Test
    fun `run the benchmark suite`() {
        val runs = System.getProperty("bench.runs")?.toIntOrNull()
        assumeTrue("set -Dbench.runs=<n>", runs != null && runs > 0)
        val lines = mutableListOf<String>()
        runBlocking {
            BenchSuite.run(EngineHost.shared, runs!!, { lines += it; println("ChessBench: $it") }, { "na" }, { Thread.currentThread().id })
        }
        File("build/bench-jvm.txt").writeText(lines.joinToString("\n", postfix = "\n"))
        assertTrue(lines.last().startsWith("done"))
    }

    /** Every search runs on the engine thread, not the caller's, and its line names that thread. */
    @Test
    fun `searches run on the engine thread and are logged with its id`() {
        val host = EngineHost { PirarucuEngine(ttMb = 1, pawnCacheMb = 1) }
        val lines = mutableListOf<String>()
        val caller = Thread.currentThread().id
        runBlocking {
            BenchSuite.run(host, 1, { lines += it }, { "na" }, { Thread.currentThread().id }, BenchSuite.POSITIONS.take(2), depth = 4, wallMs = 50)
        }
        val searches = lines.filter { it.startsWith("search ") }
        assertEquals(2 * 2 * 2, searches.size)
        val fields = searches.map { line -> Regex("""(\w+)=(\S+)""").findAll(line).associate { it.groupValues[1] to it.groupValues[2] } }
        fields.forEach { assertEquals(EngineHost.THREAD_NAME, it["thread"]) }
        val tids = fields.map { it.getValue("tid") }.toSet()
        assertEquals(1, tids.size)
        assertNotEquals(caller.toString(), tids.single())
    }
}
