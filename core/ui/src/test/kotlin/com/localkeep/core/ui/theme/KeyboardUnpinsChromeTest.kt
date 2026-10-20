package com.localkeep.core.ui.theme

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the keyboard unpins the header and the actions. The arithmetic is the whole rule, so it is
 * pinned on the windows that decided it.
 */
class KeyboardUnpinsChromeTest {

    /** 308x548 at 2.0 with ~250dp of keyboard: the window SCL-001/003/006 were found on. */
    @Test
    fun `the 308dp floor with a keyboard unpins`() {
        assertTrue(keyboardUnpinsChrome(visibleHeight = (548 - 250).dp, fontScale = 2.0f))
        assertTrue(keyboardUnpinsChrome(visibleHeight = (548 - 250).dp, fontScale = 1.0f))
    }

    @Test
    fun `landscape unpins`() {
        assertTrue(keyboardUnpinsChrome(visibleHeight = (360 - 190).dp, fontScale = 2.0f))
        assertTrue(keyboardUnpinsChrome(visibleHeight = (411 - 260).dp, fontScale = 1.0f))
    }

    /** The control: a Pixel-sized portrait window at ordinary text keeps its pinned Save bar. */
    @Test
    fun `portrait with room to spare stays pinned`() {
        assertFalse(keyboardUnpinsChrome(visibleHeight = (891 - 300).dp, fontScale = 1.0f))
        assertFalse(keyboardUnpinsChrome(visibleHeight = (800 - 300).dp, fontScale = 1.0f))
    }

    @Test
    fun `the budget grows with text size because the chrome is text`() {
        assertFalse(keyboardUnpinsChrome(visibleHeight = 500.dp, fontScale = 1.0f))
        assertTrue(keyboardUnpinsChrome(visibleHeight = 500.dp, fontScale = 2.0f))
    }

    @Test
    fun `a font scale under 1 does not shrink the budget`() {
        assertTrue(keyboardUnpinsChrome(visibleHeight = 390.dp, fontScale = 0.85f))
    }
}
