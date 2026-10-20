package com.localkeep.core.ui.theme

import com.localkeep.core.ui.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The folder picker's disabled "Create folder" button names the reason it is disabled.
 *
 * Device pass 2026-10-04 (item editor › Change folder › New folder): typing a name a sibling
 * already had disabled the button, and TalkBack read it as "Create folder, type a name first" —
 * over a field that held a name. The hint under the field said the right thing; the button
 * contradicted it.
 *
 * Asserted on [createFolderInertReason] rather than on a composed screen, because
 * [LkFolderPickerScreen] never reaches idle under Robolectric (see [LkFolderPickerChevronA11yTest]).
 * The button reads its content description from this function and from nothing else.
 */
class CreateFolderInertReasonTest {

    @Test
    fun `a name a sibling already has is the reason, not a missing name`() {
        assertEquals(
            R.string.core_ui_folder_create_name_taken_a11y,
            createFolderInertReason("Receipts", LkFolderNameStatus.TakenHere),
            "a duplicate name was announced as 'type a name first'",
        )
    }

    @Test
    fun `an empty or blank name still asks for one`() {
        assertEquals(
            R.string.core_ui_folder_create_needs_name_a11y,
            createFolderInertReason("", LkFolderNameStatus.Free),
        )
        assertEquals(
            R.string.core_ui_folder_create_needs_name_a11y,
            createFolderInertReason("   ", LkFolderNameStatus.Free),
        )
    }

    /** The control: a button that can be pressed carries no excuse at all. */
    @Test
    fun `a usable name leaves the button its own label`() {
        assertNull(createFolderInertReason("Receipts", LkFolderNameStatus.Free))
        assertNull(createFolderInertReason("Receipts", LkFolderNameStatus.TakenElsewhere))
    }
}
