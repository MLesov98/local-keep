package com.localkeep.feature.files

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `ExifStripper` never runs on a video — `PhotoPickerImport.stageFile` only strips when
 * `imageMime != null` — and `AttachmentPickerViewModel.attach` has no video re-mux path either, so a
 * video attached here keeps its GPS/camera metadata exactly as the sibling `FileItemEditScreen`'s
 * editor already discloses for the identical reason (`files_edit_metadata_video_note`,
 * `fileMetadataNoteKindFor`). The attachment picker's own note said so only for images
 * (`files_attachments_photo_note`) and was silent about video, which reads as the image note
 * covering it too.
 *
 * Asserted on the source, matching `CameraDenialSpacingTest`'s idiom: the note is prose inside a
 * Composable, not something a JVM unit test can render and read back.
 */
class AttachmentPickerVideoMetadataNoteTest {

    private val picker: String =
        File("src/main/kotlin/com/localkeep/feature/files/AttachmentPicker.kt").readText()

    private val anchor = "stringResource(R.string.files_attachments_photo_note)"

    /** Control: the anchor exists exactly once, so the slice taken after it is unambiguous. */
    @Test
    fun `the image metadata note is present exactly once`() {
        val first = picker.indexOf(anchor)
        assertTrue(first >= 0, "anchor '$anchor' not found in AttachmentPicker.kt")
        assertTrue(first == picker.lastIndexOf(anchor), "anchor must be unique for the slice below")
    }

    @Test
    fun `a second note discloses that video metadata is kept`() {
        val start = picker.indexOf(anchor)
        val afterPhotoNote = picker.substring(start).lineSequence().take(20).joinToString("\n")
        assertTrue(
            "files_attachments_video_metadata_note" in afterPhotoNote,
            "the picker's image-only metadata note must not stand in silently for video too",
        )
    }
}
