package com.yarosz.chess.bench

import com.yarosz.chess.engine.EngineHost
import com.yarosz.chess.engine.SearchLimits
import com.yarosz.chess.engine.SearchRequest

/**
 * The engine speed benchmark (decision E11, v2 PR 2's go/no-go gate). The benchmark build type only
 * (src/benchmark): it never reaches a release build or Light's builder, which takes only src/main.
 *
 * Each run searches every position twice from a new game through [EngineHost.search], so on the engine
 * thread, as the Tool will: to a fixed depth (time to each depth) and for the Level 8 default think time
 * (nodes per second, depth reached). Run 0 warms the JIT and is marked as such. Every result is one
 * `key=value` line through [log]; scripts/bench-report.py turns them into P50/P90.
 *
 * [cpu] names the CPU core the calling thread is on (decision E5) and [tid] its kernel thread id. Both
 * are sampled, with the thread's name, on the thread that searches, after every completed depth. The
 * lines themselves are logged from the caller's thread (the main thread on the phone), so the tid that
 * logcat prints is not the searching thread's.
 */
object BenchSuite {

    val POSITIONS = listOf(
        "start" to "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        "kiwipete" to "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
        "ruylopez" to "r1bq1rk1/2p1bppp/p1np1n2/1p2p3/4P3/1BPP1N2/PP3PPP/RNBQR1K1 w - - 0 9",
        "endgame" to "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
        "position4" to "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
        "position6" to "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
    )
    const val DEPTH = 14
    const val WALL_MS = 3_000L

    suspend fun run(
        host: EngineHost,
        runs: Int,
        log: (String) -> Unit,
        cpu: () -> String,
        tid: () -> Long,
        positions: List<Pair<String, String>> = POSITIONS,
        depth: Int = DEPTH,
        wallMs: Long = WALL_MS,
    ) {
        val runtime = Runtime.getRuntime()
        log(
            "start runs=$runs positions=${positions.size} depth=$depth wallMs=$wallMs " +
                "maxMemoryMb=${runtime.maxMemory() / MB} processors=${runtime.availableProcessors()}",
        )
        for (run in 0..runs) {
            for ((name, fen) in positions) {
                for (limits in listOf(SearchLimits(depth = depth), SearchLimits(wallMs = wallMs))) {
                    host.newGame()
                    val cores = linkedSetOf<String>()
                    val threads = linkedSetOf<String>()
                    val tids = linkedSetOf<Long>()
                    val depths = mutableListOf<String>()
                    val result = host.search(SearchRequest(fen, limits = limits)) { progress ->
                        depths += "${progress.depth}:${progress.elapsedMs}"
                        cores += cpu()
                        threads += Thread.currentThread().name
                        tids += tid()
                    }
                    val kind = if (limits.depth != null) "depth limit=$depth" else "wall limit=$wallMs"
                    log(
                        "search run=$run warmup=${run == 0} pos=$name kind=$kind depth=${result.depth} " +
                            "nodes=${result.nodes} ms=${result.elapsedMs} " +
                            "nps=${result.nodes * 1000 / maxOf(result.elapsedMs, 1)} " +
                            "cpu=${cores.joinToString(",")} thread=${threads.joinToString(",")} " +
                            "tid=${tids.joinToString(",")} move=${result.bestMove} ttd=${depths.joinToString(",")}",
                    )
                }
            }
        }
        log("done usedMemoryMb=${(runtime.totalMemory() - runtime.freeMemory()) / MB}")
    }

    private const val MB = 1024 * 1024
}
