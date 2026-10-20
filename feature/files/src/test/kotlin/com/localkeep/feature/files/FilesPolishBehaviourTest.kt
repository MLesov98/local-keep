package com.localkeep.feature.files

import com.localkeep.feature.files.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Behaviour of the small decisions behind the Files & media screens, the File editor and the viewer.
 * Each is a plain function for the reason `namingSheetShouldCloseOnError` gives: this module has no
 * Compose UI test artifact, and a decision pulled out of the composable can be pinned on the JVM.
 */
class FilesPolishBehaviourTest {

    // --- header count: an empty list says "Nothing attached" once, not twice ---

    @Test
    fun `an empty attachment list prints no count under the title`() {
        assertFalse(attachmentCountShown(0))
    }

    @Test
    fun `a list with files keeps its count`() {
        assertTrue(attachmentCountShown(1))
        assertTrue(attachmentCountShown(12))
    }

    // --- video poster: only before the clip was ever started ---

    @Test
    fun `a clip that was played to the end and scrubbed back to zero does not bring the poster back`() {
        assertFalse(shouldShowVideoPoster(playing = false, positionMs = 0, hasError = false, hasStarted = true))
    }

    @Test
    fun `an unstarted clip still shows its poster`() {
        assertTrue(shouldShowVideoPoster(playing = false, positionMs = 0, hasError = false, hasStarted = false))
    }

    // --- empty file in the viewer ---

    @Test
    fun `a zero-byte file of any kind is the empty state, not No preview`() {
        assertTrue(viewerShowsEmptyState(sizeBytes = 0L, text = null))
    }

    @Test
    fun `a blank text file is the empty state, drawn as a state and not as file text`() {
        assertTrue(viewerShowsEmptyState(sizeBytes = 3L, text = "  \n"))
    }

    @Test
    fun `a file with content is not the empty state`() {
        assertFalse(viewerShowsEmptyState(sizeBytes = 5L, text = "hello"))
        assertFalse(viewerShowsEmptyState(sizeBytes = 5L, text = null))
    }

    // --- metadata disclosure ---

    @Test
    fun `an SVG gets no photo-metadata warning and no cleanup button`() {
        assertEquals(FileMetadataNoteKind.NONE, fileMetadataNoteKindFor("image/svg+xml"))
        // Control: the neighbouring raster formats keep theirs.
        assertEquals(FileMetadataNoteKind.IMAGE, fileMetadataNoteKindFor("image/png"))
    }

    @Test
    fun `a refused metadata cleanup is not an edit`() {
        val loaded = FileItemEditUiState(
            isNew = false,
            blobRef = "b",
            mime = "image/png",
            metadataCleanupState = MetadataCleanupState.PENDING,
        )
        val refused = loaded.copy(metadataCleanupState = MetadataCleanupState.FAILED)
        assertEquals(loaded.editableOnly(), refused.editableOnly())
        // Control: a cleanup that went through rewrote the blob, which is work worth warning about.
        val cleaned = loaded.copy(metadataCleanupState = MetadataCleanupState.REMOVED, blobRef = "b2")
        assertNotEquals(loaded.editableOnly(), cleaned.editableOnly())
    }

    // --- empty file card while an import is running ---

    @Test
    fun `the empty file card says it is encrypting while an import runs`() {
        val (title, body) = fileEmptyStateCopy(isWorking = true)
        assertEquals(R.string.files_encrypting_ellipsis, title)
        assertEquals(R.string.files_attachments_encrypting_note, body)
    }

    @Test
    fun `the empty file card keeps its invitation when nothing is running`() {
        assertEquals(
            R.string.files_edit_empty_title to R.string.files_edit_empty_body,
            fileEmptyStateCopy(isWorking = false),
        )
    }
}
