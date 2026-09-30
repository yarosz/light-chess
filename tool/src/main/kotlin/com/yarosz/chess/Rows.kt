package com.yarosz.chess

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row as HorizontalRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.lightClickable
import com.yarosz.chess.board.PieceSet
import com.yarosz.chess.board.PieceVectors
import com.yarosz.chess.rules.Game
import com.yarosz.chess.rules.Piece
import com.yarosz.chess.rules.Side
import com.yarosz.chess.rules.san

/** The Menu pages' building blocks, shared by the Menu and the Play a friend pages. */
object Rows {
    /** One wheel detent scrolls one row (F3). */
    val HEIGHT = 48.dp

    /** The Menu's section spacing: what sets the Pieces row apart from a board's actions (N17). */
    val GAP = 16.dp

    /** A drawn piece in the Pieces row (N19): the height of a row's `Copy` line. */
    val PIECE = 24.dp

    /** The pieces the Pieces row draws (N19): White's king, queen and knight, in that order. */
    val PREVIEW = listOf(Piece.WHITE_KING, Piece.WHITE_QUEEN, Piece.WHITE_KNIGHT)
}

/**
 * A Menu's rows from its data (N5, N17): plain lines for the rows without an entry, the Pieces row
 * with its set drawn (N19), a gap before a row that asks for one, and [onTap] for the others.
 */
@Composable
fun <A> MenuItems(items: List<MenuItem<A>>, onTap: (A) -> Unit) {
    for (item in items) {
        if (item.gap) Spacer(Modifier.height(Rows.GAP))
        val entry = item.entry
        val set = item.pieces
        when {
            entry == null -> MenuLine(item.text, item.lighten)
            set != null -> PiecesRow(item.text, set) { onTap(entry) }
            else -> MenuRow(item.text, item.lighten) { onTap(entry) }
        }
    }
}

/**
 * The Pieces row (P2, N17, N19): its text, then [set]'s white king, queen and knight at the text's
 * height, drawn from the board's own vectors, so a tap shows the change on the Menu itself. One
 * target, labelled with its text.
 */
@Composable
fun PiecesRow(label: String, set: PieceSet, onClick: () -> Unit) {
    HorizontalRow(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Rows.HEIGHT)
            .lightClickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(text = label, variant = LightTextVariant.Copy, maxLines = 1, modifier = Modifier.padding(end = 12.dp))
        for (piece in Rows.PREVIEW) {
            Image(rememberVectorPainter(PieceVectors.vector(set, piece)), contentDescription = null, modifier = Modifier.size(Rows.PIECE))
        }
    }
}

/** A tappable row; [lighten] for one that does nothing now (W6: at the cap of five). */
@Composable
fun MenuRow(label: String, lighten: Boolean = false, onClick: () -> Unit) {
    LightText(
        text = label,
        variant = LightTextVariant.Copy,
        lighten = lighten,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Rows.HEIGHT)
            .lightClickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

@Composable
fun MenuLine(text: String, lighten: Boolean = false) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        lighten = lighten,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

/** One line of tappable options; the chosen one in the content colour, the others lightened. */
@Composable
fun MenuChoices(labels: List<String>, chosen: Int, onChoose: (Int) -> Unit) {
    HorizontalRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((i, label) in labels.withIndex()) {
            LightText(
                text = label,
                variant = LightTextVariant.Copy,
                lighten = i != chosen,
                underline = i == chosen,
                align = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Rows.HEIGHT)
                    .lightClickable(onClickLabel = label, role = Role.Button) { onChoose(i) }
                    .semantics {
                        contentDescription = label
                        selected = i == chosen
                    }
                    .padding(vertical = 12.dp),
            )
        }
    }
}

/** The Moves page (F11): SAN in two columns, one row per Move number, scrolled by the wheel. */
@Composable
fun MoveList(game: Game) {
    if (game.moves.isEmpty()) {
        MenuLine(UiCopy.NO_MOVES, lighten = true)
        return
    }
    val rows = ArrayList<Triple<Int, String, String>>()
    for ((ply, move) in game.moves.withIndex()) {
        val position = game.positions[ply]
        val san = position.san(move)
        if (position.sideToMove == Side.WHITE || rows.isEmpty()) {
            rows += Triple(position.fullmoveNumber, if (position.sideToMove == Side.WHITE) san else "…", if (position.sideToMove == Side.WHITE) "" else san)
        } else {
            val last = rows.removeAt(rows.lastIndex)
            rows += last.copy(third = san)
        }
    }
    for ((number, white, black) in rows) {
        HorizontalRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
            LightText(text = UiCopy.moveNumber(number), variant = LightTextVariant.Copy, lighten = true, modifier = Modifier.width(56.dp))
            LightText(text = white, variant = LightTextVariant.Copy, modifier = Modifier.width(120.dp))
            LightText(text = black, variant = LightTextVariant.Copy, modifier = Modifier.width(120.dp))
        }
    }
}
