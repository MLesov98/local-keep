package com.localkeep.feature.files

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 4C-17, 2026-09-20: every "From gallery" control led with `LkIcons.Import`, the two-arrows-passing
 * mark this set binds to *migration* — the same glyph the drawer puts on "bring another password
 * manager's data in", one screen away. Reading a photo out of the system picker is not that.
 *
 * The reuse happened because `LkIcons` held no image glyph at all (`Camera`, `Attachment`,
 * `Import`), so the fix was a new mark with its own KDoc rather than a re-pointing of an old one.
 * What these tests protect is the *set*: the three add-routes on the File editor differ by glyph as
 * well as by label, and none of them borrows a meaning another control owns.
 *
 * Source-scan, bounded to each control, so an assertion cannot pass because some other button on
 * the same screen happens to use the right icon.
 */
class GalleryRouteWearsTheGalleryGlyphTest {

    private val editorSource =
        File("src/main/kotlin/com/localkeep/feature/files/FileItemEditScreen.kt").readText()

    @Test
    fun `the empty-state source sheet's gallery row draws the same glyph`() {
        // Two controls open the same picker; a sheet that disagreed with the button behind it
        // would make one act look like two.
        val sheetAt = editorSource.indexOf("internal fun FileSourceChooserSheet")
        assertTrue(sheetAt > 0, "FileSourceChooserSheet not found — did it move?")
        val sheet = editorSource.substring(sheetAt)
        // `FileSourceRow` takes its icon *before* its label, so this row is read backwards.
        val label = sheet.indexOf("R.string.files_edit_from_gallery")
        assertTrue(label > 0, "the sheet no longer offers the gallery")
        val icon = sheet.lastIndexOf("icon = ", label)
        assertTrue(icon in 1 until label, "the sheet's gallery row declares no icon")
        val line = sheet.substring(icon, sheet.indexOf('\n', icon))
        assertTrue("LkIcons.Gallery" in line, "found \"$line\"")
    }

    /** The icon line declared before [labelKey] inside the source sheet — rows take icon first. */
    private fun sheetIconFor(labelKey: String): String {
        val sheetAt = editorSource.indexOf("internal fun FileSourceChooserSheet")
        assertTrue(sheetAt > 0, "FileSourceChooserSheet not found — did it move?")
        val sheet = editorSource.substring(sheetAt)
        val label = sheet.indexOf(labelKey)
        assertTrue(label > 0, "the sheet no longer offers $labelKey")
        val icon = sheet.lastIndexOf("icon = ", label)
        return sheet.substring(icon, sheet.indexOf('\n', icon))
    }

    @Test
    fun `the three add routes still differ from one another`() {
        // The control. A single glyph applied to all three would satisfy every assertion above
        // that names Gallery, and would be a worse outcome than the defect. Read from the source
        // sheet: since 2026-10-09 it is the File editor's only route to all three.
        val gallery = sheetIconFor("R.string.files_edit_from_gallery")
        val capture = sheetIconFor("R.string.files_capture_action")
        val documents = sheetIconFor("R.string.files_edit_from_documents")
        assertTrue("LkIcons.Camera" in capture, "capture means the camera; found \"$capture\"")
        assertTrue("LkIcons.Attachment" in documents, "documents means any file; found \"$documents\"")
        assertEquals(
            3,
            setOf(gallery.trim(), capture.trim(), documents.trim()).size,
            "three routes, three marks",
        )
    }

    @Test
    fun `the new glyph carries a KDoc saying what it means`() {
        // LkIcons.kt's own contract: a mark whose meaning is not written down is the condition
        // that let Import stand in for an image in the first place.
        val icons = File("../../core/ui/src/main/kotlin/com/localkeep/core/ui/theme/LkIcons.kt").readText()
        val declaration = icons.indexOf("val Gallery = ")
        assertTrue(declaration > 0, "LkIcons.Gallery is gone")
        val doc = icons.substring(maxOf(0, declaration - 1200), declaration)
        assertTrue(
            "photo gallery" in doc,
            "the glyph must state what it stands for; the 1200 characters above it read:\n$doc",
        )
    }
}
