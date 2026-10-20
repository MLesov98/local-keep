package com.localkeep.feature.files

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Files & media screen had two layouts. An Identity or a Card led with a camera/gallery pair and
 * demoted the only route for a PDF or any other document to a small muted "Attach a file" text
 * button, and its big empty-state file icon was switched off — tapping it did nothing. Every other
 * type got a full-width button and a live icon. Reported from a phone, 2026-10-12.
 *
 * There is now one layout and one door: a single "Attach a file" button, and the icon, both open the
 * Gallery / Camera / Documents sheet the File editor already uses.
 *
 * Asserted on the source, matching `EmptyStateOpensSourceChooserTest`: the controls are lambdas
 * inside a Composable that needs a Hilt ViewModel, which a JVM test cannot compose.
 */
class AttachmentPickerOneAddRouteTest {

    private val picker: String =
        File("src/main/kotlin/com/localkeep/feature/files/AttachmentPicker.kt").readText()

    private fun slice(anchor: String, lines: Int): String {
        val at = picker.indexOf(anchor)
        require(at >= 0) { "anchor '$anchor' not found in AttachmentPicker.kt" }
        return picker.substring(at).lineSequence().take(lines).joinToString("\n")
    }

    /** Control: the slices below are bounded and reach what they are asserting about. */
    @Test
    fun `reads the empty state call, whole and bounded`() {
        assertTrue("iconTapLabel" in slice("LkEmptyState(", 22), "slice stops before iconTapLabel")
    }

    @Test
    fun `no item type gets a different add layout`() {
        assertFalse("photoFirst" in picker, "a per-type layout is back: ${Regex("photoFirst").findAll(picker).count()} refs")
    }

    @Test
    fun `the empty-state icon is live on every type`() {
        val call = slice("LkEmptyState(", 22)
        assertFalse("onIconTap = if" in call, "the icon is conditionally disarmed")
        assertTrue("onIconTap = onAttach" in call, "the icon does not open the source sheet")
    }

    @Test
    fun `the muted text button is gone`() {
        // Other text buttons exist on this screen (the pending row's Cancel); this is the add route's.
        assertFalse(
            Regex("""LkTextButton\(\s*text = workingOrAttachLabel""").containsMatchIn(picker),
            "the generic route is a small text button again",
        )
    }

    @Test
    fun `the add button and the icon open the shared source sheet`() {
        assertTrue("FileSourceChooserSheet(" in picker, "the screen does not use the shared sheet")
        assertTrue(
            Regex("= onAttach\\b").findAll(picker).count() >= 2,
            "both the button and the icon must open the sheet",
        )
        // ...and the one lambda both call is what opens it.
        assertTrue("showSourceChooser = true" in slice("onAttach = {", 4), "onAttach no longer opens the sheet")
        val sheet = slice("FileSourceChooserSheet(", 30)
        assertTrue("onGallery" in sheet && "onCapture" in sheet && "onDocuments" in sheet, "sheet is missing a source")
    }
}
