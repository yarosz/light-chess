package com.yarosz.chess.rules

import com.yarosz.chess.rules.CastlingRights.Companion.BLACK_KINGSIDE
import com.yarosz.chess.rules.CastlingRights.Companion.BLACK_QUEENSIDE
import com.yarosz.chess.rules.CastlingRights.Companion.WHITE_KINGSIDE
import com.yarosz.chess.rules.CastlingRights.Companion.WHITE_QUEENSIDE

/**
 * Where every piece stands, whose Move it is, and the castling, en passant and move-count facts that
 * decide which Moves are legal. Immutable: [play] returns a new Position.
 *
 * [enPassant] is the square a pawn just skipped, as FEN writes it, whether or not any capture onto it
 * is legal. [canonicalFen] and [digest] keep it only when one is (contradiction 8), and that is the
 * form repetition compares.
 *
 * Built for correctness over speed (R1.1): perft keeps it honest.
 */
class Position internal constructor(
    private val board: Array<Piece?>,
    val sideToMove: Side,
    val castling: CastlingRights,
    val enPassant: Square?,
    val halfmoveClock: Int,
    val fullmoveNumber: Int,
) {
    private val whiteKing = kingSquare(Side.WHITE)
    private val blackKing = kingSquare(Side.BLACK)

    fun pieceAt(square: Square): Piece? = board[square.index]

    /** Every square and its piece, a1 first. */
    val pieces: List<Pair<Square, Piece>>
        get() = (0..63).mapNotNull { i -> board[i]?.let { Square(i) to it } }

    /** The side to move's king is attacked. */
    val inCheck: Boolean get() = attacked(board, king(sideToMove), sideToMove.opponent)

    // Computed at most once per Position. A race only computes the same list twice.
    @Volatile private var legal: List<Move>? = null

    val legalMoves: List<Move>
        get() = legal ?: generateLegal().also { legal = it }

    val isCheckmate: Boolean get() = legalMoves.isEmpty() && inCheck

    val isStalemate: Boolean get() = legalMoves.isEmpty() && !inCheck

    /**
     * Neither side can ever checkmate: no pawns, rooks or queens, and either at most one minor piece
     * in all, or only bishops, all on squares of one colour.
     */
    val hasInsufficientMaterial: Boolean
        get() {
            var knights = 0
            var lightBishops = 0
            var darkBishops = 0
            for (i in 0..63) {
                val p = board[i] ?: continue
                when (p.type) {
                    PieceType.PAWN, PieceType.ROOK, PieceType.QUEEN -> return false
                    PieceType.KNIGHT -> knights++
                    PieceType.BISHOP -> if (Square(i).isLight) lightBishops++ else darkBishops++
                    PieceType.KING -> {}
                }
            }
            val minors = knights + lightBishops + darkBishops
            return minors <= 1 || (knights == 0 && (lightBishops == 0 || darkBishops == 0))
        }

    /** The Position after [move]. Throws if [move] is not legal here. */
    fun play(move: Move): Position {
        require(move in legalMoves) { "illegal move ${move.uci} in ${fen}" }
        return apply(move)
    }

    /**
     * The legal Move this UCI text names, or null. Accepts castling written as the king onto its own
     * rook (e1h1), as Lichess does (A4), and returns it in the king's-step form (e1g1).
     */
    fun moveFromUci(text: String): Move? {
        val parsed = Move.parseUci(text) ?: return null
        legalMoves.firstOrNull { it == parsed }?.let { return it }
        val piece = board[parsed.from.index] ?: return null
        val target = board[parsed.to.index]
        if (piece.type == PieceType.KING && target == Piece.of(piece.side, PieceType.ROOK) && parsed.promotion == null) {
            val step = if (parsed.to.file > parsed.from.file) 2 else -2
            val castle = Move(parsed.from, Square(parsed.from.index + step))
            if (parsed.from.index == homeKing(piece.side) && castle in legalMoves) return castle
        }
        return null
    }

    val fen: String get() = writeFen(this, canonical = false)

    /** FEN with the en passant square only when a legal en passant capture exists (contradiction 8). */
    val canonicalFen: String get() = writeFen(this, canonical = true)

    /**
     * The Position's digest: SHA-256 (lowercase hex) of the first four fields of [canonicalFen]
     * (R1.11, C4). Move counters don't enter it, so it identifies a Position for repetition and for
     * two phones checking they agree.
     */
    val digest: String get() = sha256Hex(repetitionKey)

    /** The first four canonical FEN fields: what a repetition compares. */
    internal val repetitionKey: String get() = canonicalFen.split(' ').take(4).joinToString(" ")

    /** A legal en passant capture exists right now. */
    internal val hasLegalEnPassant: Boolean
        get() = enPassant != null && legalMoves.any { it.to == enPassant && board[it.from.index]?.type == PieceType.PAWN }

    internal fun boardCopy(): Array<Piece?> = board.copyOf()

    override fun equals(other: Any?): Boolean =
        other is Position && board.contentEquals(other.board) && sideToMove == other.sideToMove &&
            castling == other.castling && enPassant == other.enPassant &&
            halfmoveClock == other.halfmoveClock && fullmoveNumber == other.fullmoveNumber

    override fun hashCode(): Int = fen.hashCode()

    override fun toString() = fen

    // --- move generation

    private fun king(side: Side) = if (side == Side.WHITE) whiteKing else blackKing

    private fun kingSquare(side: Side): Int {
        val k = Piece.of(side, PieceType.KING)
        for (i in 0..63) if (board[i] == k) return i
        error("no ${side.name.lowercase()} king")
    }

    private fun generateLegal(): List<Move> {
        val pseudo = pseudoLegal()
        val out = ArrayList<Move>(pseudo.size)
        val us = sideToMove
        for (m in pseudo) {
            val after = apply(m)
            if (!attacked(after.board, after.king(us), us.opponent)) out += m
        }
        return out
    }

    private fun pseudoLegal(): List<Move> {
        val out = ArrayList<Move>(64)
        val us = sideToMove
        for (from in 0..63) {
            val p = board[from] ?: continue
            if (p.side != us) continue
            when (p.type) {
                PieceType.PAWN -> pawnMoves(from, out)
                PieceType.KNIGHT -> for (to in Geometry.KNIGHT_TARGETS[from]) step(from, to, out)
                PieceType.BISHOP -> slide(from, Geometry.BISHOP_DIRECTIONS, out)
                PieceType.ROOK -> slide(from, Geometry.ROOK_DIRECTIONS, out)
                PieceType.QUEEN -> slide(from, Geometry.QUEEN_DIRECTIONS, out)
                PieceType.KING -> {
                    for (to in Geometry.KING_TARGETS[from]) step(from, to, out)
                    castlingMoves(from, out)
                }
            }
        }
        return out
    }

    private fun step(from: Int, to: Int, out: MutableList<Move>) {
        val target = board[to]
        if (target == null || target.side != sideToMove) out += Move(Square(from), Square(to))
    }

    private fun slide(from: Int, directions: IntArray, out: MutableList<Move>) {
        for (d in directions) {
            for (to in Geometry.RAYS[d][from]) {
                val target = board[to]
                if (target == null) {
                    out += Move(Square(from), Square(to))
                } else {
                    if (target.side != sideToMove) out += Move(Square(from), Square(to))
                    break
                }
            }
        }
    }

    private fun pawnMoves(from: Int, out: MutableList<Move>) {
        val white = sideToMove == Side.WHITE
        val forward = if (white) 8 else -8
        val startRank = if (white) 1 else 6
        val lastRank = if (white) 7 else 0
        val one = from + forward
        if (board[one] == null) {
            pawnTo(from, one, lastRank, out)
            val two = one + forward
            if (from shr 3 == startRank && board[two] == null) out += Move(Square(from), Square(two))
        }
        for (to in Geometry.PAWN_ATTACKS[sideToMove.ordinal][from]) {
            val target = board[to]
            if ((target != null && target.side != sideToMove) || to == enPassant?.index) pawnTo(from, to, lastRank, out)
        }
    }

    private fun pawnTo(from: Int, to: Int, lastRank: Int, out: MutableList<Move>) {
        if (to shr 3 == lastRank) {
            for (type in PieceType.PROMOTIONS) out += Move(Square(from), Square(to), type)
        } else {
            out += Move(Square(from), Square(to))
        }
    }

    private fun castlingMoves(from: Int, out: MutableList<Move>) {
        val us = sideToMove
        if (from != homeKing(us)) return
        val them = us.opponent
        val rook = Piece.of(us, PieceType.ROOK)
        val kingside = if (us == Side.WHITE) WHITE_KINGSIDE else BLACK_KINGSIDE
        val queenside = if (us == Side.WHITE) WHITE_QUEENSIDE else BLACK_QUEENSIDE
        if (castling.has(kingside) && board[from + 3] == rook &&
            board[from + 1] == null && board[from + 2] == null &&
            !attacked(board, from, them) && !attacked(board, from + 1, them) && !attacked(board, from + 2, them)
        ) out += Move(Square(from), Square(from + 2))
        if (castling.has(queenside) && board[from - 4] == rook &&
            board[from - 1] == null && board[from - 2] == null && board[from - 3] == null &&
            !attacked(board, from, them) && !attacked(board, from - 1, them) && !attacked(board, from - 2, them)
        ) out += Move(Square(from), Square(from - 2))
    }

    /** Plays a pseudo-legal [move] without checking that it is legal. */
    internal fun apply(move: Move): Position {
        val from = move.from.index
        val to = move.to.index
        val next = board.copyOf()
        val piece = next[from] ?: error("no piece on ${move.from}")
        val captured = next[to]
        next[from] = null
        next[to] = move.promotion?.let { Piece.of(piece.side, it) } ?: piece

        var newEnPassant: Square? = null
        if (piece.type == PieceType.PAWN) {
            if (to == enPassant?.index && captured == null && (from and 7) != (to and 7)) {
                next[if (piece.side == Side.WHITE) to - 8 else to + 8] = null
            }
            if (to - from == 16 || from - to == 16) newEnPassant = Square((from + to) / 2)
        }
        if (piece.type == PieceType.KING && (to - from == 2 || from - to == 2)) {
            val rookFrom = if (to > from) from + 3 else from - 4
            val rookTo = if (to > from) from + 1 else from - 1
            next[rookTo] = next[rookFrom]
            next[rookFrom] = null
        }
        val rights = castling.without(RIGHTS_LOST[from] or RIGHTS_LOST[to])
        val reset = piece.type == PieceType.PAWN || captured != null
        return Position(
            board = next,
            sideToMove = sideToMove.opponent,
            castling = rights,
            enPassant = newEnPassant,
            halfmoveClock = if (reset) 0 else halfmoveClock + 1,
            fullmoveNumber = if (sideToMove == Side.BLACK) fullmoveNumber + 1 else fullmoveNumber,
        )
    }

    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        val START: Position = fromFen(START_FEN)

        /** Parses FEN, strictly. Throws [FenException] for anything that isn't a legal Position. */
        fun fromFen(fen: String): Position = parseFen(fen)

        internal fun homeKing(side: Side) = if (side == Side.WHITE) 4 else 60

        // The castling rights lost when a Move starts or ends on a square (king and rook homes).
        private val RIGHTS_LOST = IntArray(64).also {
            it[4] = WHITE_KINGSIDE or WHITE_QUEENSIDE
            it[7] = WHITE_KINGSIDE
            it[0] = WHITE_QUEENSIDE
            it[60] = BLACK_KINGSIDE or BLACK_QUEENSIDE
            it[63] = BLACK_KINGSIDE
            it[56] = BLACK_QUEENSIDE
        }

        /** Whether [by] attacks [square] on [board]. */
        internal fun attacked(board: Array<Piece?>, square: Int, by: Side): Boolean {
            val pawn = Piece.of(by, PieceType.PAWN)
            for (s in Geometry.PAWN_ATTACKS[by.opponent.ordinal][square]) if (board[s] == pawn) return true
            val knight = Piece.of(by, PieceType.KNIGHT)
            for (s in Geometry.KNIGHT_TARGETS[square]) if (board[s] == knight) return true
            val king = Piece.of(by, PieceType.KING)
            for (s in Geometry.KING_TARGETS[square]) if (board[s] == king) return true
            val queen = Piece.of(by, PieceType.QUEEN)
            val rook = Piece.of(by, PieceType.ROOK)
            val bishop = Piece.of(by, PieceType.BISHOP)
            for (d in Geometry.ROOK_DIRECTIONS) {
                for (s in Geometry.RAYS[d][square]) {
                    val p = board[s] ?: continue
                    if (p == rook || p == queen) return true
                    break
                }
            }
            for (d in Geometry.BISHOP_DIRECTIONS) {
                for (s in Geometry.RAYS[d][square]) {
                    val p = board[s] ?: continue
                    if (p == bishop || p == queen) return true
                    break
                }
            }
            return false
        }
    }
}
