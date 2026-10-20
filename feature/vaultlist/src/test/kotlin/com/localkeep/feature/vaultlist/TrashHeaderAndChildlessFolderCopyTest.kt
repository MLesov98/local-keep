package com.localkeep.feature.vaultlist

import androidx.compose.ui.test.junit4.createComposeRule
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two copy defects found by the scaling pass, both of the kind where a sentence states less or more
 * than the screen it sits on.
 *
 * The Trash header counted items only ("10 items · still encrypted") while trashed folders are
 * listed right under it, so it undercounted what the screen offers to restore or destroy. It now
 * names the folders too, composed from the two plurals the folder rows already use (a single plural
 * cannot agree with two counts).
 *
 * The delete-folder body said "subfolders included" for a folder that has none. A folder with no
 * items and no subfolders now says exactly that; one that has subfolders keeps the sentence, which is
 * true of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrashHeaderAndChildlessFolderCopyTest {

    @get:Rule
    val compose = createComposeRule()

    private fun header(items: Int, folders: Int): String {
        var out = ""
        compose.setContent { LocalKeepTheme { out = trashHeaderSubtitle(items, folders) } }
        compose.waitForIdle()
        return out
    }

    private fun body(items: Int, hasSubfolders: Boolean): String {
        var out = ""
        compose.setContent { LocalKeepTheme { out = folderDeleteBody(items, hasSubfolders) } }
        compose.waitForIdle()
        return out
    }

    @Test
    fun theHeaderCountsFoldersAsWellAsItems() {
        assertEquals("2 folders, 10 items · still encrypted", header(items = 10, folders = 2))
    }

    @Test
    fun theHeaderAgreesInTheSingular() {
        assertEquals("1 folder, 1 item · still encrypted", header(items = 1, folders = 1))
    }

    /** Control: with no folder in the trash the line is the item count alone, as it always was. */
    @Test
    fun controlWithoutFoldersTheHeaderIsUnchanged() {
        assertEquals("10 items · still encrypted", header(items = 10, folders = 0))
    }

    @Test
    fun aChildlessEmptyFolderIsNotSaidToIncludeSubfolders() {
        val text = body(items = 0, hasSubfolders = false)
        assertFalse("included" in text, "said: $text")
        assertTrue("no items and no subfolders" in text, "said: $text")
        assertTrue("Trash" in text, "must still say it can be put back: $text")
    }

    /** Control: an empty folder that does have subfolders keeps the sentence that is true of it. */
    @Test
    fun controlAnEmptyFolderWithSubfoldersStillSaysSubfoldersAreIncluded() {
        assertTrue("subfolders included" in body(items = 0, hasSubfolders = true))
    }

    /** Control: a folder that holds items is counted as before, with or without subfolders. */
    @Test
    fun controlAFolderWithItemsIsCountedAsBefore() {
        assertTrue("7 items in all" in body(items = 7, hasSubfolders = false))
    }
}
