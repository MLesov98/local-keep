package com.localkeep.feature.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The breach advice line follows the number of flagged passwords.
 *
 * It was one fixed sentence, "Start with the flagged password", singular, printed above a card
 * that said fifteen were flagged. The line is chosen by `breachedCount`, so it is a counted
 * string: the state exposes a `<plurals>` id plus the count to resolve it with, and every other
 * advice line stays a plain string.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthBreachAdviceIsCountedTest {

    private fun breached(count: Int) = HealthDashboardUiState(
        scannedCount = 40,
        weakCount = 3,
        reusedCount = 2,
        breachedCount = count,
        breachDataAvailable = true,
        passwordsChecked = 40,
    )

    @Test
    fun `a breach hit selects the counted advice and no plain string`() {
        val state = breached(15)
        assertEquals(R.plurals.health_advice_breached, state.advicePluralRes)
        assertNull(state.adviceRes)
    }

    @Test
    fun `control - without a breach hit the advice is still the plain string`() {
        val state = breached(0)
        assertNull(state.advicePluralRes)
        assertEquals(R.string.health_advice_reused, state.adviceRes)
    }

    private fun lines(): Pair<String, String> {
        val res = RuntimeEnvironment.getApplication().resources
        return res.getQuantityString(R.plurals.health_advice_breached, 1, 1) to
            res.getQuantityString(R.plurals.health_advice_breached, 15, 15)
    }

    private fun assertCounted(offline: String) {
        val (one, many) = lines()
        assertNotEquals(one, many, "one flagged password and fifteen read the same sentence")
        assertNotNull(many)
        // The Tier G scope word survives in both forms: an offline app cannot speak for every breach.
        assertTrue(offline in one && offline in many, "scope word '$offline' missing: $one | $many")
    }

    @Test
    @Config(qualifiers = "en")
    fun `en counted`() {
        assertCounted("offline")
        assertEquals("15 passwords matched the offline breach set", lines().second.substringBefore(" —"))
    }

    @Test
    @Config(qualifiers = "fr")
    fun `fr counted`() = assertCounted("hors ligne")

    @Test
    @Config(qualifiers = "de")
    fun `de counted`() = assertCounted("Offline")

    @Test
    @Config(qualifiers = "es")
    fun `es counted`() = assertCounted("sin conexión")

    @Test
    @Config(qualifiers = "pt")
    fun `pt counted`() = assertCounted("offline")
}
