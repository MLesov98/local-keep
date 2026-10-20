package com.localkeep.feature.files

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The file editor's discard prompt says what Discard actually throws away.
 *
 * Device pass 2026-10-04 (C1 finding 4): open a saved file, Edit, change the title, Back. The prompt
 * read "Discard this file? The file you imported and its details haven't been saved to the vault
 * yet." — false for a file that has been in the vault all along, and alarming in exactly the wrong
 * direction: Discard there drops an edit, not the file. The import wording stays for a new file,
 * where it is true.
 */
class FileDiscardPromptNamesWhatIsLostTest {

    @Test
    fun `a saved file being edited is asked about its changes`() {
        assertEquals(
            R.string.files_edit_discard_changes_dialog_title to R.string.files_edit_discard_changes_dialog_body,
            fileDiscardDialogCopy(isNew = false),
            "an edit of a saved file was told its file had never been saved",
        )
    }

    /** The control: a file that was only imported really is lost by Discard. */
    @Test
    fun `a new import keeps the import wording`() {
        assertEquals(
            R.string.files_edit_discard_dialog_title to R.string.files_edit_discard_dialog_body,
            fileDiscardDialogCopy(isNew = true),
        )
    }
}
