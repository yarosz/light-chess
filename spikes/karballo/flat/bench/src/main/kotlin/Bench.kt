import karballo.Board
import karballo.Config
import karballo.Move
import karballo.book.PolyglotBook
import karballo.movegen.LegalMoveGenerator
import karballo.search.SearchEngine
import karballo.search.SearchObserver
import karballo.search.SearchParameters
import karballo.search.SearchStatusInfo
import karballo.util.LightPlatformUtils
import karballo.util.Utils
import java.io.File

const val START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
const val KIWI = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
// Same middlegame as the Pirarucu bench
const val MID = "r1bq1rk1/2p1bppp/p1np1n2/1p2p3/4P3/1BPP1N2/PP3PPP/RNBQR1K1 w - - 0 9"

val gen by lazy { LegalMoveGenerator() }

fun perftRec(b: Board, d: Int): Long {
    val moves = IntArray(256)
    val n = gen.generateMoves(b, moves, 0)
    if (d == 1) return n.toLong()
    var total = 0L
    for (i in 0 until n) {
        b.doMove(moves[i], true, false)
        total += perftRec(b, d - 1)
        b.undoMove()
    }
    return total
}

fun perft(name: String, fen: String, depth: Int, expect: Long) {
    val b = Board(); b.fen = fen
    val t = System.nanoTime(); val n = perftRec(b, depth); val ms = (System.nanoTime() - t) / 1_000_000
    println("perft $name d$depth = $n (expected $expect) ${if (n == expect) "OK" else "FAIL"} ${ms}ms")
}

class Obs : SearchObserver {
    var lastDepth = 0
    var last: SearchStatusInfo? = null
    override fun info(info: SearchStatusInfo) { lastDepth = info.depth; last = info }
    override fun bestMove(bestMove: Int, ponder: Int) {}
}

fun engine(ttMb: Int = 16, book: Boolean = false): SearchEngine {
    val c = Config()
    c.transpositionTableSize = ttMb
    c.useBook = book
    return SearchEngine(c)
}

fun search(name: String, fen: String, moves: String, timeMs: Int?, depth: Int?, e: SearchEngine = engine()): String {
    val o = Obs(); e.setObserver(o)
    e.board.fen = fen
    e.board.doMoves(moves)
    val p = SearchParameters()
    if (timeMs != null) p.moveTime = timeMs
    if (depth != null) p.depth = depth
    val t = System.nanoTime(); e.go(p); val ms = (System.nanoTime() - t) / 1_000_000
    val n = e.nodeCount
    println("search $name ${if (timeMs != null) "time=${timeMs}ms" else "depth=$depth"}: depth=${o.lastDepth} nodes=$n ${ms}ms nps=${n * 1000 / maxOf(ms, 1)} best=${Move.toString(e.bestMove)} cp=${e.bestMoveScore} pv=${o.last?.pv}")
    return Move.toString(e.bestMove)
}

fun main(args: Array<String>) {
    Utils.instance = LightPlatformUtils()
    val mode = args.getOrElse(0) { "all" }
    if (mode == "all" || mode == "perft") {
        repeat(2) { perft("startpos", START, 5, 4_865_609); perft("kiwipete", KIWI, 4, 4_085_603) }
    }
    if (mode == "all" || mode == "search") {
        search("warmup", START, "", 3000, null)
        search("startpos", START, "", 10_000, null)
        search("middlegame", MID, "", 10_000, null)
        search("startpos", START, "", null, 12)
        search("mid", MID, "", null, 12)
        search("startpos+moves", START, "e2e4 e7e5 g1f3 b8c6 f1b5", 2000, null)
        val a = search("reuseA", MID, "", null, 10); val b = search("reuseB", MID, "", null, 10)
        println("determinism same fresh instance twice: ${a == b}")
    }
    if (mode == "nodes") {
        val e = engine(); e.board.fen = MID
        val p = SearchParameters(); p.nodes = 200_000
        val t = System.nanoTime(); e.go(p); val ms = (System.nanoTime() - t) / 1_000_000
        println("nodes limit 200000: nodeCount=${e.nodeCount} ${ms}ms best=${Move.toString(e.bestMove)}")
    }
    if (mode == "all" || mode == "stop") {
        val e = engine(); e.board.fen = MID
        val p = SearchParameters(); p.isInfinite = true
        val th = Thread { e.go(p) }
        val t = System.nanoTime(); th.start(); Thread.sleep(1500); e.stop(); th.join()
        println("stop: joined ${(System.nanoTime() - t) / 1_000_000}ms after start (stop at 1500) best=${Move.toString(e.bestMove)} nodes=${e.nodeCount}")
    }
    if (mode == "book") {
        val bytes = File(args[1]).readBytes()
        val book = PolyglotBook(bytes)
        println("book bytes=${bytes.size} entries=${bytes.size / 16}")
        val b = Board(); b.fen = START
        println("startpos book moves: " + book.movesWithWeights(b).joinToString { Move.toString(it.first) + ":" + it.second })
        b.doMoves("e2e4 e7e5 g1f3 b8c6 f1b5 a7a6 b5a4 g8f6 e1g1")
        println("after Ruy 5.O-O: " + book.movesWithWeights(b).joinToString { Move.toString(it.first) + ":" + it.second })
        // Play book-only line from startpos
        val c = Config(); c.useBook = true; c.book = book; c.transpositionTableSize = 16
        val e = SearchEngine(c)
        val line = StringBuilder()
        for (ply in 0 until 30) {
            e.board.fen = START; e.board.doMoves(line.toString().trim())
            val p = SearchParameters(); p.moveTime = 50
            val t = System.nanoTime(); e.go(p); val us = (System.nanoTime() - t) / 1000
            val inBook = e.nodeCount == 0L
            line.append(" ").append(Move.toString(e.bestMove))
            if (!inBook) { println("left book at ply $ply"); break }
            if (ply < 3) println("book move ply $ply: ${Move.toString(e.bestMove)} in ${us}us")
        }
        println("book line: ${line.toString().trim()}")
    }
}
