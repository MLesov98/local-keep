package com.localkeep.feature.onboarding

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Recovery Kit gate offers the same two doors the invisible-items gate does.
 *
 * Measured on device 2026-09-20 (release sweep, 2A-F17): with a fingerprint enrolled, the
 * invisible-items gate showed a "Use biometrics" button and the Recovery Kit gate did not — two
 * rows apart in the same drawer, two answers to the same question. This aligns the two.
 *
 * The other half of the property is that the alignment did not *widen* the credential: this prompt
 * is `BIOMETRIC_STRONG` only. `RevealAuth` permits `DEVICE_CREDENTIAL` beside the biometric, and a
 * four-digit device PIN is the secret a coercer is most likely to have watched being typed — which
 * is the situation the vault's recovery words exist for. So the button is asserted here and the
 * authenticator set is asserted with it; one without the other is not the fix.
 *
 * Asserted on the source for the reason [RecoveryKitAuthGateTest] gives: a `BiometricPrompt` cannot
 * run under Robolectric, and a composable gate is a branch, not a value.
 */
class RecoveryKitGateOffersBiometricTest {

    /**
     * The ViewModel with its comments removed. Load-bearing, not tidiness: the KDoc below the
     * prompt explains at length why `DEVICE_CREDENTIAL` is *not* allowed, and a test searching the
     * raw text for that word would read its own rationale as the defect.
     */
    private val viewModelSource =
        File("src/main/kotlin/com/localkeep/feature/onboarding/RecoveryKitViewModel.kt")
            .readLines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

    /** The screen with its comments removed, so prose about a control cannot satisfy a search for it. */
    private val screenSource =
        File("src/main/kotlin/com/localkeep/feature/onboarding/RecoveryKitScreen.kt")
            .readLines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

    /** The gate composable alone: from its declaration to the end of the file. */
    private val gate: String = screenSource.substringAfter("private fun RecoveryKitGate(")

    @Test
    fun `the gate slice is bounded`() {
        assertTrue(
            gate.isNotEmpty() && gate.length < screenSource.length,
            "the gate slice is unbounded, so every assertion here is measuring the whole screen " +
                "and cannot fail; length=${gate.length} of ${screenSource.length}",
        )
        assertTrue(
            gate.contains("onboarding_recoverykit_gate_password_label"),
            "the slice does not contain the gate it is named for, so it is not the region this " +
                "test believes it is reading",
        )
    }

    @Test
    fun `a fresh state does not claim a biometric this device may not have`() {
        assertFalse(
            RecoveryKitUiState().biometricAvailable,
            "the default must fail towards no biometric door, so a state built before the " +
                "capability was read never offers one",
        )
    }

    @Test
    fun `the gate renders a biometric door`() {
        assertTrue(
            gate.contains("onboarding_recoverykit_gate_biometric"),
            "the gate offers no biometric button, so a user with a fingerprint enrolled meets a " +
                "password-only gate here and a two-door gate on the invisible-items screen. " +
                "Gate: $gate",
        )
        assertTrue(
            gate.contains("biometricAvailable"),
            "the biometric door is rendered unconditionally: a device with no strong sensor would " +
                "be offered a button that cannot raise a prompt. Gate: $gate",
        )
    }

    @Test
    fun `the biometric door is strong-only, never the device credential`() {
        assertTrue(
            viewModelSource.contains("setAllowedAuthenticators(BIOMETRIC_STRONG)"),
            "the prompt must allow BIOMETRIC_STRONG alone — the device PIN is not a door onto " +
                "this vault's recovery words",
        )
        assertFalse(
            viewModelSource.contains("DEVICE_CREDENTIAL"),
            "the gate must not accept the device screen-lock credential",
        )
        assertFalse(
            viewModelSource.contains("RevealAuth"),
            "RevealAuth permits DEVICE_CREDENTIAL alongside the biometric and must not gate this",
        )
    }

    /**
     * The epoch rule the knowledge factor already obeys. A `BiometricPrompt` is a separate system
     * window that can sit open far longer than the Aggressive preset's background grace, so a
     * success delivered after the vault locked must open nothing.
     */
    @Test
    fun `a biometric success from a dead session grants nothing`() {
        val body = viewModelSource
            .substringAfter("internal fun applyBiometricOutcome(")
            .substringBefore("}")
        assertTrue(
            body.contains("generation != authGeneration"),
            "a biometric success must be checked against the generation it started in. Found: $body",
        )
    }
}
