package com.localkeep.feature.files

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for 4C.2d-4 / 1C-O2 (release sweep 2026-09-20): the viewer's export confirmation read
 * "Restored to Pictures/t-huge4000.png", in all five languages. The button and the dialog above it
 * both say Export, and "restore" is this app's trash vocabulary — what Trash does to an item you put
 * back — so the one sentence reporting where the file went named a different act from the one the
 * user performed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportNoticeVocabularyTest {

    private fun notice(): String =
        ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.files_viewer_exported_to, "Pictures/photo.png")

    private fun assertSaysExportNotRestore(text: String) {
        assertTrue(text.contains("export", ignoreCase = true), "'$text' does not name the act it reports")
        // "restaur" covers restauré / restaurado / restaurar; "wiederherge" the German.
        listOf("restore", "restaur", "wiederherge").forEach { trashWord ->
            assertFalse(
                text.contains(trashWord, ignoreCase = true),
                "'$text' uses the trash vocabulary for an export",
            )
        }
    }

    @Test
    fun `english says export`() = assertSaysExportNotRestore(notice())

    @Test
    @Config(qualifiers = "fr")
    fun `french says export`() = assertSaysExportNotRestore(notice())

    @Test
    @Config(qualifiers = "de")
    fun `german says export`() = assertSaysExportNotRestore(notice())

    @Test
    @Config(qualifiers = "es")
    fun `spanish says export`() = assertSaysExportNotRestore(notice())

    @Test
    @Config(qualifiers = "pt")
    fun `portuguese says export`() = assertSaysExportNotRestore(notice())
}
