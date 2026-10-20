package com.localkeep.core.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The four strength bands, pinned in both directions.
 *
 * These exist because the design comp got the typed-password half wrong, and the wrong version is
 * the one that reads as reasonable: it scored a typed password as `length × log2(alphabet)`, which
 * reports sixteen repeated characters as Strong. Entropy is a property of how a value was
 * *generated*; it says nothing about a value someone typed. The two entry points below are
 * deliberately different functions for that reason, and this file is what stops them being
 * "simplified" into one.
 */
class LkStrengthTest {

    @Test
    fun entropyBandsMatchTheGeneratorsShippedThresholds() {
        // 60 / 90 / 128, unchanged from the generator's own boundaries, which took them from the
        // vault-health analyser. Boundary values belong to the band above.
        assertEquals(LkStrength.Weak, LkStrength.ofBits(0))
        assertEquals(LkStrength.Weak, LkStrength.ofBits(59))
        assertEquals(LkStrength.Reasonable, LkStrength.ofBits(60))
        assertEquals(LkStrength.Reasonable, LkStrength.ofBits(89))
        assertEquals(LkStrength.Strong, LkStrength.ofBits(90))
        assertEquals(LkStrength.Strong, LkStrength.ofBits(127))
        assertEquals(LkStrength.Excellent, LkStrength.ofBits(128))
        assertEquals(LkStrength.Excellent, LkStrength.ofBits(400))
    }

    @Test
    fun zxcvbnsFiveScoresFoldOntoFourBandsAtTheBottom() {
        // 0 "guessed instantly" and 1 "guessed in seconds" both land in Weak. Nothing separates
        // them that a user should act on differently, and folding at the top instead would put
        // "safely unguessable" and "very unguessable" in one band — which is where the difference
        // actually stops mattering, but also where the user has already done the right thing.
        assertEquals(LkStrength.Weak, LkStrength.ofScore(0))
        assertEquals(LkStrength.Weak, LkStrength.ofScore(1))
        assertEquals(LkStrength.Reasonable, LkStrength.ofScore(2))
        assertEquals(LkStrength.Strong, LkStrength.ofScore(3))
        assertEquals(LkStrength.Excellent, LkStrength.ofScore(4))
    }

    @Test
    fun segmentCountRisesWithTheBandSoTheBarAndTheWordCannotDisagree() {
        // The meter draws `filledSegments` and prints `label`. If these ever diverge, a bar showing
        // three of four beside the word "Weak" is a mixed signal in the one control whose whole job
        // is to give an unmixed one.
        assertEquals(listOf(1, 2, 3, 4), LkStrength.entries.map { it.filledSegments })
    }

    @Test
    fun everyBandHasItsOwnWord() {
        // Four distinct string resources. Two bands sharing a label would make the meter's colour
        // the only thing separating them, which is exactly what the word exists to prevent.
        assertEquals(4, LkStrength.entries.map { it.label }.toSet().size)
    }
}
