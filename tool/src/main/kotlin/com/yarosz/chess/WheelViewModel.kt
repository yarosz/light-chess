package com.yarosz.chess

import android.view.KeyEvent
import com.thelightphone.sdk.LightViewModel
import com.yarosz.chess.board.TakenKeys
import com.yarosz.chess.board.Wheel

/**
 * A view model whose screen may take the wheel (R1.9, F2, F3). Screens say only whether they take a
 * press, in [onWheel]; the key-up and repeats follow that press ([TakenKeys], R4.17), so a wheel key
 * the screen used never reaches LightOS's flashlight or brightness, and one it left alone reaches
 * LightOS whole.
 */
abstract class WheelViewModel<T> : LightViewModel<T>() {
    private val keys = TakenKeys()

    /** A wheel press: true if the screen takes it. Asked once per press, never for its repeats. */
    protected abstract fun onWheel(key: Wheel): Boolean

    final override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        keys.down(keyCode, repeat = event.repeatCount > 0) { Wheel.of(keyCode)?.let(::onWheel) ?: false }

    final override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean = keys.repeat(keyCode)

    final override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = keys.up(keyCode)
}
