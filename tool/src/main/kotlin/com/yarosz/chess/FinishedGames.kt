package com.yarosz.chess

import com.yarosz.chess.correspondence.CorrespondenceGame
import com.yarosz.chess.correspondence.Stage
import com.yarosz.chess.games.GameRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One row of the Games page (W5) and the record its Review opens. */
data class FinishedRow(val text: String, val record: GameRecord)

/**
 * The Games page's list (W5): the finished Games against the computer (`games.json`, newest first)
 * and the finished Correspondence Games (`correspondence.json`), merged newest first. Each kind keeps
 * its own cap of 50 in its own file. A Correspondence Game's row has its Opponent Label where the
 * Level goes: "2026.09.28 · ABCD · Won".
 *
 * A Game against the computer has only a date, so the merge goes by date, and on the same date the
 * Games against the computer come first (their file has no finer time).
 */
object FinishedGames {
    fun merge(computer: List<GameRecord>, friends: List<CorrespondenceGame>, zone: ZoneId = ZoneId.systemDefault()): List<FinishedRow> {
        val mine = computer.map { FinishedRow(UiCopy.gamesRow(it.date, it.level, it.game.result, it.userSide), it) }
        val theirs = friends.filter { it.stage == Stage.OVER && it.log != null }
            .sortedByDescending { it.closedAt ?: 0 }
            .map { game ->
                val date = DATE.format(Instant.ofEpochMilli(game.closedAt ?: game.serverTime).atZone(zone))
                val record = GameRecord(checkNotNull(game.log).game, game.seat.side, date = date)
                FinishedRow(UiCopy.friendGamesRow(date, game.label, record.game.result, game.seat.side), record)
            }
        val rows = ArrayList<FinishedRow>(mine.size + theirs.size)
        var i = 0
        var j = 0
        while (i < mine.size || j < theirs.size) {
            val takeMine = j >= theirs.size || (i < mine.size && mine[i].record.date >= theirs[j].record.date)
            rows += if (takeMine) mine[i++] else theirs[j++]
        }
        return rows
    }

    private val DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd")
}
