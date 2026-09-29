package com.yarosz.chess.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R4.17: a key's up and repeats go where its down went, so LightOS never sees half a press. */
class TakenKeysTest {

    private val click = 319
    private val back = 317

    @Test
    fun aTakenDownMakesItsUpTaken() {
        val keys = TakenKeys()
        assertTrue(keys.down(click, repeat = false) { true })
        assertTrue(keys.up(click))
    }

    @Test
    fun aDownLeftAloneLeavesItsUpToLightOs() {
        val keys = TakenKeys()
        assertFalse(keys.down(click, repeat = false) { false })
        assertFalse(keys.up(click))
    }

    @Test
    fun anUpWithNoDownIsNotTaken() {
        assertFalse(TakenKeys().up(click))
    }

    @Test
    fun theUpIsTakenOnceAndThePressForgotten() {
        val keys = TakenKeys()
        keys.down(click, repeat = false) { true }
        assertTrue(keys.up(click))
        assertFalse(keys.up(click))
    }

    @Test
    fun repeatsFollowTheDownWithoutAskingAgain() {
        val keys = TakenKeys()
        var asked = 0
        // The click leaves Review, so a second ask would say no: the repeat must not ask.
        keys.down(click, repeat = false) { asked++; true }
        assertTrue(keys.down(click, repeat = true) { asked++; false })
        assertTrue(keys.repeat(click))
        assertTrue(keys.up(click))
        assertEquals(1, asked)

        assertFalse(keys.down(back, repeat = false) { asked++; false })
        assertFalse(keys.down(back, repeat = true) { asked++; true })
        assertFalse(keys.repeat(back))
        assertFalse(keys.up(back))
        assertEquals(2, asked)
    }

    @Test
    fun aRepeatWithNoDownIsNotTaken() {
        val keys = TakenKeys()
        assertFalse(keys.down(click, repeat = true) { true })
        assertFalse(keys.repeat(click))
    }

    @Test
    fun eachKeyIsTrackedOnItsOwn() {
        val keys = TakenKeys()
        keys.down(back, repeat = false) { true }
        keys.down(click, repeat = false) { false }
        assertFalse(keys.up(click))
        assertTrue(keys.up(back))
    }

    @Test
    fun aNewPressAsksAfresh() {
        val keys = TakenKeys()
        keys.down(click, repeat = false) { true }
        keys.up(click)
        assertFalse(keys.down(click, repeat = false) { false })
        assertFalse(keys.up(click))
    }
}
