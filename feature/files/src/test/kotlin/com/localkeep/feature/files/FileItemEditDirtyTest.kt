package com.localkeep.feature.files

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The discard prompt on the file editor is driven entirely by comparing two [editableOnly]
 * snapshots, so these cover the decision itself: what counts as work worth warning about, and
 * what is just view state that must not raise a prompt.
 *
 * The screen had no unsaved-change guard at all — a captured photo, a typed title and the
 * metadata toggles all went in one Back press.
 */
class FileItemEditDirtyTest {

    private val id = UUID.randomUUID()

    private fun state() = FileItemEditUiState(itemId = id)

    @Test
    fun `an untouched form is not dirty`() {
        assertEquals(state().editableOnly(), state().editableOnly())
    }

    @Test
    fun `an imported file is work worth warning about`() {
        val imported = state().copy(
            blobRef = "blob-1",
            filename = "passport.jpg",
            mime = "image/jpeg",
            sizeBytes = 82_113,
        )
        assertNotEquals(state().editableOnly(), imported.editableOnly())
    }

    @Test
    fun `each part of an import counts on its own`() {
        // Varied one at a time on purpose. The test above changes four fields together, so it stays
        // green even if one of them stops counting — which is not what it appears to assert. Found
        // by mutating `editableOnly` to drop `blobRef`: no test noticed, because `filename` was
        // still carrying the comparison. `blobRef` is the one that matters most, since it names an
        // encrypted blob already written to disk.
        assertNotEquals(state().editableOnly(), state().copy(blobRef = "blob-1").editableOnly())
        assertNotEquals(state().editableOnly(), state().copy(filename = "passport.jpg").editableOnly())
        assertNotEquals(state().editableOnly(), state().copy(mime = "image/jpeg").editableOnly())
        assertNotEquals(state().editableOnly(), state().copy(sizeBytes = 82_113).editableOnly())
        assertNotEquals(state().editableOnly(), state().copy(thumbnailRef = "thumb-1").editableOnly())
    }

    @Test
    fun `a typed title is work worth warning about`() {
        assertNotEquals(state().editableOnly(), state().copy(title = "Passport").editableOnly())
    }

    @Test
    fun `metadata cleanup and security flags are work worth warning about`() {
        assertNotEquals(
            state().editableOnly(),
            state().copy(metadataCleanupState = MetadataCleanupState.REMOVED).editableOnly(),
        )
        assertNotEquals(state().editableOnly(), state().copy(reprompt = true).editableOnly())
        assertNotEquals(
            state().editableOnly(),
            state().copy(removedFromGallery = true).editableOnly(),
        )
        assertNotEquals(
            state().editableOnly(),
            state().copy(capturedInApp = true).editableOnly(),
        )
    }

    @Test
    fun `opening the viewer or the camera is not an edit`() {
        // Both replace the form by returning early from the composable. If they registered as
        // edits, merely looking at the file you just imported would arm the discard prompt.
        assertEquals(state().editableOnly(), state().copy(showViewer = true).editableOnly())
        assertEquals(state().editableOnly(), state().copy(showCapture = true).editableOnly())
    }

    @Test
    fun `the picker's folder list and its item counts are not an edit`() {
        // The defect this pins shipped and was found on a device 2026-08-21: `baseline` is anchored
        // at construction, `init` then calls `loadFolders()`, and the counts it writes were the one
        // thing `editableOnly` did not strip — so every "New file" was dirty before its first
        // frame, and Back asked to discard a file that had never been imported. `folders` was
        // already stripped; its sibling was not.
        //
        // Invisible to `an untouched form is not dirty` above, which compares two pristine
        // snapshots and so can never see a field that only the *loaded* state carries. It also
        // cannot reproduce on a fresh install: with no foldered items the counts are empty either
        // way.
        val folder = UUID.randomUUID()
        assertEquals(
            state().editableOnly(),
            state().copy(folderItemCounts = mapOf(folder to 12)).editableOnly(),
        )
    }

    @Test
    fun `moving the file to another folder is still an edit`() {
        // The control for the test above: stripping the counts must not take the folder *choice*
        // with it, which is real work — see `editableOnly`'s own note on why `folderId` stays.
        assertNotEquals(
            state().editableOnly(),
            state().copy(folderId = UUID.randomUUID()).editableOnly(),
        )
    }

    @Test
    fun `a surfaced error is not an edit`() {
        assertEquals(
            state().editableOnly(),
            state().copy(error = R.string.files_edit_error_missing_file).editableOnly(),
        )
    }

    @Test
    fun `a save in flight is not an edit`() {
        // isWorking is transient view state. If it counted, tapping Save and then Back during the
        // encrypt would raise "discard this file?" over work that is in the middle of being kept.
        assertEquals(state().editableOnly(), state().copy(isWorking = true).editableOnly())
    }

    @Test
    fun `a completed save is not an edit`() {
        // save() re-anchors the baseline, but the flag itself must never be what makes the form
        // look dirty — otherwise backing out of a saved file would ask to discard it.
        assertEquals(state().editableOnly(), state().copy(saved = true).editableOnly())
    }

    @Test
    fun `saving anchors a baseline that the same form no longer differs from`() {
        val filled = state().copy(title = "Passport", blobRef = "blob-1", saved = true)
        val baseline = filled.editableOnly()
        assertEquals(baseline, filled.editableOnly())
        // ...and a later edit still registers against that new baseline.
        assertNotEquals(baseline, filled.copy(title = "Passport scan").editableOnly())
    }
}
