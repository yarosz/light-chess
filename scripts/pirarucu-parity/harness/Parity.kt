import pirarucu.board.factory.BoardFactory
import pirarucu.cache.PawnEvaluationCache
import pirarucu.hash.TranspositionTable
import pirarucu.move.Move
import pirarucu.search.History
import pirarucu.search.MainSearch
import pirarucu.search.SearchInfo
import pirarucu.search.SearchInfoListener
import pirarucu.search.SearchOptions

// The fixed-depth suite. tool/src/test/kotlin/com/yarosz/chess/engine/ParityTest.kt runs the same
// searches (same positions, depths and hash sizes, fresh engine objects each time) against the pinned
// table tool/src/test/resources/engine/parity.txt.
val POSITIONS = listOf(
    "start" to "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
    "kiwipete" to "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
    "ruylopez" to "r1bq1rk1/2p1bppp/p1np1n2/1p2p3/4P3/1BPP1N2/PP3PPP/RNBQR1K1 w - - 0 9",
    "endgame" to "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
    "position4" to "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
)
val DEPTHS = listOf(8, 9, 10, 12, 14)
const val TT_MB = 16
const val PAWN_MB = 2

object Quiet : SearchInfoListener {
    override fun searchInfo(depth: Int, elapsedTime: Long, searchInfo: SearchInfo) {}
    override fun bestMove(searchInfo: SearchInfo) {}
}

fun main() {
    for ((name, fen) in POSITIONS) for (depth in DEPTHS) {
        val options = SearchOptions()
        options.hasTimeLimit = false
        options.depth = depth
        val search = MainSearch(options, Quiet, TranspositionTable(TT_MB), PawnEvaluationCache(PAWN_MB), History())
        options.startControl()
        search.search(BoardFactory.getBoard(fen))
        val info = search.searchInfo
        println("$name $depth ${info.searchNodes} ${Move.toString(info.bestMove)} ${info.bestScore}")
    }
}
