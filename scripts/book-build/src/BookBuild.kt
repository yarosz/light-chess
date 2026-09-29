// Builds the Tool's opening Book from Lichess games (PGN on stdin), per the book rulings in
// docs/design/decision-log.md ("Opening book") and docs/book.md. Run by scripts/build-book.sh.
//
//   book-build --out DIR --source-url URL --dump-sha256 HEX [--save-filtered FILE] < games.pgn
//
// Writes DIR/book.bin (sorted big-endian 16-byte Polyglot entries) and DIR/book-manifest.json.
// Deterministic: the same games give the same bytes, whatever their order.
package com.yarosz.chess.bookbuild

import com.yarosz.chess.book.Book
import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.Side
import com.yarosz.chess.rules.moveFromSan
import com.yarosz.chess.rules.polyglotKey
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.Writer
import java.security.MessageDigest
import kotlin.system.exitProcess

// The filter and thresholds (book ruling 1 and 2).
const val MIN_ELO = 2200
const val MIN_ESTIMATED_SECONDS = 180 // base + 40 x increment
const val INCREMENT_MOVES = 40
const val TERMINATION = "Normal"
const val MAX_PLIES = 20
const val MIN_COUNT = 10
const val MIN_SHARE_PERCENT = 5
const val DROP_MIN_GAMES = 30
const val DROP_BELOW_SCORE_PERCENT = 40
const val MAX_ENTRIES = 40_000

/** Games and half-points (win 2, draw 1) for one (Position, Move) pair. */
class MoveStats {
    var games = 0
    var halfPoints = 0L
}

class Counts {
    /** Polyglot key -> Polyglot move code -> stats. */
    val byKey = HashMap<Long, HashMap<Int, MoveStats>>()
    var gamesRead = 0L
    var gamesKept = 0L
    var gamesUnreadable = 0L

    fun add(key: Long, code: Int, halfPoints: Int) {
        val stats = byKey.getOrPut(key) { HashMap(4) }.getOrPut(code) { MoveStats() }
        stats.games++
        stats.halfPoints += halfPoints
    }
}

/** One Game's headers, only the ones the filter reads. */
class Headers {
    var whiteElo: String? = null
    var blackElo: String? = null
    var timeControl: String? = null
    var termination: String? = null
    var result: String? = null
    var setUp = false

    fun clear() {
        whiteElo = null; blackElo = null; timeControl = null; termination = null; result = null; setUp = false
    }
}

fun passes(h: Headers): Boolean {
    val white = h.whiteElo?.toIntOrNull() ?: return false
    val black = h.blackElo?.toIntOrNull() ?: return false
    if (white < MIN_ELO || black < MIN_ELO) return false
    val tc = h.timeControl ?: return false
    val plus = tc.indexOf('+')
    if (plus < 0) return false
    val base = tc.substring(0, plus).toIntOrNull() ?: return false
    val increment = tc.substring(plus + 1).toIntOrNull() ?: return false
    if (base + INCREMENT_MOVES * increment < MIN_ESTIMATED_SECONDS) return false
    if (h.termination != TERMINATION) return false
    if (h.result !in setOf("1-0", "0-1", "1/2-1/2")) return false
    return !h.setUp
}

private fun headerValue(line: String): String? {
    val open = line.indexOf('"')
    val close = line.lastIndexOf('"')
    return if (open in 0 until close) line.substring(open + 1, close) else null
}

/** The first [MAX_PLIES] SAN tokens of a movetext, without comments, move numbers, NAGs or the result. */
fun sanTokens(movetext: String): List<String> {
    val out = ArrayList<String>(MAX_PLIES)
    var i = 0
    val n = movetext.length
    while (i < n && out.size < MAX_PLIES) {
        val c = movetext[i]
        when {
            c == '{' -> { val end = movetext.indexOf('}', i); i = if (end < 0) n else end + 1 }
            c == ' ' || c == '\t' -> i++
            else -> {
                var end = i
                while (end < n && movetext[end] != ' ' && movetext[end] != '{') end++
                var token = movetext.substring(i, end)
                i = end
                token = token.trimStart { it.isDigit() }.trimStart('.')
                if (token.isEmpty() || token.startsWith('$') || token in setOf("1-0", "0-1", "1/2-1/2", "*")) continue
                // "1-0" after trimming digits reads "-0"; "1/2-1/2" reads "/2-1/2".
                if (token.startsWith('-') || token.startsWith('/')) continue
                out += token
            }
        }
    }
    return out
}

/** Counts one kept Game's first plies. False when its movetext doesn't parse with our core. */
fun countGame(movetext: String, result: String, counts: Counts): Boolean {
    val tokens = sanTokens(movetext)
    val pairs = ArrayList<Triple<Long, Int, Int>>(tokens.size)
    var position = Position.START
    for (san in tokens) {
        val move = position.moveFromSan(san) ?: return false
        val white = position.sideToMove == Side.WHITE
        val halfPoints = when (result) {
            "1-0" -> if (white) 2 else 0
            "0-1" -> if (white) 0 else 2
            else -> 1
        }
        pairs += Triple(position.polyglotKey, Book.encodeMove(position, move), halfPoints)
        position = position.play(move)
    }
    for ((key, code, hp) in pairs) counts.add(key, code, hp)
    return true
}

fun readGames(reader: BufferedReader, counts: Counts, filtered: Writer?) {
    val h = Headers()
    val headerLines = ArrayList<String>(20)
    var inHeaders = false
    while (true) {
        val line = reader.readLine() ?: break
        if (line.startsWith("[")) {
            if (!inHeaders) { h.clear(); headerLines.clear(); inHeaders = true }
            headerLines += line
            when {
                line.startsWith("[WhiteElo ") -> h.whiteElo = headerValue(line)
                line.startsWith("[BlackElo ") -> h.blackElo = headerValue(line)
                line.startsWith("[TimeControl ") -> h.timeControl = headerValue(line)
                line.startsWith("[Termination ") -> h.termination = headerValue(line)
                line.startsWith("[Result ") -> h.result = headerValue(line)
                line.startsWith("[SetUp ") || line.startsWith("[FEN ") -> h.setUp = true
            }
        } else if (line.isNotEmpty() && inHeaders) {
            // The movetext: one line in Lichess's dumps.
            inHeaders = false
            counts.gamesRead++
            if (counts.gamesRead % 1_000_000 == 0L) {
                System.err.println("book-build: ${counts.gamesRead} games read, ${counts.gamesKept} kept")
            }
            if (!passes(h)) continue
            if (countGame(line, h.result!!, counts)) {
                counts.gamesKept++
                filtered?.let { w ->
                    for (hl in headerLines) w.write(hl).also { w.write("\n") }
                    w.write("\n"); w.write(line); w.write("\n\n")
                }
            } else {
                counts.gamesUnreadable++
            }
        }
    }
}

class Entry(val key: Long, val code: Int, val games: Int, var weight: Int = 0)

class BuildResult(val entries: List<Entry>, val minCount: Int, val positions: Int, val collisions: Int)

private fun kept(moves: Map<Int, MoveStats>, minCount: Int): List<Pair<Int, MoveStats>> {
    val total = moves.values.sumOf { it.games.toLong() }
    return moves.entries.filter { (_, s) ->
        s.games >= minCount &&
            s.games * 100L >= MIN_SHARE_PERCENT * total &&
            !(s.games >= DROP_MIN_GAMES && s.halfPoints * 100 < DROP_BELOW_SCORE_PERCENT * 2L * s.games)
    }.map { it.key to it.value }.sortedBy { it.first }
}

/**
 * The entries for one minimum count: every kept pair in a Position the Book can reach from the start
 * by book Moves within [MAX_PLIES] plies (breadth first, so each Position at its lowest ply).
 */
fun select(counts: Counts, minCount: Int): BuildResult {
    val out = ArrayList<Entry>()
    val visited = HashSet<Long>()
    var collisions = 0
    var frontier = listOf(Position.START)
    visited += Position.START.polyglotKey
    for (depth in 0 until MAX_PLIES) {
        val next = ArrayList<Position>()
        for (position in frontier) {
            val key = position.polyglotKey
            val moves = counts.byKey[key] ?: continue
            for ((code, stats) in kept(moves, minCount)) {
                val move = Book.decodeMove(position, code)
                if (move == null) { collisions++; continue }
                out += Entry(key, code, stats.games)
                val child = position.play(move)
                if (depth + 1 < MAX_PLIES && visited.add(child.polyglotKey)) next += child
            }
        }
        frontier = next
    }
    // Scale each Position's game counts so its most played Move weighs 65,535.
    for ((_, group) in out.groupBy { it.key }) {
        val max = group.maxOf { it.games }.toLong()
        for (e in group) e.weight = ((e.games * 65_535L + max / 2) / max).toInt().coerceIn(1, 65_535)
    }
    out.sortWith(
        compareBy<Entry> { it.key.toULong() }
            .thenByDescending { it.weight }
            .thenBy { it.code },
    )
    return BuildResult(out, minCount, out.map { it.key }.distinct().size, collisions)
}

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).associate { (k, v) -> k to v }
    val outDir = File(opts["--out"] ?: usage())
    val sourceUrl = opts["--source-url"] ?: usage()
    val dumpSha = opts["--dump-sha256"] ?: usage()
    val filteredFile = opts["--save-filtered"]?.let(::File)

    val counts = Counts()
    val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.ISO_8859_1), 1 shl 20)
    val filtered = filteredFile?.also { it.parentFile?.mkdirs() }?.bufferedWriter(Charsets.ISO_8859_1)
    filtered.use { readGames(reader, counts, it) }

    var minCount = MIN_COUNT
    var result = select(counts, minCount)
    while (result.entries.size > MAX_ENTRIES) {
        minCount++
        result = select(counts, minCount)
    }

    val bin = java.io.ByteArrayOutputStream(result.entries.size * Book.ENTRY_BYTES)
    for (e in result.entries) bin.write(Book.entry(e.key, e.code, e.weight))
    val bytes = bin.toByteArray()
    outDir.mkdirs()
    File(outDir, "book.bin").writeBytes(bytes)

    val manifest = """
        |{
        |  "source": {
        |    "url": "$sourceUrl",
        |    "sha256": "$dumpSha",
        |    "licence": "CC0 1.0 (database.lichess.org)"
        |  },
        |  "filter": {
        |    "minElo": $MIN_ELO,
        |    "minEstimatedSeconds": $MIN_ESTIMATED_SECONDS,
        |    "estimate": "base + $INCREMENT_MOVES x increment",
        |    "termination": "$TERMINATION",
        |    "maxPlies": $MAX_PLIES,
        |    "minCount": $MIN_COUNT,
        |    "minCountUsed": ${result.minCount},
        |    "minSharePercent": $MIN_SHARE_PERCENT,
        |    "dropMinGames": $DROP_MIN_GAMES,
        |    "dropBelowScorePercent": $DROP_BELOW_SCORE_PERCENT,
        |    "maxEntries": $MAX_ENTRIES
        |  },
        |  "games": {
        |    "read": ${counts.gamesRead},
        |    "kept": ${counts.gamesKept},
        |    "unreadable": ${counts.gamesUnreadable}
        |  },
        |  "positionsCounted": ${counts.byKey.size},
        |  "positions": ${result.positions},
        |  "entries": ${result.entries.size},
        |  "keyCollisions": ${result.collisions},
        |  "bookBytes": ${bytes.size},
        |  "bookSha256": "${sha256(bytes)}"
        |}
        |""".trimMargin()
    File(outDir, "book-manifest.json").writeText(manifest)
    System.err.println(
        "book-build: ${counts.gamesRead} games read, ${counts.gamesKept} kept, ${counts.gamesUnreadable} unreadable; " +
            "${result.entries.size} entries in ${result.positions} positions (min count ${result.minCount}); " +
            "${bytes.size} bytes, sha256 ${sha256(bytes)}",
    )
}

private fun usage(): Nothing {
    System.err.println("usage: book-build --out DIR --source-url URL --dump-sha256 HEX [--save-filtered FILE] < games.pgn")
    exitProcess(2)
}
