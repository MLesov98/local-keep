package com.localkeep.feature.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Back from the import wizard, opened from onboarding's Done step, returns to that Done step
 * (S1-F-11). Onboarding is no longer popped for the wizard, so the Done step must be live again:
 * a `finish` left at OPEN_IMPORT never changes, and the primary button would do nothing.
 */
class ImportHandoffReturnsToDoneTest {

    private val doneWithImport = OnboardingUiState(
        step = OnboardingUiState.Step.DONE,
        migrateRequested = true,
        finish = OnboardingUiState.Finish.OPEN_IMPORT,
    )

    @Test
    fun `the done step is live again after the wizard is opened`() {
        val back = doneWithImport.afterImportHandoff()
        assertNull(back.finish, "finish must be cleared or the primary button can never fire again")
        assertEquals(OnboardingUiState.Step.DONE, back.step)
    }

    @Test
    fun `the primary offers the vault after the user backs out of the wizard`() {
        assertTrue(!doneWithImport.afterImportHandoff().migrateRequested)
    }
}
