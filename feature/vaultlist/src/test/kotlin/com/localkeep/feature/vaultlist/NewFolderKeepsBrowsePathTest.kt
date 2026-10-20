package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Found 2026-10-03: a folder created from inside a folder threw the list back to the root.
 *
 * The folder was made in the right place; the list was not left there. Coming back from the New
 * folder chooser re-enters the list, and [VaultListViewModel.onEnteredList] resets the browse path
 * on every re-entry except a return from something [VaultListViewModel.noteOpeningItem] marked. The
 * add-item path marks itself; the new-folder path did not.
 *
 * The composable cannot be driven from a JVM test, so the wiring is pinned on the source, in the
 * style of [CarriedFolderTrashActionTest].
 */
class NewFolderKeepsBrowsePathTest {

    @Test
    fun `the new-folder entry marks the trip before it navigates`() {
        val source = File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultListScreen.kt")
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        val text = source.readText()
        val start = text.indexOf("val newFolderHere")
        assertTrue(start >= 0, "VaultListScreen no longer declares newFolderHere")
        val body = text.substring(start, text.indexOf("onNewFolder(", start) + 1)

        // FAILS WITHOUT THE FIX: the lambda went straight to onNewFolder.
        assertTrue(
            "viewModel.noteOpeningItem()" in body,
            "newFolderHere must call viewModel.noteOpeningItem() before onNewFolder, or returning " +
                "from the chooser resets the list to the root. Found:\n$body",
        )
    }
}
