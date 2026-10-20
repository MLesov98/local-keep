package com.localkeep.feature.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingBackNavigationTest {

    private fun back(
        step: OnboardingUiState.Step,
        biometricStepShown: Boolean = true,
        migrateStepShown: Boolean = true,
    ) = previousStep(step, biometricStepShown, migrateStepShown)

    @Test
    fun `welcome has nowhere to go back to`() {
        assertNull(back(OnboardingUiState.Step.WELCOME))
    }

    @Test
    fun `create password returns to welcome`() {
        assertEquals(
            OnboardingUiState.Step.WELCOME,
            back(OnboardingUiState.Step.CREATE_PASSWORD),
        )
    }

    @Test
    fun `the first step after vault creation swallows back`() {
        assertNull(back(OnboardingUiState.Step.ENABLE_BIOMETRIC))
        assertNull(back(OnboardingUiState.Step.RECOVERY_KIT, biometricStepShown = false))
    }

    @Test
    fun `recovery kit returns to the biometric step only when it was shown`() {
        assertEquals(
            OnboardingUiState.Step.ENABLE_BIOMETRIC,
            back(OnboardingUiState.Step.RECOVERY_KIT, biometricStepShown = true),
        )
    }

    @Test
    fun `done skips the migration step when it was never offered`() {
        // Was ENABLE_SERVICES, which sat between MIGRATE and DONE until spec §3.2's six-screen
        // flow was restored and the autofill offer moved onto DONE itself. DONE inherits the
        // skip: the step before it is MIGRATE when that was offered and CLOUD_BACKUP when not.
        assertEquals(
            OnboardingUiState.Step.MIGRATE,
            back(OnboardingUiState.Step.DONE, migrateStepShown = true),
        )
        assertEquals(
            OnboardingUiState.Step.CLOUD_BACKUP,
            back(OnboardingUiState.Step.DONE, migrateStepShown = false),
        )
    }

    @Test
    fun `the later steps walk the ceremony backwards`() {
        assertEquals(
            OnboardingUiState.Step.RECOVERY_KIT,
            back(OnboardingUiState.Step.CLOUD_BACKUP),
        )
        assertEquals(
            OnboardingUiState.Step.CLOUD_BACKUP,
            back(OnboardingUiState.Step.MIGRATE),
        )
        assertEquals(
            OnboardingUiState.Step.MIGRATE,
            back(OnboardingUiState.Step.DONE),
        )
    }

    /**
     * The cloud-backup step is never skipped, unlike the biometric and migration steps either side
     * of it. Whether an encrypted copy of the vault leaves the device is not a question this app
     * gets to answer on the user's behalf by omitting the screen (design.md §8.2), so back always
     * finds it and forward always shows it.
     */
    @Test
    fun `the cloud backup step is always in the path`() {
        for (biometric in listOf(true, false)) {
            for (migrate in listOf(true, false)) {
                assertEquals(
                    OnboardingUiState.Step.RECOVERY_KIT,
                    back(OnboardingUiState.Step.CLOUD_BACKUP, biometric, migrate),
                )
            }
        }
    }

    @Test
    fun `back never re-enters password entry once the vault exists`() {
        val postCreation = OnboardingUiState.Step.entries.filter {
            it > OnboardingUiState.Step.CREATE_PASSWORD
        }
        for (step in postCreation) {
            for (biometric in listOf(true, false)) {
                for (migrate in listOf(true, false)) {
                    val target = back(step, biometric, migrate)
                    assertTrue(
                        target == null || target > OnboardingUiState.Step.CREATE_PASSWORD,
                        "back from $step reached $target",
                    )
                }
            }
        }
    }
}
