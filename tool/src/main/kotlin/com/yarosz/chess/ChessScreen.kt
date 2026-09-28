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
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens

class ChessViewModel : LightViewModel<Unit>()

/** Placeholder: proves the Tool installs and opens. The real board and input arrive in v1 PR 3. */
@InitialScreen
class ChessScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, ChessViewModel>(sealedActivity) {

    override val viewModelClass: Class<ChessViewModel>
        get() = ChessViewModel::class.java

    override fun createViewModel() = ChessViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        LightTheme(colors = themeColors) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Board()
                LightText(text = "Chess", variant = LightTextVariant.Copy)
            }
        }
    }
}

private val LIGHT_SQUARE = Color(0xFFD8D8D8)
private val DARK_SQUARE = Color(0xFF8C8C8C)

@Composable
private fun Board() {
    Canvas(Modifier.size(BOARD_SIZE)) {
        val square = size.width / 8
        for (rank in 0 until 8) for (file in 0 until 8) {
            drawRect(
                color = if ((rank + file) % 2 == 0) DARK_SQUARE else LIGHT_SQUARE,
                topLeft = Offset(file * square, (7 - rank) * square),
                size = Size(square, square),
            )
        }
    }
}

private val BOARD_SIZE = 312.dp
