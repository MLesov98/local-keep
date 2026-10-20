package com.localkeep.feature.health

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `ScanningCard`'s scan-progress percentage used to be `"${floor(progress * 100f).toInt()}%"` —
 * a hardcoded concatenation that ignores locale, the same defect `LkDerivingOverlay`'s KDF ring
 * had. Both now call the shared `com.localkeep.core.ui.theme.lkFlooredPercent(progress, locale)`
 * helper, whose floor/locale behaviour is unit-tested directly in `:core:ui`
 * (`LkDerivingPercentFormatTest`, which needs Robolectric for `android.icu`).
 *
 * `:feature:health` has no Robolectric on its unit-test classpath (see
 * `HealthCategoryBadgeSizeTest`'s KDoc), and `ScanningCard` is private, so this is a source scan
 * — same idiom as `:core:ui`'s `LkIndicatorsCopyTest` — pinning that the call site was actually
 * rewired rather than just proving the shared helper works in isolation.
 */
class HealthDashboardScanPercentFormatTest {

    private val source: String = File("src/main/kotlin/com/localkeep/feature/health/HealthDashboardScreen.kt")
        .readText()

    @Test
    fun scanningCardCallsTheSharedFlooredPercentHelper() {
        assertTrue(
            "lkFlooredPercent(progress, locale)" in source,
            "ScanningCard no longer calls the shared, locale-aware lkFlooredPercent helper",
        )
    }

    @Test
    fun scanningCardNoLongerConcatenatesThePercentageByHand() {
        assertFalse(
            Regex("""floor\(progress \* 100f\)\.toInt\(\)""").containsMatchIn(source),
            "ScanningCard reverted to the hardcoded, locale-ignorant \"\${floor(progress * 100f).toInt()}%\" concatenation",
        )
    }
}
