package com.localkeep.core.ui.theme

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the folder picker's second button and the Back key mean once folders are made in a run.
 *
 * Creating no longer ends the visit (2026-10-12: "What if I want to create 5 or 10 folders or 20?
 * Do I have to click every time?"), so the card is open far more often than it was. "Back closes the
 * card" and "Cancel" are right while there is a name to abandon; once something has been made and the
 * field is empty again they mean leave.
 *
 * Asserted on [folderPickerLeavesOnDismiss] rather than on a composed screen, for the reason
 * [CreateFolderInertReasonTest] gives. `LkFolderPickerKeepsNamingAfterCreateTest` drives the screen.
 */
class FolderPickerLeavesOnDismissTest {

    @Test
    fun `with no card open the way out leaves`() {
        assertTrue(folderPickerLeavesOnDismiss(naming = false, name = null, createdAny = false))
    }

    @Test
    fun `a card opened and not yet used is still cancelled`() {
        assertFalse(folderPickerLeavesOnDismiss(naming = true, name = "", createdAny = false))
    }

    @Test
    fun `a name being typed is cancelled even after earlier folders were made`() {
        assertFalse(folderPickerLeavesOnDismiss(naming = true, name = "Be", createdAny = true))
    }

    @Test
    fun `once something was made and the field is empty the way out leaves`() {
        assertTrue(folderPickerLeavesOnDismiss(naming = true, name = "", createdAny = true))
        assertTrue(folderPickerLeavesOnDismiss(naming = true, name = "   ", createdAny = true))
    }
}
