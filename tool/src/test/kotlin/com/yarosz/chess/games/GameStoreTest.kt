package com.yarosz.chess.games

import com.yarosz.chess.rules.DrawOffer
import com.yarosz.chess.rules.Game
import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.Resignation
import com.yarosz.chess.rules.Side
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameStoreTest {

    private val dir: File = Files.createTempDirectory("game-store").toFile()
    private val main = File(dir, "games.json")
    private val backup = File(dir, "games.json.bak")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun GameRecord.play(vararg uci: String): GameRecord =
        uci.fold(this) { r, text -> r + checkNotNull(r.game.position.moveFromUci(text)) { text } }

    private val inProgress = (GameRecord(Game.of(), Side.BLACK, level = 5, takebacks = 1, gameHints = 2, date = "2026.09.28")
        .play("e2e4", "c7c5", "g1f3") + DrawOffer(Side.BLACK)).play("d7d6")

    /** A finished Game told apart by its Takebacks count. */
    private fun finished(n: Int) = GameRecord(Game.of(), level = 3, takebacks = n).play("e2e4") + Resignation(Side.BLACK)

    @Test
    fun `nothing saved yet`() {
        assertNull(GameStore(dir).load())
        assertNull(GameData().resume())
    }

    @Test
    fun `resume rebuilds the exact Position and event list`() {
        GameStore(dir).save(GameData().save(inProgress))
        val data = GameStore(dir).load()!!
        assertEquals(inProgress.game.position.fen, data.current!!.fen, "the FEN checkpoint")
        val resumed = data.resume()!!
        assertEquals(inProgress, resumed)
        assertEquals(inProgress.game.events, resumed.game.events)
        assertEquals(inProgress.game.position, resumed.game.position)
        assertEquals(Side.BLACK, resumed.game.openDrawOffer, "the Move after the offer was the offerer's own")
    }

    @Test
    fun `a PGN this build can't read resumes from the FEN checkpoint`() {
        val data = GameData().save(inProgress)
        val broken = data.copy(current = data.current!!.copy(pgn = "[Result \"*\"]\n\n1. e4 {unclosed"))
        val resumed = broken.resume()!!
        assertEquals(inProgress.game.position, resumed.game.position)
        assertEquals(emptyList(), resumed.game.events)
        assertNull(broken.copy(current = SavedGame("garbage", "not a fen")).resume())
    }

    @Test
    fun `a finished Game leaves current for the history`() {
        val record = inProgress.play("d2d4") + Resignation(Side.WHITE)
        val data = GameData().save(inProgress).save(record)
        assertNull(data.current)
        assertEquals(listOf(record), data.history())
        assertNull(data.finished.single().fen)
    }

    @Test
    fun `the history keeps the last 50, newest first`() {
        var data = GameData()
        for (n in 1..55) data = data.save(finished(n))
        GameStore(dir).save(data)
        val history = GameStore(dir).load()!!.history()
        assertEquals((55 downTo 6).toList(), history.map { it.takebacks })
    }

    @Test
    fun `Play from here saves the Game it replaces as unfinished`() {
        val fromPuzzle = GameRecord(Game.of(Position.fromFen("6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 30")), level = 6)
        val data = GameData().save(finished(1)).save(inProgress).startNew(fromPuzzle)
        assertEquals(fromPuzzle, data.resume())
        val replaced = data.history().first()
        assertEquals(inProgress, replaced)
        assertTrue("[Result \"*\"]" in data.finished.first().pgn)
        assertEquals(listOf(1), data.history().drop(1).map { it.takebacks })
        assertTrue("[SetUp \"1\"]" in data.current!!.pgn)
        assertEquals(fromPuzzle, GameData().startNew(fromPuzzle).resume(), "nothing to replace")
    }

    @Test
    fun `a Takeback's counter and cut event list are what resumes`() {
        val back = inProgress.play("d2d4").takeback()
        val data = GameStore(dir).run { save(GameData().save(back)); load()!! }
        val resumed = data.resume()!!
        assertEquals(inProgress.takebacks + 1, resumed.takebacks)
        assertEquals(back.game.events, resumed.game.events)
        assertEquals(Side.BLACK, resumed.game.position.sideToMove, "the user's move again")
    }

    @Test
    fun `a Game the computer's Move ended is reopened by a Takeback, once, through the file (X1)`() {
        val playing = GameRecord(Game.of(), Side.WHITE, level = 3, date = "2026.10.03", seed = 7L).play("f2f3", "e7e5", "g2g4")
        val mated = playing.play("d8h4")
        assertTrue(mated.game.isOver && mated.endedByComputersMove)
        val store = GameStore(dir)
        store.save(GameData().save(finished(1)).save(playing).save(mated))
        val ended = store.load()!!
        assertEquals(listOf(mated, finished(1)), ended.history())
        val shown = ended.history().first()
        assertTrue(ended.endedLast(shown))
        val back = shown.takeback()
        store.save(ended.reopen(shown, back))

        val reopened = store.load()!!
        assertEquals(back, reopened.resume())
        assertEquals(listOf("f2f3", "e7e5"), reopened.resume()!!.game.moves.map { it.uci })
        assertEquals(1, reopened.resume()!!.takebacks)
        assertEquals(listOf(finished(1)), reopened.history(), "no duplicate, no orphan")
        assertEquals(back.game.position.fen, reopened.current!!.fen, "the checkpoint")
        assertFalse(reopened.endedLast(shown))
        assertFailsWith<IllegalArgumentException> { reopened.reopen(shown, back) }
    }

    @Test
    fun `a corrupt main file falls back to the backup`() {
        val store = GameStore(dir)
        store.save(GameData().save(inProgress))
        store.save(GameData().save(inProgress.play("d2d4")))
        main.writeText("{\"current\": {\"pgn\": ")
        assertEquals(inProgress, store.load()!!.resume(), "the backup")
        store.save(GameData())
        assertEquals(inProgress, GameData.decode(backup.readText())!!.resume(), "the corrupt file didn't become the backup")
        assertTrue(File(dir, "games.json.corrupt").exists())
    }

    @Test
    fun `unknown fields and a newer schema are read for what this build knows`() {
        val pgn = Pgn.write(inProgress)
        main.writeText(
            """{"schemaVersion":4,"current":{"pgn":${jsonString(pgn)},"fen":"x","clock":12},
               |"finished":[{"pgn":"not pgn","rated":true}],"theme":"dark"}""".trimMargin(),
        )
        val data = GameStore(dir).load()!!
        assertEquals(4, data.schemaVersion)
        assertEquals(inProgress, data.resume())
        assertEquals(1, data.finished.size)
        assertEquals(emptyList(), data.history(), "an unreadable finished Game is left out")
    }

    @Test
    fun `missing fields take their defaults`() {
        main.writeText("{}")
        assertEquals(GameData(), GameStore(dir).load())
    }

    @Test
    fun `a failed rename leaves the main file as it was`() {
        GameStore(dir).save(GameData().save(inProgress))
        val failing = GameStore(dir, rename = { from, to -> if (to.name == "games.json") false else from.renameTo(to) })
        assertFailsWith<IOException> { failing.save(GameData()) }
        assertNotNull(GameStore(dir).load()!!.current)
    }

    private fun jsonString(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
