import pirarucu.board.factory.BoardFactory
import pirarucu.cache.PawnEvaluationCache
import pirarucu.hash.TranspositionTable
import pirarucu.move.Move
import pirarucu.search.History
import pirarucu.search.MainSearch
import pirarucu.search.SearchInfo
import pirarucu.search.SearchInfoListener
import pirarucu.search.SearchOptions
import pirarucu.util.Perft

const val START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
const val KIWI = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
// Middlegame (Ruy Lopez-ish, ~move 12)
const val MID = "r1bq1rk1/2p1bppp/p1np1n2/1p2p3/4P3/1BPP1N2/PP3PPP/RNBQR1K1 w - - 0 9"

class Listener(val quiet: Boolean) : SearchInfoListener {
    var lastDepth = 0
    override fun searchInfo(depth: Int, elapsedTime: Long, searchInfo: SearchInfo) {
        lastDepth = depth
        if (!quiet) println("  d=$depth t=${elapsedTime}ms nodes=${searchInfo.searchNodes} cp=${searchInfo.bestScore} pv=$searchInfo")
    }
    override fun bestMove(searchInfo: SearchInfo) {}
}

fun perft(name: String, fen: String, depth: Int, expect: Long) {
    val b = BoardFactory.getBoard(fen)
    val t = System.nanoTime(); val n = Perft.perft(b, depth); val ms = (System.nanoTime() - t) / 1_000_000
    println("perft $name d$depth = $n (expected $expect) ${if (n == expect) "OK" else "FAIL"} ${ms}ms")
}

fun search(name: String, fen: String, moves: List<String>, timeMs: Long?, depth: Int?, ttMb: Int = 16): String {
    val board = BoardFactory.getBoard(fen)
    for (m in moves) board.doMove(Move.getMove(board, m))
    val opts = SearchOptions()
    if (timeMs != null) { opts.hasTimeLimit = true; opts.hasFixedTime = true; opts.minSearchTime = timeMs; opts.maxSearchTime = timeMs }
    else opts.hasTimeLimit = false
    if (depth != null) opts.depth = depth
    val l = Listener(quiet = true)
    val s = MainSearch(opts, l, TranspositionTable(ttMb), PawnEvaluationCache(4), History())
    opts.startControl()
    val t = System.nanoTime(); s.search(board); val ms = (System.nanoTime() - t) / 1_000_000
    val n = s.searchInfo.searchNodes
    println("search $name ${if (timeMs != null) "time=${timeMs}ms" else "depth=$depth"}: depth=${l.lastDepth} nodes=$n ${ms}ms nps=${n * 1000 / maxOf(ms, 1)} best=${Move.toString(s.searchInfo.bestMove)} cp=${s.searchInfo.bestScore} pv=${s.searchInfo}")
    return Move.toString(s.searchInfo.bestMove)
}

fun main(args: Array<String>) {
    val mode = args.getOrElse(0) { "all" }
    if (mode == "all" || mode == "perft") {
        repeat(2) { perft("startpos", START, 5, 4_865_609); perft("kiwipete", KIWI, 4, 4_085_603) }
    }
    if (mode == "all" || mode == "search") {
        search("warmup", START, emptyList(), 3000, null)
        search("startpos", START, emptyList(), 10_000, null)
        search("middlegame", MID, emptyList(), 10_000, null)
        search("startpos", START, emptyList(), null, 12)
        search("mid", MID, emptyList(), null, 12)
        // FEN + move list
        search("startpos+moves", START, listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5"), 2000, null)
        // Determinism / reuse: fresh engine objects each game, two instances back to back
        val a = search("reuseA", MID, emptyList(), null, 10); val b = search("reuseB", MID, emptyList(), null, 10)
        println("determinism same fresh instance twice: ${a == b}")
    }
    if (mode == "all" || mode == "stop") {
        // stop from another thread: infinite search, stop after 1500ms
        val board = BoardFactory.getBoard(MID)
        val opts = SearchOptions().apply { hasTimeLimit = false }
        val s = MainSearch(opts, Listener(true), TranspositionTable(16), PawnEvaluationCache(4), History())
        opts.startControl()
        val th = Thread { s.search(board) }
        val t = System.nanoTime(); th.start(); Thread.sleep(1500); opts.stop = true; th.join()
        println("stop: joined ${(System.nanoTime() - t) / 1_000_000}ms after start (stop at 1500) best=${Move.toString(s.searchInfo.bestMove)} nodes=${s.searchInfo.searchNodes}")
    }
}
