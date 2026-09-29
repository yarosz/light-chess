package com.yarosz.chess

import com.yarosz.chess.correspondence.CorrespondenceGame
import com.yarosz.chess.correspondence.Delivery
import com.yarosz.chess.correspondence.Halt
import com.yarosz.chess.correspondence.HaltReason
import com.yarosz.chess.correspondence.Phone
import com.yarosz.chess.relay.FakeRelay
import com.yarosz.chess.relay.Protocol
import com.yarosz.chess.relay.Weather
import com.yarosz.chess.rules.Side
import kotlinx.coroutines.runBlocking

/**
 * Correspondence Games in every state the Play a friend screens show (W4), played for real by two
 * phones against the fake Relay (a fresh pair per scene, so no scene meets the cap of five): for
 * StripFitTest and FriendScreensTest. [a] created each Game as White; [b] took Black.
 */
class FriendScenes {
    val relay = FakeRelay()
    private val phones = mutableListOf<Phone>()
    private var seed = 100

    fun clean() = phones.forEach(Phone::clean)

    /** The Relay's time, as both phones estimate it (no skew). */
    val now: Long get() = relay.now

    inner class Pair(val a: Phone, val b: Phone, val id: String)

    private fun phone() = Phone(relay, ++seed, "scene$seed").also(phones::add)

    /** A started Game: [Pair.a] White, created it; [Pair.b] redeemed it. */
    fun started(): Pair = runBlocking {
        val a = phone()
        val b = phone()
        val invite = checkNotNull((a.c.createInvite(Side.WHITE, 3) as Delivery.Done).game)
        b.c.redeemInvite(checkNotNull(invite.invite?.code))
        a.c.syncAll()
        Pair(a, b, invite.gameId)
    }

    fun waiting(): CorrespondenceGame = runBlocking { checkNotNull((phone().c.createInvite(Side.BLACK, 7) as Delivery.Done).game) }

    /** White's view at the start: its Move. */
    fun yourMove(): CorrespondenceGame = started().let { it.a.game(it.id) }

    /** White's view after its first Move: their move. */
    fun theirMove(): CorrespondenceGame = runBlocking {
        val p = started()
        p.a.c.play(p.id, p.a.move(p.id, "e2e4"))
        p.a.game(p.id)
    }

    /** Black's view after White moved with a draw offer (W2). */
    fun drawOffered(): CorrespondenceGame = runBlocking {
        val p = started()
        p.a.c.play(p.id, p.a.move(p.id, "e2e4"), offerDraw = true)
        p.b.c.syncAll()
        p.b.game(p.id)
    }

    /** White's view once Black let its time run out. */
    fun timeUp(): CorrespondenceGame = runBlocking {
        val p = started()
        p.a.c.play(p.id, p.a.move(p.id, "e2e4"))
        relay.now += 3 * Protocol.DAY_MS + 1
        p.a.c.syncAll()
        p.a.game(p.id)
    }

    /** White's Move saved, not sent (offline). */
    fun notSent(): CorrespondenceGame = runBlocking {
        val p = started()
        p.a.weather = Weather(offline = 1.0)
        p.a.c.play(p.id, p.a.move(p.id, "e2e4"))
        p.a.weather = Weather()
        p.a.game(p.id)
    }

    fun stopped(reason: HaltReason): CorrespondenceGame = yourMove().copy(halt = Halt(reason))

    /** White resigned: the Result, before any rematch. */
    fun over(): CorrespondenceGame = runBlocking {
        val p = started()
        p.a.c.resign(p.id)
        p.a.game(p.id)
    }

    /** White offered a rematch ("Rematch sent"), and Black's view of it ("Rematch?"). */
    fun rematch(): kotlin.Pair<CorrespondenceGame, CorrespondenceGame> = runBlocking {
        val p = started()
        p.a.c.resign(p.id)
        p.b.c.syncAll()
        p.a.c.offerRematch(p.id)
        p.b.c.syncAll()
        p.a.game(p.id) to p.b.game(p.id)
    }
}
