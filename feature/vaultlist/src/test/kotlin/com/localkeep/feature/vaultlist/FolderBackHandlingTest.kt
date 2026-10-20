package com.localkeep.feature.vaultlist

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Back on the Folders screen has to cancel whatever is mid-edit before it leaves — the new-folder
 * card, or an in-place rename — and both entry points (the system gesture, the header chevron) have
 * to agree on that. [resolveFolderBack] is the one function both go through, so this pins its three
 * outcomes directly rather than through Compose.
 */
class FolderBackHandlingTest {

    @Test
    fun `back closes the new-folder card first`() {
        assertEquals(
            FolderBackAction.HideNewFolder,
            resolveFolderBack(showNewFolder = true, renamingId = UUID.randomUUID()),
        )
    }

    @Test
    fun `back cancels an in-progress rename before leaving`() {
        assertEquals(
            FolderBackAction.CancelRename,
            resolveFolderBack(showNewFolder = false, renamingId = UUID.randomUUID()),
        )
    }

    @Test
    fun `back leaves the screen when nothing is mid-edit`() {
        assertEquals(
            FolderBackAction.Leave,
            resolveFolderBack(showNewFolder = false, renamingId = null),
        )
    }
}
