package com.localkeep.feature.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Regression for 3-1: the viewer's confirmation dialog was hard-coded to the tier-A
 * permanent-removal copy for all three mounts. The standalone viewer's action is
 * `FileItemViewerViewModel.deletePage` → `VaultRepository.moveToTrash`, which only sets `deletedAt`
 * and leaves the blob referenced until a purge — so "There is no undo here, [the copy is] erased
 * from this device the next time you unlock" was false for that mount, printed beside a button the
 * same screen had already labelled "Trash".
 */
class ViewerDeleteCopyTest {

    @Test
    fun `the trash mount gets recoverable copy, not the permanent-removal dialog`() {
        val copy = viewerDeleteCopy(R.string.files_viewer_trash)

        assertEquals(R.string.files_viewer_trash_dialog_title, copy.title)
        assertEquals(R.string.files_viewer_trash_dialog_body, copy.body)
        assertEquals(R.string.files_viewer_trash_dialog_action, copy.confirm)
    }

    @Test
    fun `the trash mount never states the permanent-removal claim`() {
        // Named separately from the assertion above because this is the actual harm: whatever the
        // trash copy says, it must not be the string asserting the file is erased and unrecoverable.
        val copy = viewerDeleteCopy(R.string.files_viewer_trash)

        assertNotEquals(R.string.files_attachments_remove_dialog_body, copy.body)
        assertNotEquals(R.string.files_attachments_remove_dialog_title, copy.title)
        assertNotEquals(R.string.files_remove, copy.confirm)
    }

    @Test
    fun `a mount that does not name trash keeps the permanent-removal copy`() {
        // The control. Without it, "always return the trash copy" passes both tests above and would
        // tell the attachment picker — whose remove really is permanent — that its delete is
        // recoverable, which is the same defect pointed the dangerous way round.
        val copy = viewerDeleteCopy(R.string.files_viewer_delete)

        assertEquals(R.string.files_attachments_remove_dialog_title, copy.title)
        assertEquals(R.string.files_attachments_remove_dialog_body, copy.body)
        assertEquals(R.string.files_remove, copy.confirm)
    }
}
