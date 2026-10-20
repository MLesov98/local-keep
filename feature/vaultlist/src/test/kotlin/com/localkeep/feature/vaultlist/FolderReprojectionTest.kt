package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Folder
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The folders screen must re-project when concealment changes under it, not only when it writes.
 *
 * `FolderViewModel` projected the vault once in its initialiser and again after each of its own
 * mutations, and collected nothing. So a reveal session opened on the vault list, followed by a tap
 * on the drawer's Folders row, listed the concealed folders by name with their subtree counts — and
 * kept listing them after `RevealSession`'s 120 s window closed and every other surface had gone
 * back to concealing them, because nothing on this screen was watching. `VaultVisibility`'s contract
 * is that a hidden record is absent from every user-facing read or none, and the read here was
 * simply stale.
 *
 * Two of the three assertions below read the source, in the style of [VaultListMainThreadWorkTest]
 * and [SearchDebounceDoesNotSelfCancelTest], and for the reason the latter states: `VaultRepository`
 * and `VaultLockManager` are final classes built from a `Context` and a `KeystoreManager`, and this
 * module's unit-test classpath carries neither a mocking library nor `kotlinx-coroutines-test`. The
 * behavioural half of the fix — what a re-projection does to a half-finished interaction — is pure,
 * so it is asserted for real.
 */
class FolderReprojectionTest {

    private val source =
        File("src/main/kotlin/com/localkeep/feature/vaultlist/FolderViewModel.kt")

    private fun lines(): List<String> {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        return source.readLines()
    }

    /** The `init` block, up to the first line that closes it at the class's indentation. */
    private fun initBlock(): String {
        val all = lines()
        val start = all.indexOfFirst { it == "    init {" }
        assertTrue(start >= 0, "FolderViewModel no longer declares an `init` block")
        val end = all.drop(start + 1).indexOfFirst { it == "    }" } + start + 1
        assertTrue(end > start, "could not find the end of the `init` block")
        return all.subList(start, end + 1).joinToString("\n")
    }

    @Test
    fun `the folders screen collects the shared presentation flow`() {
        val init = initBlock()
        assertTrue(
            "repository.presentationState" in init,
            "FolderViewModel must collect the same projection flow the vault list and trash " +
                "collect. Without it a reveal expiring, a conceal, a Stop hiding or a lease " +
                "boundary — none of which is a write this screen performed — leaves concealed " +
                "folders listed by name with their counts. Found:\n$init",
        )
    }

    @Test
    fun `the presentation emission's own snapshot is used instead of re-projecting`() {
        assertTrue(
            "load(presentation.snapshot)" in lines().joinToString("\n"),
            "VaultPresentationState.Unlocked carries the snapshot VaultVisibility.project just " +
                "produced; re-projecting it walks the whole vault a second time for the same answer",
        )
    }

    @Test
    fun `the collector's whole-vault walk runs off the main thread`() {
        val init = initBlock()
        assertTrue(
            "withContext(Dispatchers.Default)" in init,
            "the presentation flow also emits on every save (VaultLockManager.onSaved), and " +
                "`load` is a whole-tree walk plus checkFolderTreeHealth(). On viewModelScope's " +
                "Main.immediate that is a dropped frame per commit for as long as Folders is " +
                "open, an import included. Found:\n$init",
        )
    }

    @Test
    fun `a re-projection that removes the folder being renamed closes the rename field`() {
        val work = Folder(id = UUID.randomUUID(), name = "Work")
        val concealed = Folder(id = UUID.randomUUID(), name = "Divorce")
        val revealed = listOf(FolderRow(work, 0), FolderRow(concealed, 0))
        val editing = FolderUiState()
            .withRows(revealed, needsRepair = false)
            .copy(renamingId = concealed.id, renameText = "Divorce papers")

        val afterConceal = editing.withRows(listOf(FolderRow(work, 0)), needsRepair = false)

        assertNull(
            afterConceal.renamingId,
            "a rename field left open over a row that is gone can never be committed — " +
                "commitRename returns silently when the folder is not in `folders`",
        )
        assertEquals("", afterConceal.renameText, "and the text it held goes with it")
    }

    @Test
    fun `a re-projection keeps a rename whose folder is still shown`() {
        val work = Folder(id = UUID.randomUUID(), name = "Work")
        val rows = listOf(FolderRow(work, 0))
        val editing = FolderUiState()
            .withRows(rows, needsRepair = false)
            .copy(renamingId = work.id, renameText = "Employment")

        val afterSave = editing.withRows(rows, needsRepair = false)

        assertEquals(work.id, afterSave.renamingId, "control: a save must not close an unrelated edit")
        assertEquals("Employment", afterSave.renameText)
    }

    @Test
    fun `a re-projection drops a move, a delete confirmation and a just-created marker with their row`() {
        val work = Folder(id = UUID.randomUUID(), name = "Work")
        val concealed = Folder(id = UUID.randomUUID(), name = "Divorce")
        val revealed = listOf(FolderRow(work, 0), FolderRow(concealed, 0))
        val busy = FolderUiState()
            .withRows(revealed, needsRepair = false)
            .copy(movingId = concealed.id, pendingDeletion = concealed.id, justCreatedId = concealed.id)

        val afterConceal = busy.withRows(listOf(FolderRow(work, 0)), needsRepair = false)

        assertNull(afterConceal.movingId, "the Move to… picker must not stay open on a vanished folder")
        assertNull(afterConceal.pendingDeletion, "nor the delete confirmation, which names it")
        assertNull(afterConceal.justCreatedId, "nor the just-created marker")
    }
}
