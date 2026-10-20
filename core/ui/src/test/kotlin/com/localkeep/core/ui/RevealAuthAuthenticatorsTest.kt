package com.localkeep.core.ui

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The reveal gate has to ask for an authenticator combination the running API actually supports.
 *
 * `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` is rejected by androidx.biometric on API 28 and 29 —
 * `AuthenticatorUtils.isSupportedCombination` answers false before the device is ever consulted, so
 * `canAuthenticate` returns `BIOMETRIC_ERROR_UNSUPPORTED` and `isAvailable` says "this phone cannot
 * challenge you" on a phone with both a fingerprint and a screen lock. `minSdk` is 28, so that is
 * two supported releases where every reveal- and copy-gated secret is a dead end.
 *
 * The control matters as much as the assertion: dropping to `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`
 * is the other combination 28/29 accepts, and it would admit a Class 2 biometric where this gate
 * demands Class 3. The fix must stay exactly as strict, so the strictness control below guards
 * against buying API 28 support with a weaker guarantee.
 *
 * Found 2026-09-06.
 */
@RunWith(RobolectricTestRunner::class)
class RevealAuthAuthenticatorsTest {

    @Test
    @Config(sdk = [28])
    fun `API 28 asks for a combination the biometric library accepts`() {
        assertPromptIsBuildable()
    }

    @Test
    @Config(sdk = [29])
    fun `API 29 asks for a combination the biometric library accepts`() {
        assertPromptIsBuildable()
    }

    @Test
    @Config(sdk = [30])
    fun `API 30 asks for a combination the biometric library accepts`() {
        assertPromptIsBuildable()
    }

    @Test
    @Config(sdk = [34])
    fun `API 34 asks for a combination the biometric library accepts`() {
        assertPromptIsBuildable()
    }

    /**
     * The strictness control. Whatever the branch chooses, Class 3 stays mandatory and Class 2 is
     * never admitted — otherwise this became a relaxation of the gate rather than a fix for it.
     */
    @Test
    @Config(sdk = [28])
    fun `the API 28 fallback keeps Class 3 and never admits a weak biometric`() {
        val allowed = RevealAuth.allowedAuthenticators()
        assertEquals(BIOMETRIC_STRONG, allowed and BIOMETRIC_STRONG, "Class 3 must stay required")
        assertEquals(0, allowed and BIOMETRIC_WEAK and BIOMETRIC_STRONG.inv(), "Class 2 must not be admitted")
    }

    /** API 30 and up keep the device-credential fallback the KDoc promises. */
    @Test
    @Config(sdk = [30])
    fun `the device credential fallback survives where the platform supports it`() {
        val allowed = RevealAuth.allowedAuthenticators()
        assertEquals(DEVICE_CREDENTIAL, allowed and DEVICE_CREDENTIAL)
        assertEquals(BIOMETRIC_STRONG, allowed and BIOMETRIC_STRONG)
    }

    /**
     * The premise, asserted rather than assumed: 32783 really is rejected on API 28, so a green
     * result above is the library agreeing and not the check having quietly stopped running.
     */
    @Test
    @Config(sdk = [28])
    fun `the old constant is genuinely rejected on API 28`() {
        val thrown = runCatching {
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("t")
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                .build()
        }.exceptionOrNull()
        assertTrue(
            thrown is IllegalArgumentException,
            "expected androidx.biometric to reject STRONG or DEVICE_CREDENTIAL on API 28, got $thrown",
        )
    }

    /**
     * `promptInfo` is what `authenticate` hands to `BiometricPrompt`, and its `build()` runs the
     * same support check `canAuthenticate` runs — so one call covers both halves of the gate and
     * they cannot drift apart.
     */
    private fun assertPromptIsBuildable() {
        val info = RevealAuth.promptInfo("Confirm it's you", "Reveal the card's security code", "Cancel")
        assertEquals(RevealAuth.allowedAuthenticators(), info.allowedAuthenticators)
    }
}
