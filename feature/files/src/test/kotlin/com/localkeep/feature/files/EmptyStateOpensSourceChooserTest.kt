package com.localkeep.feature.files

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The empty-state plate used to run the gallery import directly (`galleryLauncher(...)`), though
 * the form offers three sources — gallery, camera, documents — via the row of buttons beneath it.
 * `files_edit_empty_icon_tap_label` said "Import a file from your gallery" and the plate matched
 * that sentence exactly, so the label was honest and the body text above it ("Import a file from
 * your device, or capture a photo…") was the one describing three ways in. The plate must now open
 * the same three-way chooser the buttons below it can reach, not one source picked on its behalf.
 *
 * Asserted on the source, matching `CameraDenialSpacingTest`'s idiom: `onIconTap` is a lambda handed
 * to a Composable, not something a JVM unit test can invoke and observe launching a system picker.
 */
class EmptyStateOpensSourceChooserTest {

    private val screen: String =
        File("src/main/kotlin/com/localkeep/feature/files/FileItemEditScreen.kt").readText()

    private val anchor = "LkEmptyState("

    /** The empty-state call's own arguments, bounded to the lines it fits inside. */
    private val emptyStateCode: String = run {
        val start = screen.indexOf(anchor)
        require(start >= 0) { "anchor '$anchor' not found in FileItemEditScreen.kt" }
        screen.substring(start).lineSequence().take(15).joinToString("\n")
    }

    /** Control: the slice is bounded and reaches the tap label it is asserting about. */
    @Test
    fun `reads the empty state call, whole and bounded`() {
        assertTrue(
            "files_edit_empty_icon_tap_label" in emptyStateCode,
            "the slice stops before iconTapLabel, so an absence inside it proves nothing",
        )
    }

    @Test
    fun `the plate opens the source chooser, not the gallery launcher directly`() {
        assertTrue(
            "onIconTap = { showSourceChooser = true }" in emptyStateCode,
            "the empty-state plate must open the same chooser the row of buttons below it uses",
        )
        assertFalse(
            "galleryLauncher(" in emptyStateCode,
            "the plate must not run one specific source directly — three sources exist",
        )
    }

    /** Control: the sheet it opens actually wires all three sources, not just the two visual ones. */
    @Test
    fun `the chooser sheet offers gallery, capture and documents`() {
        val sheetAnchor = "internal fun FileSourceChooserSheet("
        val sheetStart = screen.indexOf(sheetAnchor)
        require(sheetStart >= 0) { "$sheetAnchor not found in FileItemEditScreen.kt" }
        val sheetCode = screen.substring(sheetStart).lineSequence().take(45).joinToString("\n")
        assertTrue("onClick = onGallery" in sheetCode, "missing the gallery row")
        assertTrue("onClick = onCapture" in sheetCode, "missing the capture row")
        assertTrue("onClick = onDocuments" in sheetCode, "missing the documents row")
    }
}
