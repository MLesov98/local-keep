package com.localkeep.feature.files

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The filmstrip draws the thumbnail the import already stored.
 *
 * Red before the fix. Every photo and video import writes a downsampled JPEG beside the original
 * and records it as `thumbnailRef` — `PhotoPickerImport` encrypts it, `BlobReKey` re-keys it on
 * every DEK rotation, `SchemaContract` reserves it a field number, and a snapshot carries it — and
 * no screen in the app ever read one back. The strip decrypted and decoded the **full** blob
 * instead, for every entry in a seventeen-wide window, to draw pictures 128px on a side.
 *
 * So the cost was paid twice and collected once: storage and re-key work for a blob nothing used,
 * and tens of megabytes of AEAD plus JPEG decoding on each page change to reproduce it.
 *
 * Asserted against the source. `FileBlobStore` needs a Context and a real encrypted blob on disk,
 * which is an instrumented test; what this defect actually is — which ref the strip asks for — is a
 * property of the call site, and that is readable here.
 */
class FilmstripUsesStoredThumbnailTest {

    private val source = File("src/main/kotlin/com/localkeep/feature/files/SecureMediaViewer.kt")

    private val filmstripEffect: String
        get() {
            if (!source.isFile) {
                fail("cannot find ${source.absolutePath} — this test reads the source it guards")
            }
            val anchor = "val keep = filmstripKeepWindow(attachments, currentIndex)"
            val text = source.readText()
            assertTrue(
                anchor in text,
                "cannot find `$anchor` in SecureMediaViewer.kt — update it, or this test scans the " +
                    "whole file and asserts nothing",
            )
            return text.substringAfter(anchor).substringBefore("\n    // Decrypt content off the main")
        }

    @Test
    fun `the strip asks for the stored thumbnail before the full blob`() {
        val effect = filmstripEffect
        val thumb = effect.indexOf("att.thumbnailRef")
        val full = effect.indexOf("decryptToBytes(att.blobRef")
        assertTrue(
            thumb >= 0,
            "the filmstrip must read the thumbnail the import stored; found:\n$effect",
        )
        assertTrue(
            full > thumb,
            "the full blob must be the fallback, not the first choice — otherwise the stored " +
                "thumbnail is still dead weight",
        )
    }

    @Test
    fun `an unusable thumbnail falls back instead of losing the picture`() {
        val text = source.readText()
        assertTrue(
            "hasBlob" in text && "runCatching { fileBlobStore.decryptToBytes(it, dek) }" in text,
            "`decryptToBytes` throws on a blob that is not there, so a dangling thumbnailRef must " +
                "be answered with the full blob rather than with the viewer's catch-all — an item " +
                "imported before thumbnails existed has none at all",
        )
    }

    /**
     * The control, and it must stay green while the two above go red.
     *
     * The cache and its eviction window are keyed on `blobRef`: `filmstripKeepWindow` returns
     * blobRefs, and the loop skips an attachment whose blobRef is outside it. Re-keying the map on
     * the thumbnail ref while the window still speaks blobRefs would make every lookup miss and
     * every eviction a no-op — a strip that decodes on every frame and never releases anything,
     * which is the unbounded cache this window exists to prevent, wearing the fix's name.
     */
    @Test
    fun `the thumbnail cache is still keyed and evicted by blobRef`() {
        val effect = filmstripEffect
        assertTrue(
            "if (att.blobRef !in keep) continue" in effect,
            "the keep window is computed over blobRefs and must still be applied to them",
        )
        assertTrue(
            "thumbnails[att.blobRef] != null" in effect &&
                "thumbnails + (att.blobRef to bitmap.asImageBitmap())" in effect,
            "the cache must stay keyed on blobRef, or every lookup misses and nothing is evicted",
        )
    }
}
