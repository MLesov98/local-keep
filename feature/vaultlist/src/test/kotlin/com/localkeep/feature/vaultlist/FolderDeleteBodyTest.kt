package com.localkeep.feature.vaultlist

import androidx.compose.ui.test.junit4.createComposeRule
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The delete-folder body counted what survives in the trash with the folder. For a folder holding
 * nothing that read "0 items in all", a statement about a total of nothing; it now says the folder
 * holds no items instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderDeleteBodyTest {

    @get:Rule
    val compose = createComposeRule()

    private fun body(itemCount: Int): String {
        var out = ""
        compose.setContent { LocalKeepTheme { out = folderDeleteBody(itemCount, hasSubfolders = true) } }
        compose.waitForIdle()
        return out
    }

    @Test
    fun theCountedBodyKeepsItsNonBreakingSpace() {
        val text = body(7)
        assertTrue("7\u00a0items in all" in text, "said: $text")
    }

    @Test
    fun anEmptyFolderIsNotCountedAsZeroItems() {
        val text = body(0)
        assertFalse("0" in text, "said: $text")
        assertTrue("no items" in text, "said: $text")
        assertTrue("Trash" in text, "must still say it can be put back: $text")
    }

    @Test
    fun aFolderWithOneItemUsesTheSingular() {
        assertTrue("1\u00a0item in all" in body(1))
    }
}
