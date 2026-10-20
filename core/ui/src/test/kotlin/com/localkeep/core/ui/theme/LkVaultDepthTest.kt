package com.localkeep.core.ui.theme

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lock/unlock crossing is a depth move, and a depth move you can see.
 *
 * The visual pass of 2026-09-15 found it was neither: unlock read as a cross-fade — one 20 fps frame
 * holding the Vault header, "Opening your vault…" and a ghost of "Welcome back" at once
 * (`rec/flow01-unlock-z1`) — and panic lock as a hard cut (`rec/flow12-settings`). Both are the same
 * cause. A 1.05 scale and a fade of the *same* length are not a scale plus a fade; the fade is the
 * only thing with anything to show in the first 100 ms, and by the time the scale has travelled the
 * remaining 4% there is nothing left underneath to measure it against.
 *
 * What these pin is therefore not "it animates" but the two facts the fix rests on:
 *
 *  - the travel is [LkMotion.VaultDepth] in both directions and they are each other's inverse, so
 *    locking and unlocking are one gesture seen from two sides rather than two similar ones; and
 *  - **the travel outlasts the opacity by a wide margin**, which is what makes the arriving screen
 *    occlude rather than dissolve. That ratio is the fix, and it needs its own assertion because
 *    the equalities below are only as strong as the two specs they are written against: someone
 *    putting the fade back on the scale's clock would edit production *and* [fade] together and the
 *    four equalities would go green again. `the travel outlasts the opacity` is what that edit hits.
 *
 * Reduced motion keeps its own case: the setting exists to remove movement, and a 6% scale is
 * movement. All four must collapse to a travel-free swap, which is asserted by equality against a
 * transition that carries no scale at all.
 */
class LkVaultDepthTest {

    private val fade = tween<Float>(LkMotion.Instant, easing = LkMotion.Standard)
    private val travel = tween<Float>(LkMotion.Screen, easing = LkMotion.Emphasized)
    private val near = 1f + LkMotion.VaultDepth
    private val far = 1f - LkMotion.VaultDepth

    @Test
    fun `the travel stays inside the 4 to 6 percent the spec allows`() {
        assertTrue(
            LkMotion.VaultDepth in 0.04f..0.06f,
            "VaultDepth is ${LkMotion.VaultDepth}. Past 6% this stops being a depth cue and starts " +
                "being a zoom, on a transition the user crosses every time the phone leaves a pocket.",
        )
    }

    @Test
    fun `the travel outlasts the opacity`() {
        assertTrue(
            travel.durationMillis >= fade.durationMillis * 3,
            "the scale runs ${travel.durationMillis}ms against a ${fade.durationMillis}ms fade. " +
                "Put them on the same clock and the crossing reads as a dissolve with a slight " +
                "wobble — which is what the 2026-09-15 visual pass filed, and the same 'two whole " +
                "screens legible at once' defect LkScreenPopEnterTest records being removed from " +
                "ordinary crossings.",
        )
    }

    @Test
    fun `locking settles the unlock screen down onto the vault`() {
        assertEquals(
            fadeIn(fade) + scaleIn(travel, initialScale = near),
            lkVaultSealEnter(reduceMotion = false),
        )
        assertEquals(
            fadeOut(fade) + scaleOut(travel, targetScale = far),
            lkVaultSealExit(reduceMotion = false),
        )
    }

    @Test
    fun `unlocking is the exact inverse`() {
        assertEquals(
            fadeIn(fade) + scaleIn(travel, initialScale = far),
            lkVaultOpenEnter(reduceMotion = false),
        )
        assertEquals(
            fadeOut(fade) + scaleOut(travel, targetScale = near),
            lkVaultOpenExit(reduceMotion = false),
        )
    }

    @Test
    fun `reduced motion leaves no travel at all`() {
        val instantIn = fadeIn(tween(0))
        val instantOut = fadeOut(tween(0))
        assertEquals(instantIn, lkVaultSealEnter(reduceMotion = true))
        assertEquals(instantOut, lkVaultSealExit(reduceMotion = true))
        assertEquals(instantIn, lkVaultOpenEnter(reduceMotion = true))
        assertEquals(instantOut, lkVaultOpenExit(reduceMotion = true))
    }
}
