package com.localkeep.feature.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The denominator in "N of M" is fixed for the whole run.
 *
 * Found by hand on 2026-07-29: the rail read "2 of 7" on the password step and "3 of 6" on the one
 * after it, on an emulator with no enrolled fingerprint. The count was optimistic until the vault
 * existed and truthful afterwards, on the reasoning that over-counting corrects more gracefully
 * than under-counting. It does not correct gracefully at all — a progress indicator that moves its
 * own goalposts is worse than no progress indicator, because it says the app cannot tell how long
 * this will take.
 *
 * The device answer is now asked in the ViewModel's init, so it is the same answer at every step.
 * This test walks the whole sequence for each device shape and asserts the total never moves; it
 * fails against the old predicate, which hardcoded `true` up to CREATE_PASSWORD.
 */
class StepTotalNeverShrinksTest {

    private fun totalsAlongTheFlow(possibleOnDevice: Boolean, vaultUnlocks: Boolean): List<Int> =
        OnboardingUiState.Step.entries.map { step ->
            visibleSteps(
                biometricShown = biometricStepShown(
                    step = step,
                    possibleOnDevice = possibleOnDevice,
                    // What the ViewModel writes after creation: the device could, *and* the new
                    // vault opened.
                    available = possibleOnDevice && vaultUnlocks,
                ),
                migrateShown = step <= OnboardingUiState.Step.CREATE_PASSWORD || vaultUnlocks,
            ).size
        }

    /** The case that was broken: a device that will never show the biometric screen. */
    @Test
    fun theTotalIsConstantOnADeviceWithoutBiometrics() {
        val totals = totalsAlongTheFlow(possibleOnDevice = false, vaultUnlocks = true)

        assertEquals(
            1,
            totals.distinct().size,
            "the step total changed partway through the flow: $totals",
        )
    }

    @Test
    fun theTotalIsConstantOnADeviceWithBiometrics() {
        val totals = totalsAlongTheFlow(possibleOnDevice = true, vaultUnlocks = true)

        assertEquals(OnboardingUiState.Step.entries.size, totals.first())
        assertEquals(1, totals.distinct().size, "got: $totals")
    }

    /**
     * The one path where the total legitimately narrows: creation succeeded but the unlock that
     * follows it did not, which removes both optional steps at once. Pinned rather than forbidden —
     * it is an error state the user is already being told about, and the alternative is a rail that
     * counts two screens nobody will ever see.
     */
    @Test
    fun theTotalNarrowsOnlyWhenThePostCreationUnlockFails() {
        val totals = totalsAlongTheFlow(possibleOnDevice = true, vaultUnlocks = false)

        assertEquals(OnboardingUiState.Step.entries.size, totals.first())
        assertEquals(OnboardingUiState.Step.entries.size - 2, totals.last())
        // And it narrows once, at one place — it does not oscillate.
        assertEquals(2, totals.distinct().size, "got: $totals")
    }
}
