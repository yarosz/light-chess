package com.yarosz.chess.board

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yarosz.chess.rules.Move
import com.yarosz.chess.rules.Piece
import com.yarosz.chess.rules.PieceType
import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.Side
import com.yarosz.chess.rules.Square

/** 8 × 39 dp: 117 px squares on the LP3 (R1.8). */
val POSITION_VIEW_SIZE: Dp = 312.dp

private fun gray(level: Int, alpha: Float = 1f) = Color(Shades.argb(level, alpha))

/**
 * An opponent's Move to animate as it lands, over [ms]: the piece on [move]'s destination slides in
 * from its origin. A new [id] starts the slide again. Puzzles slide in [MS] (A5); the computer's Moves
 * in a Game in [ENGINE_MS] (F11).
 */
data class Motion(val move: Move, val id: Int, val ms: Int = MS) {
    companion object {
        const val MS = 250
        const val ENGINE_MS = 200
    }
}

/**
 * Draws [position] in [pieceSet] (P1, P2), the promotion picker included, and reports touches as
 * [Touch]es (R1.6, R1.7).
 * [bottom] is the Side whose first rank is at the bottom. [input] carries the selection, targets,
 * drag and promotion picker; null draws the Position alone (Review, or while input is locked), and
 * touches still arrive as [Touch.Tap]s. [lastMove] gets the last-move shade and corner marks (A5).
 * [hint] gets the Puzzle Hint ring (A6), and [hintTarget] a target mark (the Game Hint's destination,
 * B5); [motion] slides the piece that just moved.
 */
@Composable
fun PositionView(
    position: Position,
    lastMove: Move?,
    input: MoveInput?,
    bottom: Side,
    onTouch: (Touch) -> Unit,
    modifier: Modifier = Modifier,
    description: String,
    hint: Square? = null,
    hintTarget: Square? = null,
    motion: Motion? = null,
    pieceSet: PieceSet = PieceSet.DEFAULT,
) {
    val slide = remember { Animatable(1f) }
    LaunchedEffect(motion) {
        if (motion == null) {
            slide.snapTo(1f)
        } else {
            slide.snapTo(0f)
            slide.animateTo(1f, tween(motion.ms, easing = LinearOutSlowInEasing))
        }
    }
    val painters = Piece.entries.map { rememberVectorPainter(PieceVectors.vector(pieceSet, it)) }
    val measurer = rememberTextMeasurer()
    var finger by remember { mutableStateOf<Offset?>(null) }
    val touch by rememberUpdatedState(onTouch)

    Canvas(
        modifier
            .size(POSITION_VIEW_SIZE)
            .semantics { contentDescription = description }
            .pointerInput(bottom) {
                fun squareAt(p: Offset): Square? {
                    val cell = size.width / 8f
                    val col = (p.x / cell).toInt()
                    val row = (p.y / cell).toInt()
                    if (p.x < 0 || p.y < 0 || col !in 0..7 || row !in 0..7) return null
                    return squareOf(col, row, bottom)
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val pressed = squareAt(down.position)
                    down.consume()
                    var dragging = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            if (dragging) touch(Touch.Cancel)
                            break
                        }
                        if (!change.pressed) {
                            change.consume()
                            if (pressed != null) {
                                touch(if (dragging) Touch.Release(pressed, squareAt(change.position)) else Touch.Tap(pressed))
                            }
                            break
                        }
                        if (!dragging && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            dragging = true
                            if (pressed != null) touch(Touch.Lift(pressed))
                        }
                        if (dragging) {
                            finger = change.position
                            touch(Touch.Over(squareAt(change.position)))
                        }
                        change.consume()
                    }
                    finger = null
                }
            }
    ) {
        val cell = size.width / 8f
        val hintCapture = hintTarget?.takeIf { position.pieceAt(it) != null }
        drawSquares(lastMove, bottom, cell, measurer, markedSquares(lastMove, input, checkedKing(position), hint, hintCapture))
        checkedKing(position)?.let { king ->
            drawCircle(
                gray(Shades.MARKER), radius = cell * Marks.CHECK_RING_RADIUS, center = centerOf(king, bottom, cell),
                style = Stroke(cell * Marks.CHECK_RING_STROKE),
            )
        }
        input?.selected?.let { square ->
            val inset = cell * Marks.SELECTION_BORDER / 2
            drawRect(
                gray(Shades.MARKER), topLeft = topLeftOf(square, bottom, cell) + Offset(inset, inset),
                size = Size(cell - 2 * inset, cell - 2 * inset), style = Stroke(cell * Marks.SELECTION_BORDER),
            )
        }
        val lifted = input?.dragFrom
        val sliding = motion?.move?.takeIf { slide.value < 1f }
        for ((square, piece) in position.pieces) {
            if (square == lifted) continue
            val at = topLeftOf(square, bottom, cell)
            if (sliding != null && square == sliding.to) {
                val from = topLeftOf(sliding.from, bottom, cell)
                drawPiece(painters[piece.ordinal], from + (at - from) * slide.value, cell)
            } else {
                drawPiece(painters[piece.ordinal], at, cell)
            }
        }
        hint?.let { square ->
            drawCircle(
                gray(Shades.MARKER), radius = cell * Marks.HINT_RING_RADIUS, center = centerOf(square, bottom, cell),
                style = Stroke(cell * Marks.HINT_RING_STROKE),
            )
        }
        hintTarget?.let { target ->
            val center = centerOf(target, bottom, cell)
            if (position.pieceAt(target) != null) {
                drawCircle(
                    gray(Shades.MARKER), radius = cell * Marks.CAPTURE_RING_RADIUS, center = center,
                    style = Stroke(cell * Marks.CAPTURE_RING_STROKE),
                )
            } else {
                drawCircle(gray(Shades.MARKER), radius = cell * Marks.DOT_RADIUS, center = center)
            }
        }
        if (input != null) {
            val captures = input.captures
            for (target in input.targets) {
                val center = centerOf(target, bottom, cell)
                if (target in captures) {
                    drawCircle(
                        gray(Shades.MARKER), radius = cell * Marks.CAPTURE_RING_RADIUS, center = center,
                        style = Stroke(cell * Marks.CAPTURE_RING_STROKE),
                    )
                } else {
                    drawCircle(gray(Shades.MARKER), radius = cell * Marks.DOT_RADIUS, center = center)
                }
            }
            input.dragOver?.takeIf { lifted != null }?.let { over ->
                val inset = cell * Marks.DRAG_OUTLINE / 2
                drawRect(
                    gray(Shades.MARKER), topLeft = topLeftOf(over, bottom, cell) + Offset(inset, inset),
                    size = Size(cell - 2 * inset, cell - 2 * inset), style = Stroke(cell * Marks.DRAG_OUTLINE),
                )
            }
            val at = finger
            val piece = lifted?.let { position.pieceAt(it) }
            if (at != null && piece != null) {
                // One square above the finger, so the finger doesn't hide it (R1.6).
                val top = (at.y - cell * 1.5f).coerceAtLeast(0f)
                drawPiece(painters[piece.ordinal], Offset(at.x - cell / 2, top), cell)
            }
            input.promotion?.let { drawPicker(it, painters, bottom, cell) }
        }
    }
}

/** The square at screen cell ([col], [row]) from the top left, with [bottom]'s first rank at the bottom. */
fun squareOf(col: Int, row: Int, bottom: Side): Square =
    if (bottom == Side.WHITE) Square.of(col, 7 - row) else Square.of(7 - col, row)

private fun topLeftOf(square: Square, bottom: Side, cell: Float): Offset {
    val col = if (bottom == Side.WHITE) square.file else 7 - square.file
    val row = if (bottom == Side.WHITE) 7 - square.rank else square.rank
    return Offset(col * cell, row * cell)
}

private fun centerOf(square: Square, bottom: Side, cell: Float) = topLeftOf(square, bottom, cell) + Offset(cell / 2, cell / 2)

private fun checkedKing(position: Position): Square? {
    if (!position.inCheck) return null
    val king = Piece.of(position.sideToMove, PieceType.KING)
    return position.pieces.firstOrNull { it.second == king }?.first
}

/**
 * The squares that carry a mark reaching their corners, where a coordinate would collide with it (K1):
 * the last Move's corner marks (A5), the selection border, the drag outline, and the check, Hint and
 * capture rings ([hintCapture] is the Game Hint's destination when it holds a piece). Target dots stay
 * in the middle of their square, so they are not here.
 */
internal fun markedSquares(lastMove: Move?, input: MoveInput?, checkedKing: Square?, hint: Square?, hintCapture: Square?): Set<Square> =
    buildSet {
        lastMove?.let { add(it.from); add(it.to) }
        input?.selected?.let(::add)
        input?.dragOver?.takeIf { input.dragFrom != null }?.let(::add)
        input?.let { addAll(it.captures) }
        listOfNotNull(checkedKing, hint, hintCapture).forEach(::add)
    }

private fun DrawScope.drawSquares(lastMove: Move?, bottom: Side, cell: Float, measurer: TextMeasurer, quiet: Set<Square>) {
    val marked = lastMove?.let { setOf(it.from, it.to) } ?: emptySet()
    for (row in 0 until 8) for (col in 0 until 8) {
        val square = squareOf(col, row, bottom)
        val topLeft = Offset(col * cell, row * cell)
        val shade = when {
            square in marked -> Shades.LAST_MOVE
            square.isLight -> Shades.LIGHT_SQUARE
            else -> Shades.DARK_SQUARE
        }
        drawRect(gray(shade), topLeft = topLeft, size = Size(cell, cell))
        if (square in marked) drawCorners(topLeft, cell)

        // Coordinates inside the edge squares (R1.7): ranks top-left of the left column, files
        // bottom-right of the bottom row; none on a square with a mark (K1).
        if (square in quiet) continue
        val textColor = gray(if (square.isLight) Shades.COORDINATE_ON_LIGHT else Shades.COORDINATE_ON_DARK)
        val style = TextStyle(color = textColor, fontSize = (cell * Marks.COORDINATE_SIZE).toSp(), fontWeight = FontWeight.Medium)
        val inset = cell * Marks.COORDINATE_INSET
        if (col == 0) {
            drawText(measurer, "${square.rank + 1}", topLeft = topLeft + Offset(inset, 0f), style = style)
        }
        if (row == 7) {
            val label = measurer.measure("${'a' + square.file}", style)
            drawText(
                label,
                topLeft = topLeft + Offset(cell - inset - label.size.width, cell - label.size.height.toFloat()),
            )
        }
    }
}

/** Four L-shaped corner marks inside a last-move square (A5). */
private fun DrawScope.drawCorners(topLeft: Offset, cell: Float) {
    val w = cell * Marks.CORNER_STROKE
    val l = cell * Marks.CORNER_LENGTH
    val color = gray(Shades.MARKER)
    for ((x, y) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)) {
        val cx = topLeft.x + x * (cell - w)
        val cy = topLeft.y + y * (cell - l)
        drawRect(color, topLeft = Offset(cx, cy), size = Size(w, l))
        val hx = topLeft.x + x * (cell - l)
        val hy = topLeft.y + y * (cell - w)
        drawRect(color, topLeft = Offset(hx, hy), size = Size(l, w))
    }
}

private fun DrawScope.drawPiece(painter: VectorPainter, topLeft: Offset, cell: Float) {
    val inset = cell * Marks.PIECE_INSET
    translate(topLeft.x + inset, topLeft.y + inset) {
        with(painter) { draw(Size(cell - 2 * inset, cell - 2 * inset)) }
    }
}

/** The four-piece promotion picker over the promotion file, the rest of the board dimmed (R1.6). */
private fun DrawScope.drawPicker(choice: MoveInput.PromotionChoice, painters: List<VectorPainter>, bottom: Side, cell: Float) {
    drawRect(gray(Shades.MARKER, Shades.DIM_ALPHA), size = size)
    choice.squares.forEachIndexed { i, square ->
        val topLeft = topLeftOf(square, bottom, cell)
        drawRect(gray(Shades.PICKER), topLeft = topLeft, size = Size(cell, cell))
        drawRect(gray(Shades.MARKER), topLeft = topLeft, size = Size(cell, cell), style = Stroke(cell * Marks.DRAG_OUTLINE))
        val piece = Piece.of(choice.side, PieceType.PROMOTIONS[i])
        drawPiece(painters[piece.ordinal], topLeft, cell)
    }
}
