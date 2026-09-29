package com.yarosz.chess.correspondence

import com.yarosz.chess.relay.EntryKind
import com.yarosz.chess.relay.FakeRelay
import com.yarosz.chess.relay.LogEntry
import com.yarosz.chess.relay.Protocol
import com.yarosz.chess.relay.Weather
import com.yarosz.chess.rules.Game
import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.Side
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Two phones playing one Correspondence Game through the fake Relay, in random order and bad
 * weather: requests lost either way, delivered twice, a Relay restarting, processes killed between
 * operations, the clock running past deadlines, and one phone acting in the middle of the other's
 * request. Properties:
 *
 * - every log a phone holds, and the Relay's, replays through the rules core with matching digests
 *   (no illegal event is ever accepted);
 * - a phone's log is always a prefix of the Relay's;
 * - once the weather clears and both sync, both hold the Relay's log, the same digest and the same
 *   Result, with nothing pending and nothing stopped.
 *
 * `-Dcorrespondence.seeds=<n>` runs more seeds (default [SEEDS]).
 */
class CorrespondencePropertyTest {

    private val seeds = System.getProperty("correspondence.seeds")?.toIntOrNull() ?: SEEDS

    private class Stats {
        var games = 0
        var over = 0
        var rolledBack = 0
        var conflicts = 0
        var restarts = 0
        var interleaved = 0
        val results = sortedMapOf<String, Int>()
    }

    @Test
    fun `two phones syncing in any order through bad weather end with the same Game`() {
        val stats = Stats()
        for (seed in 1..seeds) runBlocking { play(seed, stats) }
        println("correspondence property, $seeds seeds: ${stats.games} Games, ${stats.over} over, ${stats.rolledBack} roll-backs, " +
            "${stats.conflicts} seq conflicts, ${stats.restarts} restarts, ${stats.interleaved} interleaved actions; ${stats.results}")
        // The runs must reach the cases they are for.
        assertTrue(stats.over > seeds / 4, "Games that ended: ${stats.over}")
        assertTrue(stats.rolledBack > 0, "roll-backs: ${stats.rolledBack}")
        assertTrue(stats.conflicts > 0, "seq conflicts: ${stats.conflicts}")
        assertTrue(stats.interleaved > 0 && stats.restarts > 0)
        for (reason in listOf("RESIGNATION", "TIME", "AGREEMENT")) assertTrue((stats.results[reason] ?: 0) > 0, "no Game ended by $reason: ${stats.results}")
    }

    @Test
    fun `a phone never accepts an entry its rules core refuses, whatever the Relay sends`() {
        for (seed in 1..seeds) runBlocking { tampered(seed) }
    }

    private suspend fun play(seed: Int, stats: Stats) {
        val random = Random(seed)
        val relay = FakeRelay(seed = seed)
        val phones = listOf(Phone(relay, seed * 2, "a$seed"), Phone(relay, seed * 2 + 1, "b$seed"))
        try {
            val id = start(phones, random)
            stats.games++
            val weather = Weather(offline = 0.12, lostResponse = 0.12, duplicate = 0.08, restart = 0.05)
            phones.forEach { it.weather = weather }
            var nested = false
            for ((i, phone) in phones.withIndex()) {
                val other = phones[1 - i]
                phone.transport.beforeDelivery = { _ ->
                    if (!nested && random.nextInt(10) == 0) {
                        nested = true
                        stats.interleaved++
                        act(other, id, random, relay, stats)
                        nested = false
                    }
                }
            }
            repeat(STEPS) {
                val phone = phones[random.nextInt(2)]
                when (random.nextInt(40)) {
                    0 -> { phone.restart(); stats.restarts++ }
                    1, 2 -> relay.now += random.nextLong(Protocol.DAY_MS * 8)
                    else -> act(phone, id, random, relay, stats)
                }
                relay.now += random.nextLong(60_000)
                phones.forEach { check(it, id, relay) }
            }
            // The weather clears: both phones catch up.
            phones.forEach { it.weather = Weather(); it.transport.beforeDelivery = null }
            repeat(4) { phones.forEach { it.c.syncAll() } }
            val entries = relay.entries(id)
            for (phone in phones) {
                val game = phone.game(id)
                assertNull(game.halt, "seed $seed ${phone.name}: ${game.halt}")
                assertNull(game.pending, "seed $seed ${phone.name}")
                assertEquals(entries, game.entries, "seed $seed ${phone.name}")
            }
            val (a, b) = phones.map { it.log(id) }
            assertEquals(a.game.position.digest, b.game.position.digest)
            assertEquals(a.game.result, b.game.result)
            assertEquals(replay(entries).result, a.game.result)
            if (a.closed) stats.over++
            val result = a.game.result
            val name = when (result) {
                null -> "unfinished"
                is com.yarosz.chess.rules.Result.Win -> result.by.name
                is com.yarosz.chess.rules.Result.Draw -> result.by.name
            }
            stats.results.merge(name, 1, Int::plus)
            stats.rolledBack += phones.count { it.game(id).rolledBack != null }
            stats.conflicts += relay.refusals["seq_conflict"] ?: 0
        } finally {
            phones.forEach { it.clean() }
        }
    }

    /** A Game between [phones], started in clear weather: the first creates it, the second joins. */
    private suspend fun start(phones: List<Phone>, random: Random): String {
        val (a, b) = phones
        val invite = assertNotNull(done(a.c.createInvite(if (random.nextBoolean()) Side.WHITE else Side.BLACK, Protocol.DAYS_PER_MOVE.random(random))))
        done(b.c.redeemInvite(invite.invite!!.code!!))
        a.c.syncAll()
        return invite.gameId
    }

    /** One random thing [phone]'s user or its background job does in [id]. */
    private suspend fun act(phone: Phone, id: String, random: Random, relay: FakeRelay, stats: Stats) {
        val game = phone.c.game(id) ?: return
        val log = game.log ?: return
        val side = game.seat.side
        val position = log.game.position
        val roll = random.nextInt(100)
        when {
            roll < 30 -> phone.c.syncAll()
            game.pending != null -> phone.c.sync(id)
            log.closed -> phone.c.syncAll()
            position.sideToMove == side -> when {
                log.game.openDrawOffer == side.opponent && roll < 40 -> phone.c.acceptDraw(id)
                log.game.openDrawOffer == side.opponent && roll < 50 -> phone.c.declineDraw(id)
                roll < 51 && random.nextBoolean() -> phone.c.resign(id)
                else -> phone.c.play(id, position.legalMoves.random(random))
            }
            else -> when {
                roll < 40 && log.game.ply > 0 && log.game.openDrawOffer == null -> phone.c.offerDraw(id)
                roll < 50 -> phone.c.claimTimeout(id).also { if (it is Delivery.Refused && it.reason == Refusal.ROLLED_BACK) stats.rolledBack++ }
                roll < 51 && random.nextInt(4) == 0 -> phone.c.resign(id)
                else -> phone.c.syncAll()
            }
        }
    }

    /** The invariants that hold at every step, in any weather. */
    private fun check(phone: Phone, id: String, relay: FakeRelay) {
        val game = phone.game(id)
        assertNull(game.halt, "${phone.name}: ${game.halt}")
        val relayed = relay.entries(id)
        assertEquals(relayed.take(game.entries.size), game.entries, "${phone.name}'s log is a prefix of the Relay's")
        replay(game.entries)
        replay(relayed)
        assertNotNull(game.log, "${phone.name}'s saved log replays")
    }

    /**
     * [entries] replayed through the rules core alone (not [GameLog]): each Move legal, each digest
     * the Position's, each Game Event allowed where it stands. Returns the Game.
     */
    private fun replay(entries: List<LogEntry>): Game {
        var game = Game.of(Position.START)
        for (e in entries) {
            game = when (e.entryKind) {
                EntryKind.MOVE -> game + assertNotNull(game.position.moveFromUci(e.uci!!), "entry ${e.seq}")
                EntryKind.RESIGN -> game + com.yarosz.chess.rules.Resignation(e.side)
                EntryKind.DRAW_OFFER -> game + com.yarosz.chess.rules.DrawOffer(e.side)
                EntryKind.DRAW_ACCEPT -> game + com.yarosz.chess.rules.DrawAcceptance(e.side)
                EntryKind.DRAW_DECLINE -> game + com.yarosz.chess.rules.DrawRefusal(e.side)
                EntryKind.CLAIM -> game + com.yarosz.chess.rules.TimeoutClaim(e.side)
                else -> game
            }
            assertEquals(game.position.digest, e.hash, "entry ${e.seq}'s digest")
            if (e.entryKind == EntryKind.MOVE) assertEquals(game.isOver, e.end == true, "entry ${e.seq}'s end flag")
        }
        return game
    }

    /** One Game in which the Relay, at a random point, sends one corrupted entry. */
    private suspend fun tampered(seed: Int) {
        val random = Random(seed + 10_000)
        val relay = FakeRelay(seed = seed)
        val phones = listOf(Phone(relay, seed * 2, "a$seed"), Phone(relay, seed * 2 + 1, "b$seed"))
        try {
            val id = start(phones, random)
            repeat(random.nextInt(0, 12)) {
                for (phone in phones) {
                    phone.c.syncAll()
                    val game = phone.game(id)
                    val log = game.log ?: continue
                    if (!log.closed && log.game.position.sideToMove == game.seat.side) phone.c.play(id, log.game.position.legalMoves.random(random))
                }
            }
            phones.forEach { it.c.syncAll() }
            val log = phones[0].log(id)
            if (log.closed) return
            val honest = relay.entries(id)
            val toMove = log.game.position.sideToMove
            val legal = log.game.position.legalMoves.random(random)
            val after = log.game.position.play(legal)
            val illegal = listOf("a1a8", "e1e8", "h8a1", "d1d8").firstOrNull { log.game.position.moveFromUci(it) == null } ?: "a1a1"
            val ends = (log.game + legal).isOver
            val corruptions: List<(Long, String, Long) -> LogEntry> = listOf(
                { seq, g, now -> LogEntry("1.0", g, seq, log.game.ply + 1, toMove, "move", illegal, null, after.digest, null, now) },
                { seq, g, now -> LogEntry("1.0", g, seq, log.game.ply + 1, toMove, "move", legal.uci, null, log.game.position.digest, null, now) },
                { seq, g, now -> LogEntry("1.0", g, seq, log.game.ply + 1, toMove.opponent, "move", legal.uci, null, after.digest, null, now) },
                { seq, g, now -> LogEntry("1.0", g, seq, log.game.ply + 1, toMove, "move", legal.uci, if (ends) null else true, after.digest, null, now) },
                { seq, g, now -> LogEntry("1.0", g, seq, log.game.ply, toMove.opponent, "claim", null, null, log.game.position.digest, null, now) },
                { seq, g, now -> LogEntry("1.0", g, seq, log.game.ply, toMove, "drawAccept", null, null, log.game.position.digest, null, now) },
                { seq, g, now -> LogEntry("1.0", g, seq + 1, log.game.ply + 1, toMove, "move", legal.uci, null, after.digest, null, now) },
            )
            relay.tamper(id, entry = corruptions.random(random))
            for (phone in phones) {
                phone.c.syncAll()
                val game = phone.game(id)
                assertEquals(HaltReason.OUT_OF_SYNC, game.halt?.reason, "seed $seed ${phone.name}")
                assertEquals(honest, game.entries, "seed $seed ${phone.name}: the corrupted entry never joined the log")
                replay(game.entries)
                val refused = phone.c.play(id, legal)
                assertIs<Delivery.Refused>(refused)
            }
        } finally {
            phones.forEach { it.clean() }
        }
    }

    companion object {
        const val SEEDS = 40
        const val STEPS = 250
    }
}
