package com.yarosz.chess.relay

import com.yarosz.chess.correspondence.Correspondence
import com.yarosz.chess.correspondence.CorrespondenceGame
import com.yarosz.chess.correspondence.CorrespondenceStore
import com.yarosz.chess.correspondence.Stage
import com.yarosz.chess.rules.Side
import com.yarosz.chess.rules.san
import java.io.File
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * A second phone on the JVM, for playing one Game against the Tool on the emulator through a local
 * Relay (decision log W10's "one two-phone Game in bad weather"). It runs the same [Correspondence]
 * and [RelayClient] over OkHttp as the phone, keeps its files in `-Drelay.phone.dir`, and runs the
 * `;`-separated commands of `-Drelay.phone.cmd`, then writes what it holds to `last.txt` there.
 * Skipped unless `-Drelay.phone=<Relay URL>` is given; never in CI.
 *
 *     ./gradlew :tool:testDebugUnitTest --tests '*SecondPhoneTest' -Drelay.phone=http://127.0.0.1:8787 \
 *       -Drelay.phone.dir=/tmp/phone -Drelay.phone.cmd="redeem ABCD-EFGH; sync"
 *
 * Commands: `create white|black DAYS`, `redeem CODE`, `sync`, `play UCI [draw]`, `offer`, `accept`,
 * `decline`, `resign`, `claim`, `rematch`, `acceptRematch`, `declineRematch`, `show`. The Game acted on
 * is the newest one not over, else the newest.
 */
class SecondPhoneTest {

    @Test
    fun run() {
        val url = System.getProperty("relay.phone")
        assumeTrue("set -Drelay.phone=<Relay URL> to run the second phone", !url.isNullOrEmpty())
        val dir = File(checkNotNull(System.getProperty("relay.phone.dir")) { "set -Drelay.phone.dir" })
        val c = Correspondence(RelayClient(OkHttpTransport(url)), CorrespondenceStore(dir))
        val out = StringBuilder()
        runBlocking {
            for (command in System.getProperty("relay.phone.cmd").orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
                val words = command.split(Regex("\\s+"))
                val game = c.games.lastOrNull { it.stage != Stage.OVER } ?: c.games.lastOrNull()
                val id = game?.gameId
                val result: Any? = when (words[0]) {
                    "create" -> c.createInvite(if (words[1] == "black") Side.BLACK else Side.WHITE, words[2].toInt())
                    "redeem" -> c.redeemInvite(words[1])
                    "sync" -> c.syncAll()
                    "play" -> {
                        val move = checkNotNull(game?.log?.game?.position?.moveFromUci(words[1])) { "${words[1]} isn't legal here" }
                        c.play(id!!, move, offerDraw = words.getOrNull(2) == "draw")
                    }
                    "offer" -> c.offerDraw(id!!)
                    "accept" -> c.acceptDraw(id!!)
                    "decline" -> c.declineDraw(id!!)
                    "resign" -> c.resign(id!!)
                    "claim" -> c.claimTimeout(id!!)
                    "rematch" -> c.offerRematch(c.games.last { it.stage == Stage.OVER }.gameId)
                    "acceptRematch" -> c.acceptRematch(c.games.last { it.stage == Stage.OVER }.gameId)
                    "declineRematch" -> c.declineRematch(c.games.last { it.stage == Stage.OVER }.gameId)
                    "show" -> null
                    else -> error("unknown command $command")
                }
                out.appendLine("> $command: $result")
            }
        }
        for (game in c.games) out.appendLine(describe(game))
        for (seat in c.seats) out.appendLine("seat being taken: $seat")
        File(dir, "last.txt").writeText(out.toString())
        println(out)
    }

    private fun describe(game: CorrespondenceGame): String {
        val log = game.log
        val moves = log?.game?.let { g -> g.moves.mapIndexed { i, m -> g.positions[i].san(m) } }.orEmpty()
        return buildString {
            append("game ${game.gameId.take(8)} label=${game.label} side=${game.seat.side} stage=${game.stage}")
            game.invite?.code?.let { append(" code=${InviteCodes.display(it)}") }
            append(" moves=${moves.joinToString(" ")}")
            log?.game?.openDrawOffer?.let { append(" drawOfferedBy=$it") }
            log?.game?.result?.let { append(" result=$it") }
            log?.rematch?.let { append(" rematch=$it") }
            game.pending?.let { append(" pending=${it.kind}") }
            game.halt?.let { append(" halt=${it.reason}") }
            append(" entries=${game.entries.map { it.kind }}")
        }
    }
}
