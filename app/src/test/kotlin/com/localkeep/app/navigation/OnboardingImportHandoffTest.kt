package com.localkeep.app.navigation

import com.localkeep.feature.onboarding.OnboardingUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** S1-F-11: the import wizard opened from onboarding's Done step sits on top of that step. */
class OnboardingImportHandoffTest {

    @Test
    fun `opening the import wizard keeps onboarding on the stack`() {
        assertFalse(onboardingLeavesStackOnFinish(OnboardingUiState.Finish.OPEN_IMPORT))
    }

    @Test
    fun `every other finish still leaves onboarding`() {
        assertTrue(onboardingLeavesStackOnFinish(OnboardingUiState.Finish.OPEN_VAULT))
        assertTrue(onboardingLeavesStackOnFinish(OnboardingUiState.Finish.NEEDS_UNLOCK))
    }

    /** Finishing the wizard must not strand the Done step under the vault list. */
    @Test
    fun `a finished import clears onboarding when it is underneath`() {
        assertEquals(AppNavGraph.ONBOARDING, importLandingPopTarget(onboardingOnStack = true))
        assertEquals(AppNavGraph.HOME, importLandingPopTarget(onboardingOnStack = false))
    }
}
