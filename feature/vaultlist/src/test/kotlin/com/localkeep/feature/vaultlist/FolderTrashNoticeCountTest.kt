package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The folder-trash notice counts what the operation moves, not what the chip admits.
 *
 * `state.folderItemCounts` is built by `subtreeCounts(current.typeFilter)` — deliberately, because
 * a count rendered beside a filter has to be a count of what the filter admits, or the folder row
 * reads "Receipts, 1 item" next to a chip that excluded that very item. `repository.trashFolder`
 * has no such narrowing: it trashes the whole subtree. Resolving the snackbar from the row's number
 * therefore understated it by everything the chip was hiding — a folder holding 1 TOTP and 40
 * logins, trashed with the 2FA chip on, announced "1 folder, 1 item moved to Trash" over 41 items.
 * That path posts a plain message with no undo action, so the notice is the only report of the
 * operation the user gets.
 *
 * Reachable from the folder row's long-press menu and from the drag-to-Trash dock, any time a type
 * chip is applied. `folderSubtreeItemCounts` is the same subtree count with no filter applied — the
 * operation's number — and it is the only one this sentence may use.
 */
class FolderTrashNoticeCountTest {

    private val screen = File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultListScreen.kt")
    private val viewModel = File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultListViewModel.kt")

    private fun code(file: File): String {
        if (!file.isFile) fail("cannot find ${file.absolutePath} — this test reads the source it guards")
        return file.readText().lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
    }

    private fun trashFolderAction(): String =
        code(screen).substringAfter("private fun trashFolderAction(", missingDelimiterValue = "")
            .substringBefore("private const val REVEAL_HOLD_MS")
            .ifEmpty { fail("VaultListScreen no longer declares trashFolderAction(") }

    @Test
    fun `the notice counts the whole subtree, not the filtered subtree`() {
        val body = trashFolderAction()
        assertTrue(
            "state.folderSubtreeItemCounts[folder.id]" in body,
            "the folder-trash notice must name the number the operation moves. Found: $body",
        )
        assertTrue(
            "state.folderItemCounts" !in body,
            "the folder-trash notice reads the type-filtered count again. That map is narrowed by " +
                "the active chip; trashFolder is not, so with a chip on the sentence names a " +
                "strict subset of what moved and there is no undo on this path. Found: $body",
        )
    }

    @Test
    fun `the unfiltered subtree count is published with no type filter applied`() {
        val text = code(viewModel)
        assertTrue(
            "val folderSubtreeItemCounts: Map<UUID, Int>" in text,
            "VaultListUiState no longer carries folderSubtreeItemCounts",
        )
        assertTrue(
            "folderSubtreeItemCounts = subtreeCounts(snapshot.liveItems, null)" in text,
            "folderSubtreeItemCounts must be counted with no type filter — that is the whole point " +
                "of it being a second map",
        )
    }

    /** The control: the filtered map must keep its filter, or the folder rows start lying instead. */
    @Test
    fun `the folder rows keep their filtered count`() {
        assertTrue(
            "folderItemCounts = subtreeCounts(snapshot.liveItems, current.typeFilter)" in code(viewModel),
            "the count shown on a folder row must stay narrowed to the active chip",
        )
    }
}
