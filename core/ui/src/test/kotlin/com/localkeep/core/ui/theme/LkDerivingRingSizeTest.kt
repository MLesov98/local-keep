package com.localkeep.core.ui.theme

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `derivingRingSize`'s regression coverage for the KDF overlay ring — the third shipped instance
 * of a dp-sized round container holding sp-sized text, after `ScoreCard`'s health-score ring and
 * `LkStrengthMeter`'s bar.
 *
 * Pure-arithmetic unit test, not a Compose one, following `ErrorRevealPinTest`'s precedent: the
 * defect and its fix are both in the sizing formula, so pinning the formula is the whole test. This
 * also keeps the test runnable under `testFossDebugUnitTest` — no device, no `FontScaleTest`-style
 * `createComposeRule`, which would need `connectedAndroidTest` and the device this sweep is not
 * touching mid-session.
 */
class LkDerivingRingSizeTest {

    @Test
    fun `ring keeps its shipped size at the default font scale`() {
        assertEquals(172.dp, derivingRingSize(1f))
    }

    @Test
    fun `ring grows with font scale below the cap`() {
        assertEquals(172.dp * 1.1f, derivingRingSize(1.1f))
        assertEquals(172.dp * 1.2f, derivingRingSize(1.2f))
    }

    @Test
    fun `ring stops growing before it reaches the overlay's own padding`() {
        // 308dp is the narrowest window this app supports (see LkText.stackAboveFontScale's
        // KDoc); LkDerivingOverlay pads its Column 44dp on each side, leaving 220dp of content
        // width for the ring to sit inside without touching its own padding.
        val atMax = derivingRingSize(2f)
        assertTrue(
            atMax < 220.dp,
            "ring was $atMax at fontScale 2.0 — that does not fit the 220dp of content width this " +
                "overlay has on the narrowest window it supports",
        )
    }

    @Test
    fun `the cap holds rather than reversing past its own threshold`() {
        assertEquals(172.dp * 1.25f, derivingRingSize(1.25f))
        // Past the cap, growth stops — it does not shrink back down.
        assertEquals(derivingRingSize(1.25f), derivingRingSize(2f))
    }
}
