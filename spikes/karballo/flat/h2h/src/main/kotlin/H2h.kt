import karballo.Board
import karballo.Config
import karballo.Move
import karballo.search.SearchEngine
import karballo.search.SearchObserver
import karballo.search.SearchParameters
import karballo.search.SearchStatusInfo
import karballo.util.LightPlatformUtils
import karballo.util.Utils
import pirarucu.board.factory.BoardFactory
import pirarucu.cache.PawnEvaluationCache
import pirarucu.hash.TranspositionTable
import pirarucu.search.History
import pirarucu.search.MainSearch
import pirarucu.search.SearchInfo
import pirarucu.search.SearchInfoListener
import pirarucu.search.SearchOptions
import java.io.File

const val START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

// 10 short, balanced openings; each is played twice with colours swapped.
val OPENINGS = listOf(
    "e2e4 e7e5 g1f3 b8c6 f1b5 a7a6",          // Ruy Lopez
    "e2e4 c7c5 g1f3 d7d6 d2d4 c5d4",          // Sicilian
    "d2d4 d7d5 c2c4 e7e6 b1c3 g8f6",          // QGD
    "d2d4 g8f6 c2c4 g7g6 b1c3 f8g7",          // King's Indian
    "e2e4 e7e6 d2d4 d7d5 b1c3 f8b4",          // French Winawer
    "e2e4 c7c6 d2d4 d7d5 e4e5 c8f5",          // Caro-Kann advance
    "c2c4 e7e5 b1c3 g8f6 g2g3 d7d5",          // English
    "g1f3 d7d5 g2g3 g8f6 f1g2 e7e6",          // Reti
    "e2e4 e7e5 g1f3 b8c6 f1c4 f8c5",          // Italian
    "d2d4 g8f6 c2c4 e7e6 g1f3 b7b6",          // Queen's Indian
)

class KarballoPlayer(ttMb: Int = 16, limitElo: Int? = null) {
    val engine: SearchEngine
    var lastDepth = 0
    init {
        val c = Config()
        c.transpositionTableSize = ttMb
        c.useBook = false
        if (limitElo != null) { c.isLimitStrength = true; c.elo = limitElo }
        engine = SearchEngine(c)
        engine.setObserver(object : SearchObserver {
            override fun info(info: SearchStatusInfo) { lastDepth = info.depth }
            override fun bestMove(bestMove: Int, ponder: Int) {}
        })
    }
    fun newGame() = engine.clear()
    fun move(moves: String, ms: Int? = null, depth: Int? = null, fen: String = START): String {
        engine.board.fen = fen
        engine.board.doMoves(moves)
        val p = SearchParameters()
        if (ms != null) p.moveTime = ms
        if (depth != null) p.depth = depth
        engine.go(p)
        return Move.toString(engine.bestMove)
    }
}

class PirarucuPlayer {
    private var tt = TranspositionTable(16)
    private var pawn = PawnEvaluationCache(4)
    private var history = History()
    fun newGame() { tt = TranspositionTable(16); pawn = PawnEvaluationCache(4); history = History() }
    fun move(moves: String, ms: Long): String {
        val board = BoardFactory.getBoard(START)
        for (m in moves.split(" ").filter { it.isNotEmpty() }) board.doMove(pirarucu.move.Move.getMove(board, m))
        val opts = SearchOptions()
        opts.hasTimeLimit = true; opts.hasFixedTime = true; opts.minSearchTime = ms; opts.maxSearchTime = ms
        val l = object : SearchInfoListener {
            override fun searchInfo(depth: Int, elapsedTime: Long, searchInfo: SearchInfo) {}
            override fun bestMove(searchInfo: SearchInfo) {}
        }
        val s = MainSearch(opts, l, tt, pawn, history)
        opts.startControl()
        s.search(board)
        return pirarucu.move.Move.toString(s.searchInfo.bestMove)
    }
}

/** Returns 1.0 white win, 0.0 black win, 0.5 draw; and the move list. */
fun playGame(opening: String, karballoWhite: Boolean, ms: Int, fens: MutableList<String>): Pair<Double, String> {
    val k = KarballoPlayer(); val p = PirarucuPlayer()
    k.newGame(); p.newGame()
    val ref = Board(); ref.fen = START
    val moves = StringBuilder(opening)
    ref.doMoves(opening)
    var ply = opening.split(" ").size
    while (true) {
        val end = ref.isEndGame
        if (end == 1) return 1.0 to moves.toString()
        if (end == -1) return 0.0 to moves.toString()
        if (end == 99) return 0.5 to moves.toString()
        if (ply >= 300) return 0.5 to moves.toString()
        if (ply % 8 == 0 && ply in 12..80) fens.add(ref.fen)
        val karballoToMove = ref.turn == karballoWhite
        val mv = if (karballoToMove) k.move(moves.toString(), ms = ms) else p.move(moves.toString(), ms.toLong())
        val m = Move.getFromString(ref, mv, true)
        if (m == Move.NONE || ref.getLegalMove(m) == Move.NONE) {
            println("  illegal move $mv by ${if (karballoToMove) "karballo" else "pirarucu"}")
            return (if (ref.turn) 0.0 else 1.0) to moves.toString()
        }
        ref.doMove(m)
        moves.append(" ").append(mv)
        ply++
    }
}

fun match(ms: Int) {
    var kScore = 0.0; var games = 0; var w = 0; var d = 0; var l = 0
    val fens = mutableListOf<String>()
    for (op in OPENINGS) for (kWhite in listOf(true, false)) {
        val t = System.nanoTime()
        val (res, mv) = playGame(op, kWhite, ms, fens)
        val kRes = if (kWhite) res else 1 - res
        kScore += kRes; games++
        when (kRes) { 1.0 -> w++; 0.5 -> d++; else -> l++ }
        println("game $games karballo=${if (kWhite) "W" else "B"} result(karballo)=$kRes plies=${mv.split(" ").size} ${(System.nanoTime() - t) / 1_000_000_000}s")
    }
    println("karballo vs pirarucu @${ms}ms/move: +$w =$d -$l score $kScore/$games")
    File("fens.txt").writeText(fens.joinToString("\n"))
}

/** Probe what the Elo limiter does: centipawn loss vs a full-strength judge. */
fun eloProbe(ms: Int) {
    val fens = File("fens.txt").readLines().filter { it.isNotBlank() }.take(40)
    val judge = KarballoPlayer(ttMb = 32)
    fun judgeScore(fen: String, moves: String): Int {
        judge.newGame(); judge.move(moves, depth = 10, fen = fen); return judge.engine.bestMoveScore
    }
    val best = fens.map { judgeScore(it, "") }
    for (elo in listOf(500, 1000, 1500, 1800, 2100)) {
        val lim = KarballoPlayer(limitElo = elo)
        var loss = 0L; var blunders = 0; var same = 0; var depthSum = 0; var caps = 0
        for ((i, fen) in fens.withIndex()) {
            lim.newGame()
            val mv = lim.move("", ms = ms, fen = fen)
            depthSum += lim.lastDepth
            val b = Board(); b.fen = fen
            val m = Move.getFromString(b, mv, true)
            if (Move.isCapture(m)) caps++
            val after = -judgeScore(fen, mv)
            val judgeBest = judge.let { it.newGame(); it.move("", depth = 10, fen = fen) }
            if (judgeBest == mv) same++
            val cp = maxOf(0, best[i] - after).coerceAtMost(1000)
            loss += cp; if (cp >= 200) blunders++
        }
        println("elo=$elo rand/1000=${lim.engine.config.rand} book%=${lim.engine.config.bookKnowledge} avgDepth=${depthSum / fens.size} sameAsJudge=$same/${fens.size} captures=$caps avgCpLoss=${loss / fens.size} blunders>=200cp=$blunders")
    }
}

fun main(args: Array<String>) {
    Utils.instance = LightPlatformUtils()
    when (args[0]) {
        "match" -> match(args.getOrElse(1) { "250" }.toInt())
        "elo" -> eloProbe(args.getOrElse(1) { "300" }.toInt())
    }
}
