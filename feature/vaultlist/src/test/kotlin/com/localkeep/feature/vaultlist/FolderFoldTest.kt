package com.localkeep.feature.vaultlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The folder list folds to three rows, and the fold is the same fold everywhere.
 *
 * Folder rows are navigation stacked above the results they filter, so a long folder list spends the
 * whole screen on chrome and shows no items at all. This pins the count and the boundary conditions
 * around it — a list of exactly three must not sprout a control that would fold nothing away, and an
 * expanded list must report nothing hidden, or the control would offer to show four more folders
 * while all of them are on screen.
 */
class FolderFoldTest {

    private fun folders(n: Int): List<String> = (1..n).map { "folder-$it" }

    @Test
    fun `a long folder list shows only the first three`() {
        assertEquals(
            listOf("folder-1", "folder-2", "folder-3"),
            foldersShown(folders(9), expanded = false),
        )
    }

    @Test
    fun `four folders is already too many — this is the case reported from the phone`() {
        assertEquals(3, foldersShown(folders(4), expanded = false).size)
        assertEquals(1, foldersFoldedAway(4, expanded = false))
        assertTrue(folderOverflowVisible(4))
    }

    @Test
    fun `three or fewer are all shown and grow no control`() {
        for (n in 0..FolderRowCap) {
            assertEquals(folders(n), foldersShown(folders(n), expanded = false))
            assertEquals(0, foldersFoldedAway(n, expanded = false))
            assertFalse(folderOverflowVisible(n), "a list of $n folders needs no fold control")
        }
    }

    @Test
    fun `expanding shows every folder and hides none`() {
        assertEquals(folders(9), foldersShown(folders(9), expanded = true))
        assertEquals(0, foldersFoldedAway(9, expanded = true))
        // The control stays — it is what folds the list back up.
        assertTrue(folderOverflowVisible(9))
    }
}
