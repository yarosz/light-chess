package com.yarosz.chess.games

import com.yarosz.chess.rules.DrawOffer
import com.yarosz.chess.rules.Game
import com.yarosz.chess.rules.Move
import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.Resignation
import com.yarosz.chess.rules.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameRecordTest {

    private fun GameRecord.play(vararg uci: String): GameRecord =
        uci.fold(this) { r, text -> r + checkNotNull(r.game.position.moveFromUci(text)) { text } }

    private fun uci(record: GameRecord) = record.game.moves.map(Move::uci)

    @Test
    fun `a Takeback cuts the user's last Move and the computer's reply, and counts`() {
        val record = GameRecord(Game.of(), Side.WHITE, level = 4).play("e2e4", "e7e5", "g1f3", "b8c6")
        val back = record.takeback()
        assertEquals(listOf("e2e4", "e7e5"), uci(back))
        assertEquals(1, back.takebacks)
        assertEquals(Side.WHITE, back.game.position.sideToMove)
        val twice = back.takeback()
        assertEquals(emptyList(), uci(twice))
        assertEquals(2, twice.takebacks)
        assertFalse(twice.canTakeBack)
        assertFailsWith<IllegalStateException> { twice.takeback() }
    }

    @Test
    fun `a Takeback while the computer thinks cuts only the user's Move`() {
        val record = GameRecord(Game.of(), Side.WHITE).play("e2e4", "e7e5", "g1f3")
        assertEquals(listOf("e2e4", "e7e5"), uci(record.takeback()))
    }

    @Test
    fun `as Black, the computer's first Move stays`() {
        val record = GameRecord(Game.of(), Side.BLACK).play("d2d4")
        assertFalse(record.canTakeBack)
        val later = record.play("d7d5", "c2c4").takeback()
        assertEquals(listOf("d2d4"), uci(later))
        assertEquals(Side.BLACK, later.game.position.sideToMove)
    }

    @Test
    fun `a Takeback drops a draw offer made after the user's Move`() {
        val record = GameRecord(Game.of(), Side.WHITE).play("e2e4") + DrawOffer(Side.WHITE)
        val back = record.takeback()
        assertEquals(emptyList(), back.game.events)
        assertEquals(null, back.game.openDrawOffer)
    }

    @Test
    fun `a finished Game takes no Takeback`() {
        val mated = GameRecord(Game.of(), Side.BLACK).play("f2f3", "e7e5", "g2g4", "d8h4")
        assertTrue(mated.game.isOver)
        assertFalse(mated.canTakeBack)
        assertFalse((GameRecord(Game.of()).play("e2e4") + Resignation(Side.WHITE)).canTakeBack)
    }

    @Test
    fun `the counters survive the record's PGN`() {
        val record = GameRecord(Game.of(Position.START), level = 2).play("e2e4", "e7e5").takeback().withGameHint().play("d2d4")
        val back = Pgn.read(Pgn.write(record))
        assertEquals(1, back.takebacks)
        assertEquals(1, back.gameHints)
        assertEquals(record, back)
    }

    @Test
    fun `bad counters and Levels are refused`() {
        assertFailsWith<IllegalArgumentException> { GameRecord(Game.of(), level = 0) }
        assertFailsWith<IllegalArgumentException> { GameRecord(Game.of(), takebacks = -1) }
        assertFailsWith<IllegalArgumentException> { GameRecord(Game.of(), date = "28/09/2026") }
    }

    @Test
    fun `a Takeback drops the evals of the Moves it cuts, and keeps the seed`() {
        val record = GameRecord(Game.of(), Side.WHITE, level = 4, seed = 7L).play("e2e4")
            .let { it.withComputerMove(it.game.position.moveFromUci("e7e5")!!, eval = 30) }
            .play("g1f3")
            .let { it.withComputerMove(it.game.position.moveFromUci("b8c6")!!, eval = 12) }
        assertEquals(mapOf(2 to 30, 4 to 12), record.evals)
        val back = record.takeback()
        assertEquals(mapOf(2 to 30), back.evals)
        assertEquals(7L, back.seed)
        assertFailsWith<IllegalArgumentException> { GameRecord(Game.of(), evals = mapOf(1 to 0)) }
    }
}
