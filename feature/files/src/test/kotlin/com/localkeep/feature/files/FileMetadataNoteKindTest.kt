package com.localkeep.feature.files

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression test for a 2026-09-09 finding: the File editor's metadata section was
 * `if (state.mime.startsWith("image/")) { ... }` with no `else`, so a video fell through to
 * showing nothing — no strip (`PhotoPickerImport.stageFile` never runs `ExifStripper` on
 * non-images), and no disclosure that its metadata was kept either. A user had no way to learn a
 * video's location/camera data was still there, unlike an image where the section always says one
 * of "may still carry" or "removed".
 *
 * A unit test rather than a Compose one — see `namingSheetShouldCloseOnError`'s doc comment in the
 * same file for why: this module's Compose tests are known not to reproduce every real-window
 * condition, so branch-selection logic like this is pulled into a plain function and tested there.
 */
class FileMetadataNoteKindTest {

    @Test
    fun `an image mime gets the strip-or-disclose branch`() {
        assertEquals(FileMetadataNoteKind.IMAGE, fileMetadataNoteKindFor("image/jpeg"))
        assertEquals(FileMetadataNoteKind.IMAGE, fileMetadataNoteKindFor("image/heic"))
    }

    @Test
    fun `a video mime gets its own disclosure branch, not silence`() {
        assertEquals(FileMetadataNoteKind.VIDEO, fileMetadataNoteKindFor("video/mp4"))
        assertEquals(FileMetadataNoteKind.VIDEO, fileMetadataNoteKindFor("video/quicktime"))
    }

    @Test
    fun `a format with no metadata story of its own gets no note`() {
        assertEquals(FileMetadataNoteKind.NONE, fileMetadataNoteKindFor("application/pdf"))
        assertEquals(FileMetadataNoteKind.NONE, fileMetadataNoteKindFor("audio/mpeg"))
        assertEquals(FileMetadataNoteKind.NONE, fileMetadataNoteKindFor("application/octet-stream"))
    }
}
