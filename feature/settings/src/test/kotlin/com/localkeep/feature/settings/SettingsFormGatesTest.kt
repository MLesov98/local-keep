package com.localkeep.feature.settings

import com.localkeep.core.health.StrengthAnalyzer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The terms the change-password and duress forms are submitted on.
 *
 * Wave 3 of the functional pass (S10-F-08/09/10, D1-F-01): the strength meter was drawn in jade
 * "Excellent" directly under the coral too-short line it contradicted; the zxcvbn floor was checked
 * only after submit, by which point the tap had spent the current-password well; and the duress
 * form accepted an eight-character password, wiped itself through the gate, and only then refused.
 * Every one of those is a rule the form can state while it can still be acted on.
 */
class SettingsFormGatesTest {

    private val floor = StrengthAnalyzer.MIN_MASTER_PASSWORD_SCORE
    private val twelve = "a".repeat(MIN_MASTER_PASSWORD_LENGTH)

    private fun ready(newPassword: String = twelve, score: Int = floor, working: Boolean = false) =
        changePasswordReady(
            isWorking = working,
            credentialGiven = true,
            newPassword = newPassword,
            newPasswordScore = score,
            confirmPassword = newPassword,
        )

    @Test
    fun `a replacement below the strength floor does not enable submit`() {
        assertFalse(
            ready(score = floor - 1),
            "submit was live for a password the ViewModel refuses after wiping the current-password well",
        )
    }

    @Test
    fun `control - a replacement at the floor does enable submit`() {
        assertTrue(ready(score = floor))
        assertFalse(ready(score = floor, working = true), "an operation in flight still disables it")
        assertFalse(ready(newPassword = twelve.dropLast(1), score = floor), "too short still disables it")
    }

    @Test
    fun `the strength meter is not drawn under a too-short replacement`() {
        assertFalse(
            showsStrengthMeter(twelve.dropLast(1)),
            "a jade verdict was drawn directly under the coral too-short line it contradicts",
        )
    }

    @Test
    fun `control - the meter is drawn for a long enough replacement and not for an empty well`() {
        assertTrue(showsStrengthMeter(twelve))
        assertFalse(showsStrengthMeter(""))
    }

    @Test
    fun `a duress password below the length floor is called too short and cannot be submitted`() {
        val short = "a".repeat(MIN_MASTER_PASSWORD_LENGTH - 1)
        assertTrue(duressPasswordTooShort(short), "no inline too-short verdict for an 11-character duress password")
        assertFalse(
            duressSetupReady(isWorking = false, password = short, confirmPassword = short),
            "Setup was live for a password the ViewModel refuses after the gate has wiped the form",
        )
    }

    @Test
    fun `control - a long enough matching duress password can be submitted, an empty or mismatched one cannot`() {
        val ok = "a".repeat(MIN_MASTER_PASSWORD_LENGTH)
        assertFalse(duressPasswordTooShort(ok))
        assertFalse(duressPasswordTooShort(""), "an untouched well is not an error")
        assertTrue(duressSetupReady(isWorking = false, password = ok, confirmPassword = ok))
        assertFalse(duressSetupReady(isWorking = true, password = ok, confirmPassword = ok))
        assertFalse(duressSetupReady(isWorking = false, password = ok, confirmPassword = ok + "x"))
        assertEquals(false, duressSetupReady(isWorking = false, password = "", confirmPassword = ""))
    }
}
