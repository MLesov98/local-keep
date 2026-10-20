package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A folder created inside the editor's picker is visible on the detail screen after Save
 * (2026-09-20, release sweep 4A-F10).
 *
 * The bug this closes: the detail screen and the edit form are separate back-stack entries and so
 * separate instances of this ViewModel. The detail's copy follows writes through the
 * `repository.onSaved` collector, which called `loadItem` — and `loadItem` rebuilds the item's own
 * fields, `folderId` included, but reads nothing about folders. The detail resolves a folder's
 * *name* by looking that id up in `state.folders`, a list loaded once in `init`, so a folder
 * created in the picker during the edit was not in it: the lookup missed and an item that was
 * filed read `Folder: Add to folder`, across two dumps eight seconds apart, while the vault-list
 * row already showed the folder. Leaving and re-entering fixed it, which is what pointed at the
 * list rather than at the item.
 *
 * Source-pinned for the reason `ItemEditViewModelTest` gives: this module's `ItemEditViewModel`
 * cannot be constructed on a JVM.
 */
class DetailFollowsAFolderCreatedInTheEditorTest {

    private val viewModel =
        File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditViewModel.kt").readText()

    private val onSaved = viewModel
        .substringAfter("repository.onSaved.collect {", "")
        .substringBefore("\n        }")

    @Test
    fun `following a write elsewhere reloads the folder list too`() {
        assertTrue(onSaved.isNotEmpty(), "the onSaved collector moved; this test no longer reads it")
        assertTrue("loadFolders()" in onSaved, "the detail screen still cannot name a folder it did not know about")
        assertTrue("loadItem(current.itemId.toString())" in onSaved, "the item reload is gone")
    }

    /**
     * And it still refuses to reload a form with work in it — the guard that reload sits behind is
     * what stops it discarding what the user is typing, and adding a second call must not have
     * moved anything out from under it.
     */
    @Test
    fun `a dirty editor is still left alone`() {
        val guard = onSaved.substringBefore("loadFolders()")
        assertTrue(
            "if (current.isNew || current.isSaving || current.trashed || isDirty()) return@collect" in guard,
            "the folder reload runs ahead of the guard that protects an editor with typing in it",
        )
    }

    /**
     * The premise: the detail screen really does resolve the name by lookup, so a list missing the
     * folder is the whole defect.
     */
    @Test
    fun `the detail screen names the folder by looking it up in that list`() {
        val detail =
            File("src/main/kotlin/com/localkeep/feature/itemedit/ItemDetailScreen.kt").readText()
        assertTrue("state.folders.firstOrNull { it.id == state.folderId }" in detail)
    }
}
