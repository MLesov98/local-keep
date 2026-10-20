package com.localkeep.feature.settings

import androidx.biometric.BiometricManager
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two Settings rows that described a feature where they should have reported a state.
 *
 * **Biometric unlock said "Unavailable on this device" for four different reasons.** The row
 * collapsed `BiometricManager.canAuthenticate` to a boolean and printed one sentence for every way
 * it can say no. That sentence is *false* for `BIOMETRIC_ERROR_NONE_ENROLLED` — the device can do
 * this perfectly well and its owner has simply not enrolled a finger yet — and that is also the
 * most common of the four on a phone somebody has just set up. So the app told a user their
 * hardware could not do something it could, and offered nothing to act on. It is the one refusal
 * with an action behind it, and it now carries the route to enrol.
 *
 * **The autofill row never said whether autofill was on.** Its summary read "Offer saved logins in
 * apps and the browser", which is a description of the feature, so a user who had never enabled it
 * and a user for whom it had worked for months read the same line. Material's own rule for a
 * settings row is that the secondary text carries state; this is the one row in the group whose
 * state lives entirely outside the app, in the system's autofill picker.
 *
 * Both are re-read on entry, because both are changed in the *system* Settings app, which rows here
 * send the user to and which never returns a result.
 */
class SystemCapabilityRowsTest {

    private val screen: String =
        File("src/main/kotlin/com/localkeep/feature/settings/SettingsScreen.kt")
            .readLines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    @Test
    fun `each way biometrics can be refused has its own sentence`() {
        assertEquals(
            R.string.settings_biometric_summary_not_enrolled,
            biometricUnavailableReason(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED),
            "nothing enrolled is not the same as unavailable, and it is the one the user can fix",
        )
        assertEquals(
            R.string.settings_biometric_summary_no_hardware,
            biometricUnavailableReason(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE),
        )
        assertEquals(
            R.string.settings_biometric_summary_sensor_busy,
            biometricUnavailableReason(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE),
        )
        assertEquals(
            R.string.settings_biometric_summary_needs_update,
            biometricUnavailableReason(BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED),
        )
    }

    @Test
    fun `only the fixable refusal offers a route to fix it`() {
        assertTrue(
            "R.string.settings_biometric_enrol_button" in screen &&
                "viewModel::openBiometricEnrollSettings" in screen,
            "the not-enrolled case must offer enrolment; without it the row states a problem and " +
                "withholds the only action that solves it",
        )
        val guard = "state.biometricUnavailableReason ==\n" +
            "                        R.string.settings_biometric_summary_not_enrolled"
        assertTrue(
            guard in screen,
            "the enrol button must be conditional on the not-enrolled reason; a phone with no " +
                "sensor would otherwise be offered a screen with nothing on it to do",
        )
    }

    @Test
    fun `the autofill row reports whether autofill is actually on`() {
        assertTrue(
            "state.autofillEnabled" in screen &&
                "R.string.settings_autofill_service_summary_on" in screen &&
                "R.string.settings_autofill_service_summary_off" in screen,
            "the row's summary must carry the state, not a description of the feature",
        )
        assertTrue(
            "viewModel.refreshSystemCapabilities()" in screen,
            "both values are changed in the system Settings app, which returns no result, so they " +
                "must be re-read on entry or the row reports what was true on the way out",
        )
    }

    /**
     * The control, and it must stay green while the tests above go red.
     *
     * The available cases are untouched. `biometricUnavailableReason` returns null exactly when the
     * platform says BIOMETRIC_SUCCESS, which is what lets the row's `when` fall through to its
     * on/off summaries unchanged — and a mapping that returned a string there would replace a true
     * "On for this device" with a reason for an unavailability that is not happening.
     *
     * The second half matters as much: an unrecognised code must NOT be given an invented
     * explanation. `BIOMETRIC_STATUS_UNKNOWN` is the platform saying it does not know, and guessing
     * on its behalf is the exact failure this change exists to undo, one level further out.
     */
    @Test
    fun `an available sensor has no reason, and an unknown verdict is not invented`() {
        assertNull(
            biometricUnavailableReason(BiometricManager.BIOMETRIC_SUCCESS),
            "a working sensor must leave the row's on/off summaries alone",
        )
        assertEquals(
            R.string.settings_biometric_summary_unavailable,
            biometricUnavailableReason(BiometricManager.BIOMETRIC_STATUS_UNKNOWN),
            "the platform said it does not know; the generic line is the honest answer",
        )
    }
}
