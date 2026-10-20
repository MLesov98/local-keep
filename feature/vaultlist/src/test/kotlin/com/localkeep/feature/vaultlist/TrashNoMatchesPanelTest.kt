package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A trash search that matches nothing has to say so, even when a folder is in the trash.
 *
 * The full-screen empty state is guarded on `items.isEmpty() && folders.isEmpty()`, and the folder
 * list is deliberately never narrowed by the query — a folder row stands for a subtree whose items
 * are absent from `items` by construction. So one trashed folder was enough to send a zero-match
 * search into the else branch, where the folder rows render and nothing anywhere says the query
 * missed: the user reads a list that looks like the answer to what they typed. Found
 * 2026-09-07; repro needs one trashed folder, a query matching no item, and more than
 * `SEARCH_THRESHOLD` items in the trash so the search field is on screen at all.
 *
 * The UI/UX spec lists "no search results" among the mandatory empty states, and
 * `vaultlist_trash_empty_no_matches` / `_body_search` already existed for this exact case — the
 * state was intended and missing, not invented here.
 */
class TrashNoMatchesPanelTest {

    /** The case the defect suppressed: a search matched nothing, and folders are why it went quiet. */
    @Test
    fun saysNoMatchesEvenWhenTheTrashHoldsFolders() {
        assertTrue(
            trashNoMatchesInList(itemCount = 0, folderCount = 2, searching = true, loadFailed = false),
            "a zero-match search must be reported; trashed folders are not matches, they were " +
                "never tested against the query",
        )
    }

    /** The rows are the answer; a message beside them would be contradicting them. */
    @Test
    fun staysQuietWhenTheSearchActuallyMatchedSomething() {
        assertFalse(
            trashNoMatchesInList(itemCount = 3, folderCount = 2, searching = true, loadFailed = false),
        )
    }

    /** Nothing typed is not a failed search — that is the ordinary "Trash is empty" state's job. */
    @Test
    fun staysQuietWhenNoSearchIsRunning() {
        assertFalse(
            trashNoMatchesInList(itemCount = 0, folderCount = 2, searching = false, loadFailed = false),
        )
    }

    /**
     * With no folders either, the full-screen empty state already handles it and says the same two
     * strings. Two panels claiming the screen at once is the one outcome worse than none.
     */
    @Test
    fun leavesTheFullScreenPanelToDoItWhenNothingElseIsOnScreen() {
        assertFalse(
            trashNoMatchesInList(itemCount = 0, folderCount = 0, searching = true, loadFailed = false),
        )
    }

    /**
     * A failed read empties both lists too, and the error banner above already says what happened.
     * "No matches" on top of "couldn't read the trash" tells the user something the app does not know.
     */
    @Test
    fun doesNotBlameTheSearchForAFailedRead() {
        assertFalse(
            trashNoMatchesInList(itemCount = 0, folderCount = 2, searching = true, loadFailed = true),
        )
    }

    /**
     * And it is actually rendered. The predicate on its own is a fact about a function; what the
     * defect was is that no panel existed on the route at all.
     *
     * Read from the source because `TrashScreen` needs a `TrashViewModel` built from
     * `VaultRepository`, `VaultLockManager` and `FileBlobStore`, none of which stand up on the JVM —
     * the same reason `feature/backup`'s `ExportPassphraseGateTest` reads its own screen.
     */
    @Test
    fun theListRendersThePanelItAsksFor() {
        val screen = File("src/main/kotlin/com/localkeep/feature/vaultlist/TrashScreen.kt").readText()
        val list = screen.substringAfter("item(key = \"folders-gap\")").substringBefore("itemsIndexed(state.items")
        assertTrue(
            "trashNoMatchesInList(" in list,
            "the list body never asks whether the search missed; found: $list",
        )
        assertTrue(
            "R.string.vaultlist_trash_empty_no_matches" in list &&
                "R.string.vaultlist_trash_empty_body_search" in list,
            "the panel must use the strings written for this state; found: $list",
        )
    }
}
