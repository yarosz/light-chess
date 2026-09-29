import karballo.Move
import karballo.Config
import karballo.search.SearchEngine
import karballo.search.SearchParameters
import karballo.util.LightPlatformUtils
import karballo.util.Utils

/**
 * Karballo behind a line protocol on stdin/stdout, so the Level calibration in the Tool's tests
 * (LevelCalibrationTest, -Dcalibrate.karballo=<this app's bin/serve>) can play against its Elo limiter.
 *
 *   new <elo>                  -> ok           (elo 0 = full strength; 500..2100 = the limiter)
 *   go <nodes>\t<fen>\t<moves> -> <uci> <score>
 *   quit
 *
 * Args: [seed]. The limiter draws from one seeded source, so a run replays.
 */
fun main(args: Array<String>) {
    Utils.instance = LightPlatformUtils(args.getOrNull(0)?.toLong() ?: 1L)
    var engine: SearchEngine? = null
    while (true) {
        val line = readLine() ?: return
        when {
            line.startsWith("new ") -> {
                val elo = line.substring(4).trim().toInt()
                val c = Config()
                c.transpositionTableSize = 16
                c.useBook = false
                if (elo > 0) {
                    c.isLimitStrength = true
                    c.elo = elo
                }
                engine = SearchEngine(c)
                engine.clear()
                println("ok")
            }
            line.startsWith("go ") -> {
                val parts = line.substring(3).split("\t")
                val e = checkNotNull(engine) { "send new first" }
                e.board.fen = parts[1]
                if (parts.size > 2 && parts[2].isNotBlank()) e.board.doMoves(parts[2])
                val p = SearchParameters()
                p.nodes = parts[0].toInt()
                e.go(p)
                println("${Move.toString(e.bestMove)} ${e.bestMoveScore}")
            }
            line == "quit" -> return
        }
        System.out.flush()
    }
}
