package com.yarosz.chess.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.yarosz.chess.rules.CapturedPieces
import com.yarosz.chess.rules.Piece
import com.yarosz.chess.rules.PieceType
import com.yarosz.chess.rules.Side

/**
 * The captured-pieces row's measures and placement (P3), in dp, pure so `CapturedRowTest` can check
 * that the widest row fits the board. The row sits at the left of the action row under the board
 * (E4), tightened by [fit] to the room the buttons leave: at the left end the pieces the Side at the bottom has taken, at the right end the other Side's, each end
 * growing inwards from the board's edge. Both ends read pawn, knight, bishop, rook, queen from left
 * to right, so the left end has its pawns at the edge and the right end its queens. Pieces of one kind
 * overlap like a fanned hand; the Material Lead ("+7") sits just inside the leading Side's end.
 */
object CapturedRowLayout {
    /** The drawing's box: 51 px on the LP3 (the owner's mock-up: 52). */
    const val SIZE = 17f

    /** From one piece to the next of the same kind (the mock-up: 17 px). */
    const val SAME_STEP = 5.5f

    /** From the last piece of one kind to the first of the next (the mock-up: 50 px). */
    const val KIND_STEP = 16.5f

    /** Between an end's last piece and its Material Lead. */
    const val LEAD_GAP = 1f

    /** The drawings' top, inside the row's canvas (`ActionRow` lifts the canvas by half of it, E4). */
    const val TOP = 3f

    /** The row's canvas height: the drawings and the room above them. */
    const val BOTTOM = TOP + SIZE

    /** The two ends, their Material Lead included, never come closer than this. */
    const val MIN_GAP = 4f

    /** One drawing, its left edge [x] from the board's left edge. */
    data class Placed(val piece: Piece, val x: Float)

    /**
     * Where everything goes: [pieces] in the order they are drawn (each end from its board edge
     * inwards, so the inner piece of a pair is painted over the outer), then the Material Lead's
     * [lead] text with its left edge at [leadX]. [leftEnd] and [rightStart] bound the two ends.
     * [steps] scales [SAME_STEP] and [KIND_STEP]: 1 but where [fit] had to tighten the row.
     */
    data class Row(val pieces: List<Placed>, val lead: String?, val leadX: Float, val leftEnd: Float, val rightStart: Float, val steps: Float = 1f)

    /** The lead's text: "+7" for the Side ahead, by any amount; null when level. */
    fun leadText(captured: CapturedPieces): String? = captured.lead.takeIf { it != 0 }?.let { "+${kotlin.math.abs(it)}" }

    /**
     * [place] in a room [width] wide, tightened where it must be (E4): at the board's full width the
     * widest row keeps its ends [MIN_GAP] apart as placed (`CapturedRowTest`); in layout E's narrower
     * room beside the buttons, when they would come closer, every step shrinks by the one factor that
     * keeps them [MIN_GAP] apart, as a fanned hand closes. Nothing is dropped and nothing wraps.
     */
    fun fit(captured: CapturedPieces, bottom: Side, width: Float, textWidth: (String) -> Float): Row {
        val natural = place(captured, bottom, width, textWidth)
        if (natural.leftEnd + MIN_GAP <= natural.rightStart) return natural
        // The gap between the ends is linear in the steps' scale: solve for MIN_GAP.
        val closed = place(captured, bottom, width, textWidth, steps = 0f)
        val open = natural.rightStart - natural.leftEnd
        val shut = closed.rightStart - closed.leftEnd
        if (shut <= open) return natural
        val steps = ((shut - MIN_GAP) / (shut - open)).coerceIn(0f, 1f)
        return place(captured, bottom, width, textWidth, steps)
    }

    /**
     * Places [captured] on a board [width] wide with [bottom] at the bottom; [textWidth] measures the
     * lead; [steps] scales the steps between pieces ([fit]).
     */
    fun place(captured: CapturedPieces, bottom: Side, width: Float, textWidth: (String) -> Float, steps: Float = 1f): Row {
        val sameStep = SAME_STEP * steps
        val kindStep = KIND_STEP * steps
        val pieces = mutableListOf<Placed>()
        val top = bottom.opponent
        // Left end: what the bottom Side took (the top Side's pieces), from the left edge inwards.
        var x = 0f
        var leftEnd = 0f
        val leftKinds = CapturedPieces.ORDER.map { kind -> kind to captured.by(bottom).count { it == kind } }.filter { it.second > 0 }
        for ((kind, n) in leftKinds) {
            repeat(n) { i ->
                pieces += Placed(Piece.of(top, kind), x)
                leftEnd = x + SIZE
                x += if (i < n - 1) sameStep else kindStep
            }
        }
        // Right end: what the top Side took, from the right edge inwards, queens outermost.
        x = width - SIZE
        var rightStart = width
        val rightKinds = CapturedPieces.ORDER.reversed().map { kind -> kind to captured.by(top).count { it == kind } }.filter { it.second > 0 }
        for ((kind, n) in rightKinds) {
            repeat(n) { i ->
                pieces += Placed(Piece.of(bottom, kind), x)
                rightStart = x
                x -= if (i < n - 1) sameStep else kindStep
            }
        }
        val lead = leadText(captured)
        var leadX = 0f
        if (lead != null) {
            val w = textWidth(lead)
            if (captured.leader == bottom) {
                leadX = if (leftEnd > 0f) leftEnd + LEAD_GAP else 0f
                leftEnd = leadX + w
            } else {
                val right = if (rightStart < width) rightStart - LEAD_GAP else width
                leadX = right - w
                rightStart = leadX
            }
        }
        return Row(pieces, lead, leadX, leftEnd, rightStart, steps)
    }
}

/**
 * What the action row needs to draw the captured-pieces row (P3, E4): the Game's [captured] pieces
 * at the Ply on screen, the Side at the [bottom] of the board and the player's [pieceSet].
 * [description] is the row's accessibility label.
 */
data class CapturedRowState(val captured: CapturedPieces, val bottom: Side, val pieceSet: PieceSet, val description: String) {
    /**
     * The row's pieces show once something is captured (or the material is not level). The buttons
     * sit at the action row's right either way, so nothing moves when the first piece is taken.
     */
    val shown: Boolean get() = !captured.isEmpty
}

/** Every piece but the kings: the row never draws a king. */
private val CAPTURABLE = Piece.entries.filter { it.type != PieceType.KING }

/** The captured-pieces row itself, [width] wide: the pieces in [CapturedRowLayout]'s places and the Material Lead. */
@Composable
fun CapturedRow(state: CapturedRowState, width: Dp, modifier: Modifier = Modifier) {
    val painters = CAPTURABLE.associateWith { rememberVectorPainter(PieceVectors.captured(state.pieceSet, it)) }
    val measurer = rememberTextMeasurer()
    val colors = LightThemeTokens.colors
    val base = LightThemeTokens.typography.superfine
    val style = base.copy(fontSize = base.fontSize.value.designVerticalPxToSp(), lineHeight = base.lineHeight.value.designVerticalPxToSp(), color = colors.contentSecondary)
    Canvas(
        modifier
            .width(width)
            .height(CapturedRowLayout.BOTTOM.dp)
            .semantics { contentDescription = state.description }
    ) {
        val dp = 1.dp.toPx()
        val row = CapturedRowLayout.fit(state.captured, state.bottom, size.width / dp) { text ->
            measurer.measure(text, style).size.width / dp
        }
        val box = CapturedRowLayout.SIZE * dp
        for ((piece, x) in row.pieces) {
            val painter = painters.getValue(piece)
            translate(x * dp, CapturedRowLayout.TOP * dp) {
                with(painter) { draw(Size(box, box)) }
            }
        }
        row.lead?.let { lead ->
            val text = measurer.measure(lead, style)
            val centre = (CapturedRowLayout.TOP + CapturedRowLayout.SIZE / 2) * dp
            drawText(text, topLeft = Offset(row.leadX * dp, centre - text.size.height / 2f))
        }
    }
}
