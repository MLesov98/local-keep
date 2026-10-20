package com.localkeep.feature.vaultlist

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for the 2026-09-09 functional sweep finding: restoring an item out of the trash goes
 * back to its folder — or, when that folder is itself gone, to the root, a real and recurring
 * outcome since the fix that clears a dead `folderId` on restore — and the confirmation said
 * neither, so the user went looking for the item in a folder it was never filed back into.
 *
 * `VaultRepository.restoreFromTrash` reports nothing about where it put the item (unlike
 * `RestoreFolderOutcome` for `restoreFolder`), so [TrashViewModel.restore] reads the outcome back
 * by comparing the item's `folderId` immediately before and after the write, and hands that pair to
 * [restoreItemNotice] — the function this test pins directly, with no ViewModel or repository
 * needed, the same boundary [BulkTrashUndoTest] draws around `changedIds`.
 */
class RestoreItemDestinationNoticeTest {

    private val liveFolder = UUID.randomUUID()
    private val ancestorFolder = UUID.randomUUID()
    private val confirmation = "Item restored"
    private val confirmationAt: (String) -> String = { name -> "Item restored to $name" }
    private val confirmationAtRoot = "Item restored to the top level"

    @Test
    fun `unchanged folder gets the plain confirmation`() {
        val text = restoreItemNotice(
            originalFolderId = liveFolder,
            destinationFolderId = liveFolder,
            confirmation = confirmation,
            confirmationAt = confirmationAt,
            confirmationAtRoot = confirmationAtRoot,
            folderName = { fail("must not resolve a name for an unchanged destination") },
        )
        assertEquals(confirmation, text)
    }

    @Test
    fun `already unfiled and still unfiled gets the plain confirmation`() {
        val text = restoreItemNotice(
            originalFolderId = null,
            destinationFolderId = null,
            confirmation = confirmation,
            confirmationAt = confirmationAt,
            confirmationAtRoot = confirmationAtRoot,
            folderName = { fail("must not resolve a name for an unchanged destination") },
        )
        assertEquals(confirmation, text)
    }

    // FAILS WITHOUT THE FIX: before restoreItemNotice existed, TrashViewModel.restore posted a
    // caller-supplied fixed string no matter what restoreFromTrash actually did — this is exactly
    // the case that string was silent about, an item whose original folder is itself gone.
    @Test
    fun `folder gone with no live ancestor reports the root`() {
        val text = restoreItemNotice(
            originalFolderId = liveFolder,
            destinationFolderId = null,
            confirmation = confirmation,
            confirmationAt = confirmationAt,
            confirmationAtRoot = confirmationAtRoot,
            folderName = { fail("must not resolve a name for a root destination") },
        )
        assertEquals(confirmationAtRoot, text)
    }

    @Test
    fun `folder gone but a live ancestor exists names it`() {
        val text = restoreItemNotice(
            originalFolderId = liveFolder,
            destinationFolderId = ancestorFolder,
            confirmation = confirmation,
            confirmationAt = confirmationAt,
            confirmationAtRoot = confirmationAtRoot,
            folderName = { id -> if (id == ancestorFolder) "Work" else null },
        )
        assertEquals("Item restored to Work", text)
    }

    @Test
    fun `a destination that cannot be named falls back to the root notice`() {
        val text = restoreItemNotice(
            originalFolderId = liveFolder,
            destinationFolderId = ancestorFolder,
            confirmation = confirmation,
            confirmationAt = confirmationAt,
            confirmationAtRoot = confirmationAtRoot,
            folderName = { null },
        )
        assertEquals(confirmationAtRoot, text)
    }

    private fun fail(message: String): Nothing = kotlin.test.fail(message)
}
