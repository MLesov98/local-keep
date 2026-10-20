package com.localkeep.feature.vaultlist

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Empty trash" over a trash holding only folders read "all 0 items and 1 folder". With no items
 * the sentence leads with the folders instead, and drops the trailing clause that would repeat them.
 * The count and its noun are joined by a no-break space so the line never ends on "1" and starts the
 * next with "folder" (D3-F-04).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmptyTrashPhrasingTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun sentence(items: Int, folders: Int): String {
        val p = emptyTrashPhrasing(itemTotal = items, folderTotal = folders)
        val removes = context.resources.getQuantityString(p.removesRes, p.removesCount, p.removesCount)
        val clause = if (p.showFoldersClause) {
            " " + context.resources.getQuantityString(R.plurals.vaultlist_trash_empty_and_folders, folders, folders)
        } else {
            ""
        }
        return removes + clause
    }

    @Test
    fun aTrashOfOnlyFoldersNamesNoItems() {
        assertEquals("This permanently removes 1\u00a0folder", sentence(items = 0, folders = 1))
        assertEquals("This permanently removes all 3\u00a0folders", sentence(items = 0, folders = 3))
        assertFalse("0 items" in sentence(items = 0, folders = 2))
    }

    @Test
    fun aTrashWithItemsAndFoldersKeepsTheTwoPartSentence() {
        assertEquals("This permanently removes all 4 items and 2\u00a0folders", sentence(items = 4, folders = 2))
    }

    @Test
    fun aTrashOfOnlyItemsIsUnchanged() {
        assertEquals("This permanently removes all 4 items", sentence(items = 4, folders = 0))
    }

    @Test
    fun everyLocaleHasTheFoldersOnlyPhrase() {
        // Control: a missing translation would fall back to English inside a localised dialog.
        for (tag in listOf("fr", "de", "es", "pt")) {
            val localized = context.createConfigurationContext(
                context.resources.configuration.apply { setLocale(Locale.forLanguageTag(tag)) },
            )
            val text = localized.resources.getQuantityString(
                R.plurals.vaultlist_trash_empty_removes_folders_only, 2, 2,
            )
            val english = sentence(items = 0, folders = 2)
            assertTrue(text != english, "$tag fell back to English: $text")
            assertTrue("2" in text, "$tag lost the count: $text")
        }
    }
}
