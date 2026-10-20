package com.localkeep.feature.files

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

/**
 * S3-06, 2026-10-02: the attachment import handed the *provider's* MIME to the sanitizer as its
 * hint, and the sanitizer treats any disagreement with the sniffed format as `MIME_MISMATCH` -- so a
 * WebP or PNG saved with a `.jpg` name (reported as `image/jpeg`), or an `image/avif`, was refused
 * with "take it again with your camera set to JPEG". The stripper works from the bytes either way;
 * the refusal bought no safety and gave advice that cannot help.
 *
 * Native graphics so that a real PNG is encoded and its bounds are read back by the platform
 * decoder, as they are on a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportTrustsBytesOverProviderMimeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val mislabelled = Uri.parse("content://com.localkeep.test.mime/photo.jpg")

    /** Answers `getType`, which is the provider's claim; everything else is unused here. */
    private class FakeProvider(private val types: Map<String, String>) : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = types[uri.lastPathSegment]
        override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
    }

    private fun pngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out), "fixture failed to encode")
        bitmap.recycle()
        return out.toByteArray()
    }

    @Before
    fun setUp() {
        // A PNG whose name ends .jpg, so the provider reports image/jpeg.
        ShadowContentResolver.registerProviderInternal(
            "com.localkeep.test.mime",
            FakeProvider(mapOf("photo.jpg" to "image/jpeg")),
        )
        val png = pngBytes()
        shadowOf(context.contentResolver)
            .registerInputStreamSupplier(mislabelled) { ByteArrayInputStream(png) }
    }

    private fun blobStore(): FileBlobStore = mock<FileBlobStore>().also {
        whenever(it.encryptToBlob(any(), any())).thenReturn("blob-1")
    }

    @Test
    fun `a PNG that the provider calls image jpeg is imported as the PNG it is`() = runTest {
        val result = PhotoPickerImport(context, blobStore())
            .importAttachment(mislabelled, ByteArray(32))

        val success = result as? AttachmentImportResult.Success
        assertTrue(success != null, "expected the import to succeed, got $result")
        assertEquals("image/png", success.attachment.mime)
    }

    @Test
    fun `a File item staged from a mislabelled PNG can still have its metadata removed`() = runTest {
        val store = blobStore()
        val transaction = mock<BlobReplacementTransaction>()
        whenever(store.beginReplacement(any())).thenReturn(transaction)
        whenever(transaction.stageBlob(any(), any())).thenReturn("blob-2")
        val importer = PhotoPickerImport(context, store)
        val staged = StagedFileSource(
            blobRef = "blob-1",
            thumbnailRef = null,
            filename = "photo.jpg",
            mime = "image/jpeg", // the provider's claim, as stageFile stores it
            sizeBytes = 0,
            generationId = java.util.UUID.randomUUID(),
            metadataState = MetadataCleanupState.PENDING,
            sourceUri = mislabelled,
            capturedInApp = false,
            removedFromGallery = false,
            pendingGalleryDeleteUri = null,
        )

        val replaced = importer.replaceStagedFile(staged, pngBytes(), ByteArray(32))

        assertTrue(replaced.isSuccess, "replaceStagedFile failed: ${replaced.exceptionOrNull()}")
        assertEquals(MetadataCleanupState.REMOVED, replaced.getOrThrow().metadataState)
        assertEquals("image/png", replaced.getOrThrow().mime)
    }

    /**
     * AVIF is a HEIF-family container -- `isHeif` accepts the brand -- but its hint was not in the
     * HEIF set, so `image/avif` could never match its own format. The bytes here are not a decodable
     * picture, so the sanitizer fails later for that reason; what matters is that it no longer
     * fails *as a mismatch*.
     */
    @Test
    fun `an avif hint is not a mismatch for avif bytes`() {
        val avif = byteArrayOf(0, 0, 0, 16) + "ftypavif".toByteArray(Charsets.US_ASCII) + ByteArray(4)

        val result = ExifStripper.sanitize(avif, "image/avif")

        assertNotEquals(SanitizerResult.Failed(SanitizerFailureReason.MIME_MISMATCH), result)
    }

    /** Control: the sanitizer's own contract is unchanged -- a hint that contradicts the bytes is refused. */
    @Test
    fun `sanitize still refuses a hint that contradicts the bytes`() {
        assertEquals(
            SanitizerResult.Failed(SanitizerFailureReason.MIME_MISMATCH),
            ExifStripper.sanitize(pngBytes(), "image/jpeg"),
        )
    }
}
