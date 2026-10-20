package com.localkeep.feature.files

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Regression test for 3C-3 (re-verify 1, 2026-09-21).
 *
 * The poster-frame fix drew `File.thumbnailRef` on the video surface, and on a device every
 * freshly imported video still opened black: `PhotoPickerImport.stageFile` — the one path every
 * File item comes through, gallery and document picker alike — wrote a thumbnail only on its
 * buffered image branch and left `thumbnailRef` null for the streamed video branch. The filmstrip
 * drew an `MP4` badge and the viewer had nothing to post.
 *
 * Only a *scaled* frame is registered with the retriever, and deliberately so: that is the OOM
 * half of the fix. `getFrameAtTime` would hand back the frame at full video resolution — 8 MB of
 * ARGB for 1080p, on a 48 MiB low-RAM heap — so an implementation that reached for it would find
 * no frame here and fail this test rather than pass it at the cost the fix exists to avoid.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoImportWritesThumbnailTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val videoUri: Uri = Uri.parse("content://com.localkeep.test.media/clip.mp4")
    private val otherUri: Uri = Uri.parse("content://com.localkeep.test.media/notes.txt")

    /** Answers `getType`, which is what classifies the import; everything else is unused here. */
    private class FakeProvider(private val types: Map<String, String>) : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = types[uri.lastPathSegment]
        override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
    }

    @Before
    fun setUp() {
        ShadowContentResolver.registerProviderInternal(
            "com.localkeep.test.media",
            FakeProvider(mapOf("clip.mp4" to "video/mp4", "notes.txt" to "text/plain")),
        )
        val resolver = shadowOf(context.contentResolver)
        // A supplier, not a single stream: stageFile opens the URI twice (the MIME sniff, then the
        // encrypting read) and the sniff closes what it was given.
        resolver.registerInputStreamSupplier(videoUri) { ByteArrayInputStream(ByteArray(2048)) }
        resolver.registerInputStreamSupplier(otherUri) { ByteArrayInputStream(ByteArray(16)) }

        val source = DataSource.toDataSource(context, videoUri)
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, "1920")
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, "1080")
        ShadowMediaMetadataRetriever.addScaledFrame(
            source,
            0L,
            512,
            288,
            Bitmap.createBitmap(512, 288, Bitmap.Config.ARGB_8888),
        )
    }

    @After
    fun tearDown() {
        ShadowMediaMetadataRetriever.reset()
        DataSource.reset()
    }

    private fun importWith(store: FileBlobStore) = PhotoPickerImport(context, store)

    private fun blobStore(): FileBlobStore = mock<FileBlobStore>().also {
        whenever(it.encryptToBlob(any(), any())).thenReturn("blob-1")
        whenever(it.encryptThumbnail(any(), any())).thenReturn("thumb-1")
    }

    @Test
    fun `a video staged from the document picker gets a stored thumbnail`() = runTest {
        val staged = importWith(blobStore())
            .stageFile(videoUri, ByteArray(32), fromDocumentPicker = true)
            .getOrThrow()

        assertEquals("blob-1", staged.blobRef)
        assertNotNull(staged.thumbnailRef, "a video import must store a poster frame")
        assertEquals("thumb-1", staged.thumbnailRef)
    }

    @Test
    fun `a video staged from the gallery picker gets a stored thumbnail`() = runTest {
        val staged = importWith(blobStore())
            .stageFile(videoUri, ByteArray(32), fromDocumentPicker = false)
            .getOrThrow()

        assertEquals("thumb-1", staged.thumbnailRef)
    }

    /** Control: the branch is scoped to video, not to "anything that is not an image". */
    @Test
    fun `a non-media file still stages without a thumbnail`() = runTest {
        val staged = importWith(blobStore())
            .stageFile(otherUri, ByteArray(32), fromDocumentPicker = true)
            .getOrThrow()

        assertEquals("blob-1", staged.blobRef)
        assertNull(staged.thumbnailRef)
    }
}
