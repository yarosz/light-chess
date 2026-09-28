package com.yarosz.chess.puzzles

/** In-memory Packs for the session tests: one Band file per 100 points, and a manifest naming [sha]. */
object TestPacks {
    private const val FEN = "rn2k2r/ppB2pRp/4p3/8/1bPPn3/2N2q2/PPQ1NP2/R3K3 w Qkq - 3 13"
    private const val MOVES = "c7b8 f3f2 e1d1 f2f1"

    /** [Lines.MATE_IN_2]'s Position and Solution under another id and Puzzle Rating. */
    fun line(id: String, rating: Int, rd: Int = 75, moves: String = MOVES, fen: String = FEN) = "$id;$fen;$moves;$rating;$rd;mate"

    fun of(sha: String, lines: List<String>): Pack {
        val byBand = lines.groupBy { it.split(';')[3].toInt() / 100 * 100 }.toSortedMap()
        val files = HashMap<String, ByteArray>()
        val bands = byBand.map { (band, group) ->
            val sorted = group.sortedBy { it.split(';')[3].toInt() }
            val text = sorted.joinToString("\n", postfix = "\n")
            val file = "%04d.txt".format(band)
            files["${Pack.DIR}/$file"] = text.toByteArray()
            val ratings = sorted.map { it.split(';')[3].toInt() }
            """{"band":$band,"file":"$file","puzzles":${sorted.size},"minRating":${ratings.min()},"maxRating":${ratings.max()},"bytes":${text.length},"sha256":"x"}"""
        }
        files["${Pack.DIR}/manifest.json"] =
            """{"schemaVersion":1,"packSha256":"$sha","puzzles":${lines.size},"bands":[${bands.joinToString(",")}]}""".toByteArray()
        return Pack { path -> files[path] ?: error("no asset $path") }
    }
}
