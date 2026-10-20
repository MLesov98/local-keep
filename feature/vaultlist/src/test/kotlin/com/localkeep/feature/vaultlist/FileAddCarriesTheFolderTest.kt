package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Device, 2026-10-04 (API 36, foss release): a File added from inside "My Folder" saved at the top
 * level, while every other type added from the same sheet started in the folder.
 *
 * The add flow hands the browsed folder to the editor through `PendingFolderSelection`, and the
 * `FileEditor` branch was deliberately left out of it on 2026-08-12 — the files editor had no
 * folder field then, so a request it could not consume would have filed the *next* add instead.
 * The field shipped on 2026-08-19; the branch never caught up. The file editor now consumes the
 * request on `FILE_NEW`, so the branch makes it, and marks the trip the way the item branch does so
 * that coming back does not reset the list to the root.
 *
 * Pinned on the source in the style of [NewFolderKeepsBrowsePathTest]: the composable cannot be
 * driven from a JVM test.
 */
class FileAddCarriesTheFolderTest {

    private fun fileBranch(): String {
        val source = File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultListScreen.kt")
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        val text = source.readText()
        val start = text.indexOf("AddDestination.FileEditor ->")
        assertTrue(start >= 0, "VaultListScreen no longer routes AddDestination.FileEditor")
        val end = text.indexOf("onAddFile(", start)
        assertTrue(end > start, "the FileEditor branch no longer calls onAddFile")
        return text.substring(start, end)
    }

    @Test
    fun `a file added inside a folder asks the editor to start there`() {
        assertTrue(
            "PendingFolderSelection::request" in fileBranch(),
            "the FileEditor branch must request the browsed folder before onAddFile. Found:\n" +
                fileBranch(),
        )
    }

    @Test
    fun `the trip to the file editor keeps the browse path`() {
        assertTrue(
            "viewModel.noteOpeningItem()" in fileBranch(),
            "the FileEditor branch must mark the trip before onAddFile, or cancelling the add " +
                "resets the list to the root. Found:\n" + fileBranch(),
        )
    }
}
