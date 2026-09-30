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
import kotlin.test.assertFalse
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
    fun `Time Left is one unit, hours under 48 and days above to the nearest, the last hour in minutes rounded up (W4, W12)`() {
        val day = Protocol.DAY_MS
        val hour = 3_600_000L
        val minute = 60_000L
        // A fresh 3-day Game reads 3d on both phones, whichever way a few seconds of skew go.
        assertEquals("3d", UiCopy.timeLeft(3 * day))
        assertEquals("3d", UiCopy.timeLeft(3 * day - 1_000))
        assertEquals("3d", UiCopy.timeLeft(3 * day + 1_000))
        assertEquals("3d", UiCopy.timeLeft(2 * day + 23 * hour + 59 * minute))
        assertEquals("3d", UiCopy.timeLeft(2 * day + 12 * hour))
        assertEquals("2d", UiCopy.timeLeft(2 * day + 12 * hour - 1))
        assertEquals("7d", UiCopy.timeLeft(7 * day - 5 * minute))
        assertEquals("2d", UiCopy.timeLeft(2 * day))
        // Days from 47h 30m: "2d" then overstates by at most 30 minutes.
        assertEquals("2d", UiCopy.timeLeft(47 * hour + 30 * minute))
        // Under that, hours to the nearest, half up.
        assertEquals("47h", UiCopy.timeLeft(47 * hour + 30 * minute - 1))
        assertEquals("36h", UiCopy.timeLeft(36 * hour))
        assertEquals("24h", UiCopy.timeLeft(day))
        assertEquals("24h", UiCopy.timeLeft(23 * hour + 30 * minute))
        assertEquals("23h", UiCopy.timeLeft(23 * hour + 30 * minute - 1))
        assertEquals("6h", UiCopy.timeLeft(5 * hour + 30 * minute))
        assertEquals("5h", UiCopy.timeLeft(5 * hour + 30 * minute - 1))
        assertEquals("1h", UiCopy.timeLeft(hour))
        assertEquals("1h", UiCopy.timeLeft(59 * minute + 1))
        // The last hour, in minutes rounded up: "0m" only once the deadline has passed.
        assertEquals("59m", UiCopy.timeLeft(59 * minute))
        assertEquals("41m", UiCopy.timeLeft(40 * minute + 59_000))
        assertEquals("40m", UiCopy.timeLeft(40 * minute))
        assertEquals("1m", UiCopy.timeLeft(59_000))
        assertEquals("1m", UiCopy.timeLeft(1))
        assertEquals("0m", UiCopy.timeLeft(0))
        assertEquals("0m", UiCopy.timeLeft(-1))
        assertEquals("0m", UiCopy.timeLeft(-2 * day))
        assertEquals("0m", UiCopy.timeLeft(Long.MIN_VALUE))
        // A nonsense Deadline: clamped to a year, so the sums can't overflow.
        assertEquals("365d", UiCopy.timeLeft(Long.MAX_VALUE))
        assertEquals("Expires in 8760h", UiCopy.expiresIn(Long.MAX_VALUE))
    }

    @Test
    fun `an invite's expiry is in hours to the nearest, then minutes rounded up (W4, W12)`() {
        val hour = 3_600_000L
        assertEquals("Expires in 48h", UiCopy.expiresIn(48 * hour))
        assertEquals("Expires in 48h", UiCopy.expiresIn(48 * hour - 30_000))
        assertEquals("Expires in 47h", UiCopy.expiresIn(47 * hour))
        assertEquals("Expires in 1h", UiCopy.expiresIn(hour))
        assertEquals("Expires in 59m", UiCopy.expiresIn(59 * 60_000L))
        assertEquals("Expires in 1m", UiCopy.expiresIn(1))
        assertEquals("Expires in 0m", UiCopy.expiresIn(0))
    }

    @Test
    fun `the claim shows exactly when Time Left reads 0m (W12, V14)`() {
        val timeUp = scenes.timeUp()
        val deadline = timeUp.log!!.deadline!!
        assertEquals(UiCopy.theirMoveLeft(1), FriendStrip.of(timeUp, deadline - 1).status)
        assertEquals("${UiCopy.THEIR_MOVE} · 1m", FriendStrip.of(timeUp, deadline - 1).status)
        assertEquals(UiCopy.TIME_IS_UP, FriendStrip.of(timeUp, deadline).status)
        assertEquals(listOf(FriendButton.CLAIM), FriendStrip.of(timeUp, deadline).buttons)
    }

    @Test
    fun `a fresh Game reads the same Time Left on both phones, one behind the Relay's time and one ahead (W12)`() {
        for ((days, left) in listOf(1 to "24h", 3 to "3d", 7 to "7d")) {
            val p = scenes.started(days)
            val white = p.a.game(p.id)
            val black = p.b.game(p.id)
            val start = white.log!!.deadline!! - days * Protocol.DAY_MS
            // Each phone estimates the Relay's time (V14): White's 90 s behind, Black's 90 s ahead.
            for (skew in listOf(0L, 1_000L, 90_000L)) {
                assertEquals("${UiCopy.YOUR_MOVE} · $left", FriendStrip.of(white, start - skew).status, "$days-day, White $skew ms behind")
                assertEquals("${UiCopy.THEIR_MOVE} · $left", FriendStrip.of(black, start + skew).status, "$days-day, Black $skew ms ahead")
            }
        }
    }

    @Test
    fun `a deleted Game's row opens its Menu, where Forget game is, other rows open the board (W6, W13)`() {
        val gone = scenes.stopped(HaltReason.GONE)
        val outOfSync = scenes.stopped(HaltReason.OUT_OF_SYNC)
        val yours = scenes.yourMove()
        val rows = FriendRows.of(listOf(gone, outOfSync, yours), emptyList(), scenes.now).associateBy { it.gameId }
        assertEquals("${gone.label} · ${UiCopy.GAME_DELETED}", rows.getValue(gone.gameId).text)
        assertTrue(rows.getValue(gone.gameId).menu)
        assertFalse(rows.getValue(outOfSync.gameId).menu)
        assertFalse(rows.getValue(yours.gameId).menu)
    }
}
