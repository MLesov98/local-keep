package com.localkeep.feature.health

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `categoryBadgeSize`'s regression coverage for the dp-box-holding-sp-text defect this screen's
 * health-score ring already shipped a fix for once (`ScoreCard`, `HealthDashboardScreen.kt`).
 *
 * A pure-arithmetic unit test rather than a Compose one, matching `ErrorRevealPinTest`'s reasoning
 * in `:core:ui`: `:feature:health` has no Robolectric on its unit-test classpath, and a font-scale
 * regression only needs the sizing formula pinned, not a real render pass.
 */
class HealthCategoryBadgeSizeTest {

    @Test
    fun `badge keeps its shipped size at the default font scale`() {
        assertEquals(38.dp, categoryBadgeSize(1f))
    }

    @Test
    fun `badge grows enough to hold a three-digit count at fontScale 2`() {
        val size = categoryBadgeSize(2f)
        // 14sp monospace at fontScale 2.0 is ~28sp; a three-character count needs roughly 42-50dp
        // to sit inside its plate without drawing past it (see the KDoc above the production fn).
        assertTrue(
            size >= 50.dp,
            "badge was only $size at fontScale 2.0 — too small for a 3-digit count, which is what " +
                "regressed before this fix (a fixed 38.dp box holding text that had grown past it)",
        )
    }

    @Test
    fun `badge scales linearly with font scale, same treatment as the score ring`() {
        assertEquals(38.dp * 1.5f, categoryBadgeSize(1.5f))
        assertEquals(38.dp * 1.75f, categoryBadgeSize(1.75f))
    }
}
