package com.localkeep.feature.onboarding

import com.localkeep.core.ui.theme.LkStrength
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The meter never paints an empty bar.
 *
 * Before the visual sweep of 2026-09-01, `LkSegmentMeter` was handed the raw zxcvbn score, so a
 * score of 0 — "guessed instantly", the worst band there is — filled zero of four segments and the
 * coral warning colour never appeared at all. The sweep pixel-sampled all four bars at
 * RGB(108,109,110), which is `controlIdle` composited over the background, and a full-image scan
 * for coral returned no matching pixels. On the one field in the app that can never be reset.
 *
 * `theWorstScoreStillPaintsAWarningBar` was proven red on 2026-09-01: with [strengthMeterLevel]
 * returning `score` unchanged it fails on its first assertion, while the two control tests below
 * stay green.
 */
class StrengthMeterLevelTest {

    @Test
    fun theWorstScoreStillPaintsAWarningBar() {
        // The defect, stated as the assertion that catches it. Score 0 is the only input that ever
        // produced an empty meter.
        assertEquals(1, strengthMeterLevel(0))
        assertTrue(strengthMeterLevel(0) >= 1, "score 0 must fill at least one segment")
    }

    @Test
    fun noScoreEverPaintsAnEmptyMeter() {
        // The general form, so a future re-mapping cannot reintroduce the hole at some other value.
        // Negative inputs included: strengthScore is an Int and nothing in the type stops one.
        for (score in -3..4) {
            assertTrue(
                strengthMeterLevel(score) >= 1,
                "score $score filled ${strengthMeterLevel(score)} segments; the meter must never be empty",
            )
        }
    }

    @Test
    fun everyOtherScoreIsPassedThroughUnchanged() {
        // The control, and the reason this is a floor rather than a re-mapping. If someone "fixes"
        // this by routing through LkStrength.ofScore(), scores 3 and 4 would come back as 3 and 4
        // by luck but 1 would become 1 and 2 would become 2 via a different band table — and the
        // five-rung label ladder this screen prints would no longer match the bar. Pinning
        // pass-through is what keeps the clamp from growing into a mapping.
        assertEquals(1, strengthMeterLevel(1))
        assertEquals(2, strengthMeterLevel(2))
        assertEquals(3, strengthMeterLevel(3))
        assertEquals(4, strengthMeterLevel(4))
    }

    @Test
    fun theClampAgreesWithLkStrengthOnEveryScoreItDoesNotChange() {
        // Scores 1..4 must keep matching the shared band table, or the bar and the word disagree.
        // Score 0 is excluded deliberately: LkStrength folds it into Weak (1 segment), which is the
        // same number this clamp produces, but for a different reason — see strengthMeterLevel's
        // KDoc on why we do not simply call ofScore() here.
        for (score in 1..4) {
            assertEquals(
                LkStrength.ofScore(score).filledSegments,
                strengthMeterLevel(score),
                "score $score disagrees with LkStrength.filledSegments",
            )
        }
    }
}
