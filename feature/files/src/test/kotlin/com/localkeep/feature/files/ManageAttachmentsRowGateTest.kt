package com.localkeep.feature.files

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A File item *is* a file. The editor's "Manage attachments" row led to a second list, with its own
 * "Attach a file" control, for adding more files to something that already holds one — reported
 * from a phone 2026-10-12 as the same job done in two places. The row now shows only where it has
 * something to manage, so a File item that already carries attachments (made before this) keeps the
 * way to them, and one that has none no longer offers a second place to add.
 */
class ManageAttachmentsRowGateTest {

    @Test
    fun `a saved file with nothing attached does not offer the row`() {
        assertFalse(manageAttachmentsRowVisible(isNew = false, attachmentCount = 0))
    }

    @Test
    fun `a saved file that already holds attachments keeps the way to them`() {
        assertTrue(manageAttachmentsRowVisible(isNew = false, attachmentCount = 1))
        assertTrue(manageAttachmentsRowVisible(isNew = false, attachmentCount = 7))
    }

    /** Control: the unsaved-item guard that was already there must survive the new rule. */
    @Test
    fun `an unsaved file never offers it, whatever the count says`() {
        assertFalse(manageAttachmentsRowVisible(isNew = true, attachmentCount = 0))
        assertFalse(manageAttachmentsRowVisible(isNew = true, attachmentCount = 3))
    }
}
