package com.yarosz.chess

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.yarosz.chess.puzzles.Pack
import com.yarosz.chess.rules.Position
import com.yarosz.chess.rules.Side
import com.yarosz.chess.rules.Square

class ChessViewModel : LightViewModel<Unit>() {
    val position: Position = Position.START
}

/**
 * Placeholder: proves the Tool installs, opens and runs the rules core. Pieces are FEN letters
 * (uppercase for White). The real board, cburnett pieces and input arrive in v1 PR 3.
 */
@InitialScreen
class ChessScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, ChessViewModel>(sealedActivity) {

    override val viewModelClass: Class<ChessViewModel>
        get() = ChessViewModel::class.java

    override fun createViewModel() = ChessViewModel()

    /** The Puzzles in the Tool's assets, read a Band at a time. The puzzle flow uses it (v1 PR 4). */
    val pack: Pack by lazy { Pack(lightContext::readAsset) }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val position = viewModel.position
        LightTheme(colors = themeColors) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Board(position)
                val toMove = if (position.sideToMove == Side.WHITE) "White" else "Black"
                LightText(text = "Chess · $toMove to move · ${position.legalMoves.size} moves", variant = LightTextVariant.Copy)
            }
        }
    }
}

private val LIGHT_SQUARE = Color(0xFFD8D8D8)
private val DARK_SQUARE = Color(0xFF8C8C8C)
private val BOARD_SIZE = 312.dp

@Composable
private fun Board(position: Position) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.Black)
    Canvas(Modifier.size(BOARD_SIZE)) {
        val square = size.width / 8
        for (rank in 0 until 8) for (file in 0 until 8) {
            val topLeft = Offset(file * square, (7 - rank) * square)
            drawRect(
                color = if (Square.of(file, rank).isLight) LIGHT_SQUARE else DARK_SQUARE,
                topLeft = topLeft,
                size = Size(square, square),
            )
            val piece = position.pieceAt(Square.of(file, rank)) ?: continue
            val text = measurer.measure(piece.fenChar.toString(), style)
            drawText(
                text,
                topLeft = topLeft + Offset((square - text.size.width) / 2, (square - text.size.height) / 2),
            )
        }
    }
}
