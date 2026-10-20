package com.localkeep.feature.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The all-clear sentence may only mention the breach set when a breach scan actually ran.
 *
 * `breachedCount` is zero in two very different situations: the corpus was consulted and matched
 * nothing, and the corpus never loaded, so nothing was consulted at all. The advice line read that
 * zero as a clean result and told the user their passwords were "not in the offline breach set",
 * which no check had established.
 *
 * The second situation used to be the common one — it was every free-tier scan and every `foss`
 * build, back when breach matching was Pro-gated and `foss` shipped no corpus. Both flavours now
 * ship the corpus and neither gates it, so what remains is an asset that fails to open or parse.
 * Rarer, and the claim would be exactly as untrue.
 */
class HealthAdviceBreachClaimTest {

    private fun clean(breachDataAvailable: Boolean, passwordsChecked: Int = 4) = HealthDashboardUiState(
        scannedCount = 4,
        weakCount = 0,
        reusedCount = 0,
        oldCount = 0,
        duplicateCount = 0,
        breachedCount = 0,
        breachDataAvailable = breachDataAvailable,
        passwordsChecked = passwordsChecked,
    )

    @Test
    fun `a clean scan that consulted the corpus may say so`() {
        val state = clean(breachDataAvailable = true)
        assertEquals(R.string.health_advice_clean_breach_checked, state.adviceRes)
        assertEquals(R.string.health_footnote_breach_available_undated, state.footnoteRes)
    }

    @Test
    fun `a clean scan that never consulted the corpus claims nothing about breaches`() {
        val state = clean(breachDataAvailable = false)
        assertEquals(R.string.health_advice_clean_no_breach_data, state.adviceRes)
        // The breach-checked string is the only one of the two allowed to name the breach set.
        assertNotEquals(R.string.health_advice_clean_breach_checked, state.adviceRes)
        assertEquals(R.string.health_footnote_breach_unavailable, state.footnoteRes)
    }

    // 19-3: scannedCount > 0 and breachDataAvailable == true is not proof anything was looked up —
    // every Login here could have an empty password. Only passwordsChecked == 0 says so, and the
    // all-clear advice line and the footnote must both fall back rather than claim a check ran.
    @Test
    fun `items scanned but no password ever looked up claims nothing about breaches either`() {
        val state = clean(breachDataAvailable = true, passwordsChecked = 0)
        assertEquals(R.string.health_advice_clean_no_breach_data, state.adviceRes)
        assertNotEquals(R.string.health_advice_clean_breach_checked, state.adviceRes)
        assertEquals(R.string.health_footnote_breach_not_yet_checked, state.footnoteRes)
        assertNotEquals(R.string.health_footnote_breach_available_undated, state.footnoteRes)
    }

    @Test
    fun `a real breach hit is still reported ahead of everything else`() {
        val state = HealthDashboardUiState(
            scannedCount = 4,
            weakCount = 3,
            reusedCount = 2,
            breachedCount = 1,
            breachDataAvailable = true,
        )
        assertEquals(R.plurals.health_advice_breached, state.advicePluralRes)
    }
}
