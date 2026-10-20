package com.localkeep.feature.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The step rail counts only the steps this run will actually show.
 *
 * The biometric half of this was fixed when someone noticed the rail saying "3 of 7" on a device
 * that would only ever show four screens. The migration half was left behind, and it fails the
 * same way: `chooseCloudBackup` sends the user straight to `DONE` when the post-creation unlock
 * did not take, so `MIGRATE` never renders and every screen after it reported the user one step
 * further from the end than they were.
 */
class VisibleStepsTest {

    @Test
    fun countsEveryStepWhenBothOptionalOnesWillShow() {
        val steps = visibleSteps(biometricShown = true, migrateShown = true)

        assertEquals(OnboardingUiState.Step.entries.size, steps.size)
    }

    @Test
    fun dropsTheBiometricStepOnADeviceThatCannotOfferIt() {
        val steps = visibleSteps(biometricShown = false, migrateShown = true)

        assertFalse(OnboardingUiState.Step.ENABLE_BIOMETRIC in steps)
        assertTrue(OnboardingUiState.Step.MIGRATE in steps)
    }

    /** The case that was broken: no open vault, so the migration wizard is never offered. */
    @Test
    fun dropsTheMigrationStepWhenTheVaultDidNotOpen() {
        val steps = visibleSteps(biometricShown = true, migrateShown = false)

        assertFalse(
            OnboardingUiState.Step.MIGRATE in steps,
            "chooseCloudBackup skips MIGRATE when the vault is not unlocked, so the rail must " +
                "not count it: $steps",
        )
    }

    /**
     * Both skipped at once is the real shape of the failure path, not a hypothetical: the same
     * `vaultUnlocked` that gates the migration step is also a term in the biometric predicate, so
     * an unlock that does not take removes both.
     */
    @Test
    fun dropsBothWhenTheUnlockDidNotTake() {
        val steps = visibleSteps(biometricShown = false, migrateShown = false)

        assertEquals(OnboardingUiState.Step.entries.size - 2, steps.size, "got: $steps")
        assertTrue(OnboardingUiState.Step.DONE in steps, "DONE always renders: $steps")
        assertTrue(OnboardingUiState.Step.RECOVERY_KIT in steps, "the kit is mandatory: $steps")
    }

    /** Filtering must not reorder: the rail derives the user's position from indexOf. */
    @Test
    fun keepsDeclarationOrder() {
        val steps = visibleSteps(biometricShown = true, migrateShown = true)

        assertEquals(OnboardingUiState.Step.entries.toList(), steps)
    }
}
