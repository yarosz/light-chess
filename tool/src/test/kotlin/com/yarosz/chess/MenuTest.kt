package com.yarosz.chess

import com.yarosz.chess.correspondence.HaltReason
import com.yarosz.chess.games.Confirm
import com.yarosz.chess.games.GameChoices
import com.yarosz.chess.games.GameData
import com.yarosz.chess.games.GameFlow
import com.yarosz.chess.games.GameRecord
import com.yarosz.chess.games.GameState
import com.yarosz.chess.games.SideChoice
import com.yarosz.chess.puzzles.Stage
import com.yarosz.chess.rules.Game
import com.yarosz.chess.rules.Resignation
import com.yarosz.chess.rules.Side
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What each board's Menu holds (N5) and what each strip carries beside its status (N3, N4, N9). */
class MenuTest {
    private val scenes = FriendScenes()

    @AfterTest
    fun cleanUp() = scenes.clean()

    /** Everything Home offers (N1): none of it belongs in a board's Menu (N5). */
    private val places = setOf(
        UiCopy.PUZZLES, UiCopy.PLAY_COMPUTER, UiCopy.PLAY_FRIEND, UiCopy.GAMES, UiCopy.ABOUT, UiCopy.PLAYER_RATING, UiCopy.MISSED,
    )

    private fun noPlaces(texts: List<String>) {
        for (text in texts) {
            assertFalse(text in places || text.startsWith(UiCopy.PIECES) || text.startsWith(UiCopy.PLAYER_RATING) ||
                text.startsWith(UiCopy.MISSED) || text.startsWith(UiCopy.PLAY_FRIEND) || text.startsWith("Puzzle "), "\"$text\" is a place or a setting")
        }
    }

    private val level8 = GameFlow.start(GameState(), GameChoices(level = 8, side = SideChoice.WHITE), 1L, "2026.09.30")

    @Test
    fun `the computer's Menu holds this board's actions only, in N5's order`() {
        val thinking = GameFlow.play(level8, level8.record!!.game.position.moveFromUci("e2e4")!!)
        val turn = GameFlow.computerReply(thinking, null)!!
        val yours = GameFlow.computerMoved(thinking, turn, "e7e5", 0)
        val menu = GameMenu.of(yours)
        assertEquals(
            listOf(GameMenuEntry.OFFER_DRAW, GameMenuEntry.RESIGN, GameMenuEntry.TAKEBACK, GameMenuEntry.FLIP, GameMenuEntry.MOVES, GameMenuEntry.THINK_TIME, GameMenuEntry.NEW_GAME),
            menu.mapNotNull { it.entry },
        )
        assertEquals(listOf("Offer draw", "Resign", "Takeback", "Flip board", "Moves", "Think Time · 3 s", "New game"), menu.map { it.text })
        noPlaces(menu.map { it.text })
    }

    @Test
    fun `while the computer thinks, the draw offer waits for the user's Move, lightened`() {
        val thinking = GameFlow.play(level8, level8.record!!.game.position.moveFromUci("e2e4")!!)
        val first = GameMenu.of(thinking).first()
        assertEquals(MenuItem<GameMenuEntry>(UiCopy.OFFER_DRAW_ON_YOUR_MOVE, lighten = true), first)
        noPlaces(GameMenu.of(thinking).map { it.text })
    }

    @Test
    fun `Resign asks for a second tap in place, and a Game that is over keeps only what still applies`() {
        val asking = level8.copy(confirming = Confirm.RESIGN)
        assertTrue(GameMenu.of(asking).any { it.text == UiCopy.RESIGN_CONFIRM && it.entry == GameMenuEntry.RESIGN })
        val over = GameState(GameData(), GameRecord(Game.of() + Resignation(Side.WHITE), Side.WHITE, level = 3))
        assertEquals(listOf(GameMenuEntry.FLIP, GameMenuEntry.MOVES, GameMenuEntry.NEW_GAME), GameMenu.of(over).mapNotNull { it.entry })
        assertTrue(GameMenu.of(GameState()).isEmpty(), "no Game, no Menu")
    }

    @Test
    fun `a Correspondence Game's Menu holds its actions, Moves and Rename, and no place (N5)`() {
        val yours = scenes.yourMove()
        val menu = FriendMenu.of(yours, FriendState(games = listOf(yours)), confirming = null)
        assertEquals(listOf(FriendMenuEntry.RESIGN, FriendMenuEntry.MOVES, FriendMenuEntry.RENAME), menu.mapNotNull { it.entry })
        assertEquals(UiCopy.OFFER_DRAW_AFTER_MOVE, menu.first().text, "no draw offer before the user's Move (W2)")
        noPlaces(menu.map { it.text })

        val theirs = scenes.theirMove()
        assertEquals(
            listOf(FriendMenuEntry.OFFER_DRAW, FriendMenuEntry.RESIGN, FriendMenuEntry.MOVES, FriendMenuEntry.RENAME),
            FriendMenu.of(theirs, FriendState(games = listOf(theirs)), null).mapNotNull { it.entry },
        )
        val chosen = FriendState(games = listOf(yours), chosen = mapOf(yours.gameId to yours.log!!.game.position.moveFromUci("e2e4")!!))
        assertEquals(FriendMenuEntry.SEND_AND_OFFER_DRAW, FriendMenu.of(yours, chosen, null).first().entry)
        assertEquals(UiCopy.RESIGN_CONFIRM, FriendMenu.of(yours, FriendState(games = listOf(yours)), FriendConfirm.RESIGN).first { it.entry == FriendMenuEntry.RESIGN }.text)
    }

    @Test
    fun `a stopped or finished Game's Menu opens with why, and offers Forget game (W13)`() {
        for (reason in HaltReason.entries) {
            val stopped = scenes.stopped(reason)
            val menu = FriendMenu.of(stopped, FriendState(games = listOf(stopped)), null)
            assertEquals(null, menu.first().entry, "the halt line first")
            assertEquals(listOf(FriendMenuEntry.MOVES, FriendMenuEntry.RENAME, FriendMenuEntry.FORGET), menu.mapNotNull { it.entry })
            noPlaces(menu.map { it.text })
        }
        val over = scenes.over()
        assertEquals(listOf(FriendMenuEntry.MOVES, FriendMenuEntry.RENAME, FriendMenuEntry.FORGET), FriendMenu.of(over, FriendState(games = listOf(over)), null).mapNotNull { it.entry })
        val forgetting = FriendMenu.of(over, FriendState(games = listOf(over)), FriendConfirm.FORGET)
        assertEquals(UiCopy.FORGET_CONFIRM, forgetting.last().text)
    }

    @Test
    fun `an invite's Menu has Rename and nothing that is a place (N5)`() {
        val invite = scenes.waiting()
        val menu = FriendMenu.of(invite, FriendState(games = listOf(invite)), null)
        assertEquals(listOf(FriendMenuEntry.RENAME), menu.mapNotNull { it.entry })
        noPlaces(menu.map { it.text })
    }

    @Test
    fun `the Puzzle board has no Menu (N5)`() {
        // No Puzzle button is the Menu, by label or by the label a script taps ("Open the Menu").
        for (button in PuzzleButton.entries) {
            assertFalse(button.label == UiCopy.MENU_TITLE || button.description == UiCopy.MENU_DESCRIPTION, "$button is a Menu")
        }
        // In every state the strip holds only its own actions, never a way to a Menu page.
        val own = setOf(PuzzleButton.HINT, PuzzleButton.SOLUTION, PuzzleButton.NEXT, PuzzleButton.LATEST)
        for (stage in Stage.entries) for (review in listOf(null, UiCopy.review(3, 5))) for (first in listOf(false, true)) {
            for (wrong in listOf(false, true)) for (side in Side.entries) {
                val strip = PuzzleStrip.of(stage, review, "Solved +12", justWrong = wrong, first = first, solver = side)
                assertTrue(strip.buttons.size <= 3)
                assertTrue(own.containsAll(strip.buttons), "${strip.buttons} in $stage")
                assertTrue(strip.buttons.none { it.label in places || it.description == UiCopy.MENU_DESCRIPTION }, "${strip.buttons} in $stage")
            }
        }
        assertTrue(PuzzleStrip.FINISHED.buttons.isEmpty(), "the end of the Pack: the arrow and the status only")
        assertEquals(listOf(PuzzleButton.HINT, PuzzleButton.SOLUTION), PuzzleStrip.of(Stage.PLAY, null, "", false, false, Side.WHITE).buttons)
        assertEquals(listOf(PuzzleButton.NEXT), PuzzleStrip.of(Stage.DONE, null, "Solved +12", false, false, Side.WHITE).buttons)
        assertEquals(listOf(PuzzleButton.LATEST, PuzzleButton.NEXT), PuzzleStrip.of(Stage.DONE, "Review · 3 of 5", "", false, false, Side.WHITE).buttons)
        assertEquals(UiCopy.FIRST_PUZZLE, PuzzleStrip.of(Stage.PLAY, null, "", false, true, Side.WHITE).status)
        assertTrue(PuzzleStrip.of(Stage.PLAY, null, "", false, true, Side.WHITE).buttons.isEmpty())
    }

    @Test
    fun `the computer's strip has the Menu mark, but not in Review or a replay (N4)`() {
        assertTrue(GameStrip.of(level8, null).menu)
        assertEquals(listOf(GameButton.HINT), GameStrip.of(level8, null).buttons)
        val thinking = GameFlow.play(level8, level8.record!!.game.position.moveFromUci("e2e4")!!)
        assertTrue(GameStrip.of(thinking, null).menu)
        assertEquals(listOf(GameButton.MOVE_NOW), GameStrip.of(thinking, null).buttons)
        assertFalse(GameStrip.of(thinking, 0).menu, "Review keeps Latest alone (R4.16)")
        val over = GameRecord(Game.of() + Resignation(Side.WHITE), Side.WHITE, level = 3)
        assertEquals(listOf(GameButton.NEXT), GameStrip.of(GameState(GameData(), over), null).buttons)
        assertTrue(GameStrip.of(GameState(GameData(), over), null).menu)
        val replay = GameStrip.replay(over, null)
        assertTrue(replay.buttons.isEmpty() && !replay.menu, "a replay's strip is the arrow and the Result (N3)")
    }

    @Test
    fun `a Correspondence Game's strip has the Menu mark in every state but Review, the offers included (N9)`() {
        val now = scenes.now
        val offered = FriendStrip.of(scenes.drawOffered(), now)
        assertEquals(UiCopy.DRAW_QUESTION, offered.status)
        assertEquals("Draw?", offered.status)
        assertEquals(listOf(FriendButton.ACCEPT_DRAW, FriendButton.DECLINE_DRAW), offered.buttons)
        assertTrue(offered.menu)
        val (_, rematch) = scenes.rematch()
        val theirs = FriendStrip.of(rematch, now)
        assertEquals("Rematch?", theirs.status)
        assertEquals(listOf(FriendButton.ACCEPT_REMATCH, FriendButton.DECLINE_REMATCH), theirs.buttons)
        assertTrue(theirs.menu)
        val yours = scenes.yourMove()
        assertTrue(FriendStrip.of(yours, now).menu)
        assertTrue(FriendStrip.of(yours, now, chosen = yours.log!!.game.position.moveFromUci("e2e4")).let { it.menu && it.buttons == listOf(FriendButton.SEND, FriendButton.UNDO) })
        for (reason in HaltReason.entries) assertTrue(FriendStrip.of(scenes.stopped(reason), now).menu)
        assertFalse(FriendStrip.of(scenes.theirMove(), now, reviewPly = 0).menu)
        // The Play a friend list's row keeps "Draw offered" (N9).
        val drawRow = FriendRows.of(listOf(scenes.drawOffered()), emptyList(), now).single().text
        assertTrue(drawRow.endsWith(UiCopy.DRAW_OFFERED), drawRow)
    }

    @Test
    fun `the invite's strip has the Menu mark, and Cancel alone while it asks again`() {
        val invite = scenes.waiting()
        val strip = FriendStrip.invite(invite, scenes.now)
        assertEquals(listOf(FriendButton.CANCEL), strip.buttons)
        assertTrue(strip.menu)
        assertFalse(FriendStrip.invite(invite, scenes.now, confirming = true).menu)
    }
}
