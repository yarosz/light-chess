package com.yarosz.chess.book

import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.polyglotKey

/** Builds a Book in memory: (Position, UCI move, weight) entries, sorted as Polyglot sorts. */
internal fun bookOf(vararg entries: Triple<Position, String, Int>): Book {
    val rows = entries.map { (position, uci, weight) ->
        Triple(position.polyglotKey, Book.encodeMove(position, position.moveFromUci(uci)!!), weight)
    }.sortedWith(compareBy<Triple<Long, Int, Int>> { it.first.toULong() }.thenBy { it.second })
    return Book(rawBook(rows))
}

/** Raw entries (key, move code, weight), in the given order. */
internal fun rawBook(rows: List<Triple<Long, Int, Int>>): ByteArray =
    rows.fold(ByteArray(0)) { acc, (key, code, weight) -> acc + Book.entry(key, code, weight) }

internal fun Position.after(vararg uci: String): Position =
    uci.fold(this) { p, m -> p.play(p.moveFromUci(m)!!) }
