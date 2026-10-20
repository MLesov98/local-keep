package com.localkeep.core.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * Every screen crossing is a swap. Nothing animates, in either direction, on either end.
 *
 * This file used to assert the opposite for three of the four — that going back was still, and that
 * going forward "genuinely is an arrival". The first half was right and stays. The second half was
 * measured wrong on 2026-08-21 and the reasoning is worth keeping, because it is not obvious and it
 * cost six rounds of reports:
 *
 * A screen transition is not the only thing moving during a crossing. The app shell decides whether
 * to draw the bottom tab bar from `currentBackStackEntryAsState`, which flips on the `navigate()`
 * call — while the NavHost is still showing the previous screen, because it has to compose the
 * destination first, and on the emulator that takes around 400 ms. So for the length of the
 * transition the shell and the content disagreed about which screen the user was on, and the bar
 * appeared on the screen being left. Being a Scaffold slot, it also changed that screen's content
 * padding, so its footer jumped. Measured whole-screen, bottom third, leaving the Login form:
 *
 *     56 56 53 53 60 60 60 60 37
 *
 * 60 is the Login footer after the tab bar pushed it up — on a screen the user had already left.
 *
 * A transition cannot fix that; it can only lengthen the window in which it happens. Two things
 * close it: the shell waits for the destination on a pop (`rememberChromeEntry` in AppShell), and
 * every crossing is instantaneous, so there is no window left for anything to disagree in.
 *
 * The rule these four assertions serve, and the thing to check a change against: between two
 * settled screens the user may see them hand over, and may not see a third state.
 * `tools/crossing/` measures exactly that on a device, and is the reason any of this is known.
 */
class LkScreenPopEnterTest {

    @Test
    fun `the screen you go back to does not animate in`() {
        assertSame(EnterTransition.None, lkScreenPopEnter(reduceMotion = false, risePx = 27))
        // Reduced motion cannot make it any stiller, and must not accidentally make it move.
        assertSame(EnterTransition.None, lkScreenPopEnter(reduceMotion = true, risePx = 27))
    }

    @Test
    fun `the screen you go back from leaves at once`() {
        // It is drawn *above* the destination, which is already complete underneath it. Anything
        // this does — a fade, a slide — is a third state laid over the answer.
        assertSame(ExitTransition.None, lkScreenPopExit(reduceMotion = false, risePx = 27))
        assertSame(ExitTransition.None, lkScreenPopExit(reduceMotion = true, risePx = 27))
    }

    @Test
    fun `the screen you go forward to arrives complete`() {
        // Not an arrival gesture. It used to fade in over 460 ms while the screen underneath faded
        // out over 180, so two whole screens were legible at once — and everything that screen did
        // while being torn down was legible through it.
        assertSame(EnterTransition.None, lkScreenEnter(reduceMotion = false, risePx = 27))
        assertSame(EnterTransition.None, lkScreenEnter(reduceMotion = true, risePx = 27))
    }

    @Test
    fun `the screen you go forward from leaves at once`() {
        assertSame(ExitTransition.None, lkScreenExit(reduceMotion = false, risePx = 27))
        assertSame(ExitTransition.None, lkScreenExit(reduceMotion = true, risePx = 27))
    }
}
