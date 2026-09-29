package com.yarosz.chess.games

import com.yarosz.chess.rules.DrawAcceptance
import com.yarosz.chess.rules.DrawOffer
import com.yarosz.chess.rules.DrawReason
import com.yarosz.chess.rules.DrawRefusal
import com.yarosz.chess.rules.FenException
import com.yarosz.chess.rules.Game
import com.yarosz.chess.rules.GameEvent
import com.yarosz.chess.rules.InvalidGameEventException
import com.yarosz.chess.rules.Move
import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.Resignation
import com.yarosz.chess.rules.Result
import com.yarosz.chess.rules.Side
import com.yarosz.chess.rules.TimeoutClaim
import com.yarosz.chess.rules.WinReason
import com.yarosz.chess.rules.moveFromSan
import com.yarosz.chess.rules.san

/** PGN text that isn't a Game this reader can rebuild. */
class PgnException(message: String) : IllegalArgumentException(message)

/**
 * The game record's text form (B7): PGN, in the export format, for one [GameRecord].
 *
 * Tags, in this order: the Seven Tag Roster (Event, Site, Date, Round, White, Black, Result); SetUp
 * "1" and FEN when the Game doesn't start from the standard Position (F7); Termination, a sentence
 * saying why the Game ended, when it has; then ours (B5/B7), named like PGN's own tags:
 *
 * - `Level`: the computer's Level, 1 to 8 (absent when no computer plays);
 * - `ThinkTime`: Level 8's think time in whole seconds (absent when not set);
 * - `Takebacks`: how many Takebacks the user took (contradiction 6);
 * - `GameHints`: how many Game Hints the user took;
 * - `Seed`: the Game's seed (absent when not set), so a resumed Game replays the computer's picks.
 *
 * White and Black are [YOU] and [COMPUTER]. The movetext is SAN from the rules core. Game Events that
 * aren't Moves go in comments as PGN-style commands: `{[%draw offer white]}`, `{[%draw accept
 * black]}`, `{[%draw refuse black]}`, and a timeout claim (v3; never in a Game against the computer)
 * `{[%claim white]}`. The computer's true evaluation before a Move it searched follows
 * that Move as `{[%eval -0.52]}`, Lichess's form (pawns, White's view). A resignation has no
 * command: it is what a decisive Result without checkmate means, so it is read back from the Result
 * tag.
 *
 * The reader rebuilds the Game through the core and rejects an illegal Move or a Result the Game
 * doesn't reach. It skips comments (keeping only the commands above), NAGs, variations, `;` comments
 * and `%` lines, and ignores tags it doesn't know.
 */
object Pgn {
    const val YOU = "You"
    const val COMPUTER = "Computer"
    const val EVENT = "Game against the computer"

    private const val MAX_LINE = 79

    fun write(record: GameRecord): String {
        val game = record.game
        val tags = buildList {
            add("Event" to EVENT)
            add("Site" to "?")
            add("Date" to record.date)
            add("Round" to "-")
            add("White" to if (record.userSide == Side.WHITE) YOU else COMPUTER)
            add("Black" to if (record.userSide == Side.BLACK) YOU else COMPUTER)
            add("Result" to resultToken(game.result))
            if (game.start != Position.START) {
                add("SetUp" to "1")
                add("FEN" to game.start.fen)
            }
            termination(game.result)?.let { add("Termination" to it) }
            record.level?.let { add("Level" to it.toString()) }
            record.thinkTimeSeconds?.let { add("ThinkTime" to it.toString()) }
            add("Takebacks" to record.takebacks.toString())
            add("GameHints" to record.gameHints.toString())
            record.seed?.let { add("Seed" to it.toString()) }
        }
        return buildString {
            for ((name, value) in tags) append('[').append(name).append(" \"").append(escape(value)).append("\"]\n")
            append('\n')
            append(wrap(movetext(game, record.evals)))
            append('\n')
        }
    }

    /** Reads one Game written by [write], or any PGN of one Game whose Moves and Result our core accepts. */
    fun read(text: String): GameRecord {
        val scanner = Scanner(text)
        val tags = scanner.tags()
        val start = tags["FEN"]?.let {
            try {
                Position.fromFen(it)
            } catch (e: FenException) {
                throw PgnException(e.message ?: "bad FEN tag")
            }
        } ?: Position.START

        var game = Game.of(start)
        val evals = HashMap<Int, Int>()
        var marker: String? = null
        fun add(event: GameEvent) {
            game = try {
                game + event
            } catch (e: InvalidGameEventException) {
                throw PgnException("at ply ${game.ply}: ${e.message}")
            }
        }
        var depth = 0
        while (true) {
            val token = scanner.next() ?: break
            if (marker != null) throw PgnException("text after the result $marker: $token")
            when (token) {
                is Token.Open -> depth++
                is Token.Close -> if (--depth < 0) throw PgnException("')' without '('")
                is Token.Comment -> if (depth == 0) {
                    commands(token.text).forEach(::add)
                    EVAL.find(token.text)?.let { m ->
                        if (game.ply > 0) evals[game.ply] = Math.round(m.groupValues[1].toDouble() * 100).toInt()
                    }
                }
                is Token.Symbol -> if (depth == 0) {
                    val san = token.text.replace(MOVE_NUMBER, "")
                    when {
                        token.text in RESULTS -> marker = token.text
                        san.isEmpty() || san.all { it == '!' || it == '?' } -> {}
                        else -> add(
                            game.position.moveFromSan(san)
                                ?: throw PgnException("illegal or unknown move '$san' at ply ${game.ply + 1} in ${game.position.fen}"),
                        )
                    }
                }
            }
        }
        if (depth != 0) throw PgnException("a variation isn't closed")

        val declared = tags["Result"] ?: marker ?: "*"
        if (declared !in RESULTS) throw PgnException("unknown Result \"$declared\"")
        if (marker != null && marker != declared) throw PgnException("the movetext ends $marker but the Result tag says $declared")
        if (game.result == null && (declared == "1-0" || declared == "0-1")) {
            add(Resignation(if (declared == "1-0") Side.BLACK else Side.WHITE))
        }
        val reached = resultToken(game.result)
        if (reached != declared) throw PgnException("the Result tag says $declared but the Game reaches $reached")

        return GameRecord(
            game = game,
            userSide = if (tags["Black"] == YOU || tags["White"] == COMPUTER) Side.BLACK else Side.WHITE,
            level = tags["Level"]?.let { number("Level", it).also { n -> if (n !in 1..8) throw PgnException("Level $n is not 1-8") } },
            thinkTimeSeconds = tags["ThinkTime"]?.let { number("ThinkTime", it).also { n -> if (n < 1) throw PgnException("ThinkTime $n") } },
            takebacks = tags["Takebacks"]?.let { number("Takebacks", it) } ?: 0,
            gameHints = tags["GameHints"]?.let { number("GameHints", it) } ?: 0,
            date = tags["Date"]?.takeIf { DATE.matches(it) } ?: GameRecord.UNKNOWN_DATE,
            seed = tags["Seed"]?.let { it.toLongOrNull() ?: throw PgnException("Seed \"$it\" is not a number") },
            evals = evals,
        )
    }

    /** PGN's Result token: `1-0`, `0-1`, `1/2-1/2`, or `*` while the Game goes on. */
    fun resultToken(result: Result?): String = when (result) {
        null -> "*"
        is Result.Draw -> "1/2-1/2"
        is Result.Win -> if (result.winner == Side.WHITE) "1-0" else "0-1"
    }

    /** The Termination tag's sentence, or null while the Game goes on. */
    fun termination(result: Result?): String? = when (result) {
        null -> null
        is Result.Win -> {
            val winner = if (result.winner == Side.WHITE) "White" else "Black"
            when (result.by) {
                WinReason.CHECKMATE -> "$winner wins by checkmate"
                WinReason.RESIGNATION -> "$winner wins by resignation"
                WinReason.TIME -> "$winner wins on time"
            }
        }
        is Result.Draw -> when (result.by) {
            DrawReason.STALEMATE -> "Draw by stalemate"
            DrawReason.AGREEMENT -> "Draw by agreement"
            DrawReason.REPETITION -> "Draw by threefold repetition"
            DrawReason.FIFTY_MOVE_RULE -> "Draw by the 50-move rule"
            DrawReason.INSUFFICIENT_MATERIAL -> "Draw by insufficient material"
        }
    }

    private fun movetext(game: Game, evals: Map<Int, Int>): List<String> = buildList {
        var ply = 0
        var numberNext = true
        for (event in game.events) {
            when (event) {
                is Move -> {
                    val position = game.positions[ply]
                    if (position.sideToMove == Side.WHITE) add("${position.fullmoveNumber}.")
                    else if (numberNext) add("${position.fullmoveNumber}...")
                    add(position.san(event))
                    ply++
                    numberNext = false
                    evals[ply]?.let {
                        add("{[%eval ${pawns(it)}]}")
                        numberNext = true
                    }
                }
                is DrawOffer -> { add(command("offer", event.side)); numberNext = true }
                is DrawAcceptance -> { add(command("accept", event.side)); numberNext = true }
                is DrawRefusal -> { add(command("refuse", event.side)); numberNext = true }
                is TimeoutClaim -> { add("{[%claim ${event.side.name.lowercase()}]}"); numberNext = true }
                is Resignation -> {} // the Result tag carries it
            }
        }
        add(resultToken(game.result))
    }

    /** Centipawns as pawns with two decimals: `-0.52`, `0.00`, `3.15`. */
    private fun pawns(cp: Int): String {
        val abs = Math.abs(cp.toLong())
        return (if (cp < 0) "-" else "") + "${abs / 100}." + "${abs % 100}".padStart(2, '0')
    }

    private fun command(kind: String, side: Side) = "{[%draw $kind ${side.name.lowercase()}]}"

    private fun commands(comment: String): List<GameEvent> = COMMAND.findAll(comment).map { match ->
        val side = if (match.groupValues[2] == "white") Side.WHITE else Side.BLACK
        when (match.groupValues[1]) {
            "offer" -> DrawOffer(side)
            "accept" -> DrawAcceptance(side)
            "refuse" -> DrawRefusal(side)
            else -> TimeoutClaim(side) // group 1 is empty for a claim
        }
    }.toList()

    private fun wrap(tokens: List<String>): String = buildString {
        var line = 0
        for (token in tokens) {
            if (line > 0 && line + 1 + token.length > MAX_LINE) {
                append('\n')
                line = 0
            }
            if (line > 0) {
                append(' ')
                line++
            }
            append(token)
            line += token.length
        }
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun number(tag: String, value: String): Int =
        value.toIntOrNull()?.takeIf { it >= 0 } ?: throw PgnException("$tag \"$value\" is not a count")

    private val RESULTS = setOf("1-0", "0-1", "1/2-1/2", "*")
    /** A move number, `12.` or `12...`, alone or stuck to its Move (`12.Nf3`). */
    private val MOVE_NUMBER = Regex("""^\d+\.+""")
    /** A draw command's kind in group 1, or a claim; the side in group 2. */
    private val COMMAND = Regex("""\[%(?:draw\s+(offer|accept|refuse)|claim)\s+(white|black)\s*]""")
    private val EVAL = Regex("""\[%eval\s+(-?\d+(?:\.\d+)?)\s*]""")
    private val DATE = Regex("""[0-9?]{4}\.[0-9?]{2}\.[0-9?]{2}""")

    private sealed interface Token {
        data object Open : Token
        data object Close : Token
        data class Comment(val text: String) : Token
        data class Symbol(val text: String) : Token
    }

    /** Splits PGN text into its tag pairs, then movetext tokens; NAGs, `;` comments and `%` lines vanish here. */
    private class Scanner(private val text: String) {
        private var i = 0

        fun tags(): Map<String, String> {
            val tags = LinkedHashMap<String, String>()
            while (true) {
                skipSpace()
                if (i >= text.length || text[i] != '[') return tags
                i++
                val name = StringBuilder()
                while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) name.append(text[i++])
                skipSpace()
                if (name.isEmpty() || i >= text.length || text[i] != '"') throw PgnException("bad tag near offset $i")
                i++
                val value = StringBuilder()
                while (true) {
                    if (i >= text.length) throw PgnException("tag ${name} isn't closed")
                    val c = text[i++]
                    if (c == '"') break
                    if (c == '\\' && i < text.length) value.append(text[i++]) else value.append(c)
                }
                skipSpace()
                if (i >= text.length || text[i] != ']') throw PgnException("tag $name has no ']'")
                i++
                tags[name.toString()] = value.toString()
            }
        }

        fun next(): Token? {
            while (true) {
                skipSpace()
                if (i >= text.length) return null
                val c = text[i]
                when {
                    c == '{' -> {
                        val end = text.indexOf('}', i + 1)
                        if (end < 0) throw PgnException("a comment isn't closed")
                        return Token.Comment(text.substring(i + 1, end)).also { i = end + 1 }
                    }
                    c == ';' -> skipLine()
                    c == '(' -> { i++; return Token.Open }
                    c == ')' -> { i++; return Token.Close }
                    c == '$' -> { i++; while (i < text.length && text[i].isDigit()) i++ }
                    else -> {
                        val start = i
                        while (i < text.length && !text[i].isWhitespace() && text[i] !in "{};()$[]") i++
                        if (i == start) throw PgnException("unexpected '$c' in the movetext")
                        return Token.Symbol(text.substring(start, i))
                    }
                }
            }
        }

        /** Skips whitespace and `%` escape lines. */
        private fun skipSpace() {
            while (i < text.length) {
                if (text[i] == '%' && (i == 0 || text[i - 1] == '\n')) skipLine()
                else if (text[i].isWhitespace()) i++
                else return
            }
        }

        private fun skipLine() {
            while (i < text.length && text[i] != '\n') i++
        }
    }
}
