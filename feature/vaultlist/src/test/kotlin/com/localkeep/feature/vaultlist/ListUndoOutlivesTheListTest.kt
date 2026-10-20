package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Found 2026-10-02: the Undo the list's own notices offer must still work when the list
 * that posted it has been destroyed.
 *
 * `LkNotices` is a process singleton and the Undo is a closure stored in it. The vault list is popped
 * and rebuilt by Folders > open a folder, by a finished import and by a save from the generator —
 * each `navigate(HOME) { popUpTo(HOME) { inclusive = true } }`, which clears
 * `VaultListViewModel` — while a notice posted a moment earlier is still on screen for its eight
 * seconds. Its button then launched on a cancelled `viewModelScope` and did nothing, with no message.
 * `TrashUndoBus` (`:app`) documents the same trap for the item detail screen.
 *
 * Asserted on the source, in the style of [VaultListMainThreadWorkTest] and for its reason:
 * `VaultListViewModel` is built from a `VaultRepository` and a `VaultLockManager`, both final classes
 * constructed from a `Context` and a `KeystoreManager`, and this module's unit-test classpath carries
 * neither a mocking library nor `kotlinx-coroutines-test`. What is pinned is the property that broke:
 * no Undo closure launches on `viewModelScope`.
 */
class ListUndoOutlivesTheListTest {

    private val source =
        File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultListViewModel.kt")

    private fun text(): String {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        return source.readText()
    }

    /** From the first occurrence of [from] up to the first occurrence of [until] after it. */
    private fun between(from: String, until: String): String {
        val all = text()
        val start = all.indexOf(from)
        assertTrue(start >= 0, "VaultListViewModel no longer contains `$from`")
        val end = all.indexOf(until, start + from.length)
        assertTrue(end > start, "could not find `$until` after `$from`")
        // Code only: the comments beside these closures explain why they avoid `viewModelScope`,
        // and naming it is not launching on it.
        return all.substring(start, end).lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
    }

    @Test
    fun `the single-item trash Undo runs its write on the process scope`() {
        val body = between("private fun undoTrash(", "\n    }\n")
        assertTrue(
            "scope = undoScope" in body,
            "undoTrash must hand `mutate` the process-lifetime undoScope. With the default " +
                "viewModelScope the Undo does nothing once the list's ViewModel has been cleared. " +
                "Found:\n$body",
        )
    }

    @Test
    fun `mutate launches on the scope it is given, not on viewModelScope`() {
        val body = between("    private fun mutate(", "\n    /** Read and written only from the main dispatcher")
        assertTrue("scope.launch {" in body, "mutate must launch on its `scope` parameter. Found:\n$body")
        assertFalse(
            "viewModelScope.launch" in body,
            "mutate launches on viewModelScope directly, so its `scope` parameter changes nothing. " +
                "Found:\n$body",
        )
    }

    @Test
    fun `the drag-move Undo runs on the process scope`() {
        val undoAction = between("actionLabel = LkNotices.Message.Text(undoLabel),", "fun trashFolderFromDrag(")
        assertTrue(
            "undoScope.launch" in undoAction,
            "commitDrag's Undo must launch on undoScope. Found:\n$undoAction",
        )
        assertFalse(
            "viewModelScope" in undoAction,
            "commitDrag's Undo launches on viewModelScope: after the list is rebuilt the button is " +
                "shown, tappable, and does nothing. Found:\n$undoAction",
        )
    }

    @Test
    fun `the bulk-trash Undo runs on the process scope`() {
        val body = between("private fun bulkRestoreTrashed(", "\n    }\n")
        assertTrue(
            "undoScope.launch" in body,
            "bulkRestoreTrashed is the Undo on the bulk-trash notice and must launch on undoScope. " +
                "Found:\n$body",
        )
        assertFalse(
            "viewModelScope" in body,
            "bulkRestoreTrashed launches on viewModelScope. Found:\n$body",
        )
    }

    @Test
    fun `the undo scope belongs to the process, not to a ViewModel`() {
        val declaration = text().lineSequence().firstOrNull { "val undoScope" in it }
            ?: fail("VaultListViewModel declares no `undoScope`")
        assertTrue(
            "CoroutineScope(SupervisorJob()" in declaration,
            "undoScope must be an independent CoroutineScope (SupervisorJob, so one failed restore " +
                "cannot leave every later Undo inert). Found: $declaration",
        )
        assertFalse("viewModelScope" in declaration, "undoScope derives from viewModelScope: $declaration")
    }
}
