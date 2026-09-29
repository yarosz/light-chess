package com.yarosz.chess.correspondence

import com.yarosz.chess.relay.FakeRelay
import com.yarosz.chess.relay.FlakyTransport
import com.yarosz.chess.relay.RelayClient
import com.yarosz.chess.relay.RelayTransport
import com.yarosz.chess.relay.Weather
import com.yarosz.chess.rules.Move
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.assertIs

/**
 * One phone in a test: its own files, and a transport to the shared fake Relay whose weather the
 * test sets. [restart] is a process death: a new [Correspondence] over the same files. [skew] is how
 * far this phone's clock is ahead of the Relay's.
 */
class Phone(val relay: FakeRelay, seed: Int, val name: String = "phone$seed", private val wrap: ((RelayTransport) -> RelayTransport)? = null) {
    val dir: File = Files.createTempDirectory("correspondence-$name").toFile()
    val transport = FlakyTransport(relay, Random(seed))
    var skew = 0L
    val store = CorrespondenceStore(dir)
    var c = open()
        private set

    private fun open() = Correspondence(RelayClient(wrap?.invoke(transport) ?: transport), store) { relay.now + skew }

    fun restart() {
        c = open()
    }

    var weather: Weather
        get() = transport.weather
        set(value) {
            transport.weather = value
        }

    fun game(id: String): CorrespondenceGame = checkNotNull(c.game(id)) { "$name has no Game $id" }

    fun log(id: String): GameLog = checkNotNull(game(id).log) { "$name's Game $id hasn't started" }

    /** The legal Move [uci] in this phone's current Position of [id]. */
    fun move(id: String, uci: String): Move = checkNotNull(log(id).game.position.moveFromUci(uci)) { "$uci in ${log(id).game.position.fen}" }

    fun clean() {
        dir.deleteRecursively()
    }
}

fun done(delivery: Delivery): CorrespondenceGame? {
    assertIs<Delivery.Done>(delivery, "$delivery")
    return delivery.game
}

fun refused(delivery: Delivery, reason: Refusal): CorrespondenceGame? {
    assertIs<Delivery.Refused>(delivery, "$delivery")
    kotlin.test.assertEquals(reason, delivery.reason, "$delivery")
    return delivery.game
}

fun queued(delivery: Delivery): CorrespondenceGame {
    assertIs<Delivery.Queued>(delivery, "$delivery")
    return delivery.game
}
