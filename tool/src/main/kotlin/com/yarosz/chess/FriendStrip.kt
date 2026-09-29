package com.yarosz.chess

import com.yarosz.chess.board.StripLayout
import com.yarosz.chess.correspondence.Correspondence
import com.yarosz.chess.correspondence.CorrespondenceGame
import com.yarosz.chess.correspondence.HaltReason
import com.yarosz.chess.correspondence.PendingSeat
import com.yarosz.chess.correspondence.Stage
import com.yarosz.chess.relay.InviteCodes
import com.yarosz.chess.rules.Move
import com.yarosz.chess.rules.san

/**
 * The Correspondence Game's strip buttons. There is no Game Hint, Takeback or Move now here: the
 * engine never touches a Correspondence Game (W10, contradiction 6).
 */
enum class FriendButton(val label: String, val description: String) {
    SEND(UiCopy.SEND, UiCopy.SEND_DESCRIPTION),
    UNDO(UiCopy.UNDO, UiCopy.UNDO_DESCRIPTION),
    RETRY(UiCopy.RETRY, UiCopy.RETRY_DESCRIPTION),
    ACCEPT_DRAW(UiCopy.ACCEPT, "Accept the draw"),
    DECLINE_DRAW(UiCopy.DECLINE, "Decline the draw"),
    CLAIM(UiCopy.CLAIM_WIN, UiCopy.CLAIM_WIN_DESCRIPTION),
    REMATCH(UiCopy.REMATCH, UiCopy.REMATCH_DESCRIPTION),
    ACCEPT_REMATCH(UiCopy.ACCEPT, "Accept the rematch"),
    DECLINE_REMATCH(UiCopy.DECLINE, "Decline the rematch"),
    CANCEL(UiCopy.CANCEL, UiCopy.CANCEL_DESCRIPTION),
    LATEST(UiCopy.LATEST, UiCopy.LATEST_DESCRIPTION),
    MENU(UiCopy.MENU, UiCopy.MENU_DESCRIPTION),
}

/**
 * What a Correspondence Game's strip shows (W4), as pure data so `StripFitTest` checks every case:
 * one line each but for a Result (R4.16). The invite page's strip is [invite].
 */
data class FriendStrip(val status: String, val buttons: List<FriendButton>, val statusLines: Int = 1) {
    companion object {
        /**
         * The strip for [game] at the Relay time [now] (the phone's estimate, V14). [chosen] is the Move
         * waiting for Send or Undo (F11); [sending] a request in flight; [notice] a few seconds' line.
         */
        fun of(game: CorrespondenceGame, now: Long, chosen: Move? = null, sending: Boolean = false, notice: String? = null, reviewPly: Int? = null): FriendStrip {
            val menu = FriendButton.MENU
            val log = game.log
            if (reviewPly != null && log != null) return FriendStrip(UiCopy.review(reviewPly, log.game.ply), listOf(FriendButton.LATEST))
            game.halt?.let { halt ->
                val status = when (halt.reason) {
                    HaltReason.OUT_OF_SYNC -> UiCopy.OUT_OF_SYNC
                    HaltReason.NEEDS_UPDATE -> UiCopy.UPDATE_CHESS
                    HaltReason.GONE -> UiCopy.GAME_DELETED
                    HaltReason.SEAT_LOST -> UiCopy.SEAT_LOST
                }
                return FriendStrip(status, listOf(menu))
            }
            if (sending) return FriendStrip(UiCopy.SENDING, listOf(menu))
            if (game.pending != null) return FriendStrip(UiCopy.NOT_SENT, listOf(FriendButton.RETRY, menu))
            if (notice != null) return FriendStrip(notice, listOf(menu))
            if (log == null) return invite(game, now)
            val position = log.game.position
            if (log.closed) {
                val rematch = log.rematch
                return when {
                    rematch == null -> FriendStrip(UiCopy.friendResult(log.game.result, game.seat.side), listOf(FriendButton.REMATCH, menu), StripLayout.STATUS_MAX_LINES)
                    rematch.accepted != null -> FriendStrip(UiCopy.friendResult(log.game.result, game.seat.side), listOf(menu), StripLayout.STATUS_MAX_LINES)
                    rematch.offeredBy == game.seat.side -> FriendStrip(UiCopy.REMATCH_SENT, listOf(menu))
                    else -> FriendStrip(UiCopy.REMATCH_OFFERED, listOf(FriendButton.ACCEPT_REMATCH, FriendButton.DECLINE_REMATCH))
                }
            }
            // W4's SAN + Send + Undo, plus Menu for W2's "Send and offer draw": the one strip with four
            // buttons (decision log "v3 PR 2 (implementation)"); StripFitTest holds it to one line.
            if (chosen != null) return FriendStrip(position.san(chosen), listOf(FriendButton.SEND, FriendButton.UNDO, menu))
            val left = (log.deadline ?: now) - now
            return when {
                position.sideToMove == game.seat.side && log.game.openDrawOffer == game.seat.side.opponent ->
                    FriendStrip(UiCopy.DRAW_OFFERED, listOf(FriendButton.ACCEPT_DRAW, FriendButton.DECLINE_DRAW))
                position.sideToMove == game.seat.side -> FriendStrip(UiCopy.yourMoveLeft(left), listOf(menu))
                left <= 0 -> FriendStrip(UiCopy.TIME_IS_UP, listOf(FriendButton.CLAIM, menu))
                else -> FriendStrip(UiCopy.theirMoveLeft(left), listOf(menu))
            }
        }

        /**
         * The invite page's strip (W4, G2): "Expires in 48h" with Cancel and Menu; after one tap on
         * Cancel ([confirming]), "Tap again to cancel" next to Cancel alone, which is what fits one
         * line; a cancel the Relay hasn't confirmed, "Not sent" with Retry.
         */
        fun invite(game: CorrespondenceGame, now: Long, confirming: Boolean = false, notice: String? = null): FriendStrip {
            val invite = game.invite
            return when {
                invite?.cancelling == true -> FriendStrip(UiCopy.NOT_SENT, listOf(FriendButton.RETRY, FriendButton.MENU))
                notice != null -> FriendStrip(notice, listOf(FriendButton.MENU))
                confirming -> FriendStrip(UiCopy.CANCEL_CONFIRM, listOf(FriendButton.CANCEL))
                else -> FriendStrip(UiCopy.expiresIn((invite?.expiresAt ?: now) - now), listOf(FriendButton.CANCEL, FriendButton.MENU))
            }
        }
    }
}

/**
 * One row of the Play a friend page (W6), and what a tap on it opens: the Game's board, its invite, a
 * Seat sent again, or with [menu] the Game's Menu, straight to Forget game for a Game the Relay
 * deleted (W13).
 */
data class FriendRow(val text: String, val gameId: String? = null, val seat: PendingSeat? = null, val invite: Boolean = false, val menu: Boolean = false)

/**
 * The Play a friend page's rows (W6), in E7's order: the user's Move first, soonest Time Left first;
 * then their move; then invites (and Seats being taken); then finished Games still inside V10's
 * rematch window; then Stopped Games.
 */
object FriendRows {
    fun of(games: List<CorrespondenceGame>, seats: List<PendingSeat>, now: Long): List<FriendRow> {
        fun left(game: CorrespondenceGame) = (game.log?.deadline ?: now) - now
        val stopped = games.filter { it.stopped }
        val live = games - stopped.toSet()
        val playing = live.filter { it.stage == Stage.ACTIVE }
        val yours = playing.filter { it.log?.game?.position?.sideToMove == it.seat.side }.sortedBy(::left)
        val theirs = (playing - yours.toSet()).sortedBy(::left)
        val invites = live.filter { it.stage == Stage.WAITING }
        val over = live.filter { it.stage == Stage.OVER && inRematchWindow(it, now) }.sortedByDescending { it.closedAt ?: 0 }
        return buildList {
            for (g in yours + theirs) add(FriendRow(UiCopy.friendRow(g.label, playingState(g, now)), g.gameId))
            for (g in invites) add(FriendRow(inviteText(g, now), g.gameId, invite = g.invite?.code != null))
            for (s in seats) add(FriendRow(UiCopy.friendRow(s.code?.let(InviteCodes::display) ?: s.label, UiCopy.NOT_SENT), seat = s))
            for (g in over) add(FriendRow(UiCopy.friendRow(g.label, overState(g)), g.gameId))
            for (g in stopped) add(FriendRow(UiCopy.friendRow(g.label, FriendStrip.of(g, now).status), g.gameId, menu = g.halt?.reason == HaltReason.GONE))
        }
    }

    /** V10: a Game that is over stays on the page while a rematch can still come. */
    fun inRematchWindow(game: CorrespondenceGame, now: Long): Boolean {
        val rematch = game.log?.rematch
        return when {
            rematch != null -> rematch.accepted == null
            else -> now < (game.closedAt ?: 0) + Correspondence.REMATCH_WINDOW_MS
        }
    }

    private fun playingState(game: CorrespondenceGame, now: Long): String {
        val log = game.log ?: return ""
        val left = (log.deadline ?: now) - now
        return when {
            game.pending != null -> UiCopy.NOT_SENT
            log.game.position.sideToMove == game.seat.side ->
                if (log.game.openDrawOffer == game.seat.side.opponent) UiCopy.DRAW_OFFERED else UiCopy.yourMoveLeft(left)
            left <= 0 -> UiCopy.TIME_IS_UP
            else -> UiCopy.theirMoveLeft(left)
        }
    }

    private fun inviteText(game: CorrespondenceGame, now: Long): String {
        val invite = game.invite
        val code = invite?.code
        return when {
            code == null -> UiCopy.friendRow(game.label, UiCopy.REMATCH_SENT)
            invite.cancelling -> UiCopy.friendRow(InviteCodes.display(code), UiCopy.NOT_SENT)
            else -> UiCopy.friendRow(InviteCodes.display(code), UiCopy.expiresIn(invite.expiresAt - now))
        }
    }

    private fun overState(game: CorrespondenceGame): String {
        val log = game.log ?: return ""
        val rematch = log.rematch
        return when {
            game.pending != null -> UiCopy.NOT_SENT
            rematch != null && rematch.accepted == null ->
                if (rematch.offeredBy == game.seat.side) UiCopy.REMATCH_SENT else UiCopy.REMATCH_OFFERED
            else -> UiCopy.outcome(log.game.result, game.seat.side)
        }
    }
}
