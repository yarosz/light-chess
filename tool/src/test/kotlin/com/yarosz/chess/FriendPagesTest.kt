package com.yarosz.chess

import com.yarosz.chess.correspondence.HaltReason
import com.yarosz.chess.games.GameRecord
import com.yarosz.chess.relay.Protocol
import com.yarosz.chess.rules.Game
import com.yarosz.chess.rules.Resignation
import com.yarosz.chess.rules.Side
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Play a friend page's rows (W6), the Games page's merge (W5) and the copy that isn't a strip. */
class FriendPagesTest {
    private val scenes = FriendScenes()

    @AfterTest
    fun cleanUp() = scenes.clean()

    @Test
    fun `rows come in E7's order, with the Opponent Label and Time Left`() {
        val now = scenes.now
        val yours = scenes.yourMove()
        val theirs = scenes.theirMove()
        val invite = scenes.waiting()
        val stopped = scenes.stopped(HaltReason.OUT_OF_SYNC)
        val over = scenes.over()
        val rows = FriendRows.of(listOf(stopped, invite, theirs, over, yours), emptyList(), now)
        assertEquals(listOf(yours.gameId, theirs.gameId, invite.gameId, over.gameId, stopped.gameId), rows.map { it.gameId })
        assertEquals("${yours.label} · Your move · 3d", rows[0].text)
        assertEquals("${theirs.label} · Their move · 3d", rows[1].text)
        assertTrue(rows[2].text.endsWith(" · Expires in 48h") && rows[2].invite, rows[2].text)
        assertEquals("${over.label} · Lost", rows[3].text)
        assertEquals("${stopped.label} · Out of sync", rows[4].text)
    }

    @Test
    fun `your move comes soonest Time Left first, and a finished Game leaves after the rematch window`() {
        val now = scenes.now
        val fresh = scenes.yourMove()
        val older = fresh.copy(gameId = "0".repeat(64), startedAt = fresh.startedAt!! - Protocol.DAY_MS)
        assertEquals(listOf(older.gameId, fresh.gameId), FriendRows.of(listOf(fresh, older), emptyList(), now).map { it.gameId })
        val over = scenes.over()
        assertEquals(1, FriendRows.of(listOf(over), emptyList(), now).size)
        assertTrue(FriendRows.of(listOf(over), emptyList(), now + 49 * 3_600_000L).isEmpty(), "gone from the page, still in Games")
    }

    @Test
    fun `the Games page merges both kinds newest first, the Opponent Label where the Level goes (W5)`() {
        val over = scenes.over()
        val friendDay = FinishedGames.merge(emptyList(), listOf(over), ZoneOffset.UTC).single()
        assertTrue(Regex("""\d{4}\.\d{2}\.\d{2} · ${over.label} · Lost""").matches(friendDay.text), friendDay.text)
        val date = friendDay.record.date
        val newer = GameRecord(Game.of() + Resignation(Side.BLACK), Side.WHITE, level = 3, date = "9999.01.01")
        val older = GameRecord(Game.of() + Resignation(Side.WHITE), Side.WHITE, level = 1, date = "1999.01.01")
        val rows = FinishedGames.merge(listOf(newer, older), listOf(over, scenes.yourMove()), ZoneOffset.UTC)
        assertEquals(listOf("9999.01.01 · Level 3 · Won", "$date · ${over.label} · Lost", "1999.01.01 · Level 1 · Lost"), rows.map { it.text })
    }

    @Test
    fun `the Menu entry counts the user's Move (W6)`() {
        assertEquals("Play a friend", UiCopy.playFriend(0))
        assertEquals("Play a friend · Your move: 2", UiCopy.playFriend(2))
    }

    @Test
    fun `Time Left is one unit, rounded down (W4)`() {
        assertEquals("2d", UiCopy.timeLeft(2 * Protocol.DAY_MS + 23 * 3_600_000L))
        assertEquals("5h", UiCopy.timeLeft(5 * 3_600_000L + 59 * 60_000L))
        assertEquals("40m", UiCopy.timeLeft(40 * 60_000L + 59_000L))
        assertEquals("0m", UiCopy.timeLeft(-1))
    }
}
