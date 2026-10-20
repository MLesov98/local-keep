package com.localkeep.feature.files

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Two layout defects on the Files & media screen, reported from a phone 2026-10-12.
 *
 * **The indent.** Each attached file was a bare `LkGroupRow` in the list, and that row pads itself 16dp
 * on both sides to sit inside a card. With no card around it the row hung 16dp in from the button
 * above, so its type tile and its trash button lined up with nothing. Every other list in the app
 * (Trash, Hidden items) puts the row in an [com.localkeep.core.ui.theme.LkGroup]; this one now does.
 *
 * **The two notes.** The photo/backup disclosure and the video disclosure were two amber cards
 * stacked under the button. They stay two *strings* — each is single-sourced and shared (the video
 * sentence is word for word `files_edit_metadata_video_note` in the File editor, and the photo one
 * is also shown on the capture screen and held by the security-copy tests) — but they are drawn in
 * one card, so rewording either one still changes it everywhere.
 *
 * Asserted on the source, matching `AttachmentPickerOneAddRouteTest`.
 */
class AttachmentPickerLayoutTest {

    private val picker: String =
        File("src/main/kotlin/com/localkeep/feature/files/AttachmentPicker.kt").readText()

    private fun slice(anchor: String, lines: Int): String {
        val at = picker.indexOf(anchor)
        require(at >= 0) { "anchor '$anchor' not found in AttachmentPicker.kt" }
        return picker.substring(at).lineSequence().take(lines).joinToString("\n")
    }

    /** Control: the slices below are bounded and reach the row they are asserting about. */
    @Test
    fun `reads the attachment row, whole and bounded`() {
        assertTrue("FileTypeTile(att.filename)" in slice("items(state.attachments", 40))
    }

    @Test
    fun `each attachment row sits inside its own group card`() {
        val items = slice("items(state.attachments", 12)
        assertTrue("LkGroup(" in items, "the row is bare again, 16dp in from the button above it")
    }

    @Test
    fun `the photo and video disclosures are drawn in one note`() {
        val note = slice("LkInlineNote(", 8)
        assertTrue("files_attachments_photo_note" in note, "the first note is not the photo/backup one")
        assertTrue("files_attachments_video_metadata_note" in note, "the video sentence is a second card again")
    }
}
