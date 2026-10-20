package com.localkeep.feature.files

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the platform decoder can actually read an encrypted video through [EncryptedMediaDataSource].
 *
 * This test exists because nothing else in the build can answer the question. `StreamingAeadReaderTest`
 * shows the reader returns the right bytes at the right offsets; a compile shows the types line up.
 * Neither says whether MediaExtractor and MediaMetadataRetriever — which parse the container, jump to
 * the index, and seek back into the samples — get what they need through a `MediaDataSource` that is
 * decrypting a frame at a time underneath them. That is a platform behaviour, so it is checked on a
 * device, which is the same lesson the StAX bug in this repo already taught once.
 *
 * The asset is a synthetic ffmpeg test pattern, ~200 KB, deliberately larger than
 * [com.localkeep.core.crypto.StreamingAead.CHUNK] so playback spans several encrypted frames and a
 * seek genuinely crosses a frame boundary.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedVideoPlaybackTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dek = ByteArray(32) { (it * 7).toByte() }
    private lateinit var store: FileBlobStore
    private lateinit var sourceBytes: ByteArray
    private lateinit var blobRef: String

    @Before
    fun setUp() {
        store = FileBlobStore(context)
        sourceBytes = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .context.assets.open("testpattern.mp4").use { it.readBytes() }
        blobRef = store.encryptToBlob(ByteArrayInputStream(sourceBytes), dek)
    }

    @Test
    fun theAssetSpansSeveralEncryptedFrames() {
        // If this ever stops holding, the rest of the test silently degrades into a single-frame
        // case and stops covering the thing it was written for.
        assertTrue(
            sourceBytes.size > com.localkeep.core.crypto.StreamingAead.CHUNK * 2,
            "test asset is ${sourceBytes.size} bytes — too small to cross frame boundaries",
        )
    }

    @Test
    fun retrieverReadsMetadataThroughTheEncryptedSource() {
        val retriever = MediaMetadataRetriever()
        store.openRandomAccess(blobRef, dek).use { reader ->
            retriever.setDataSource(EncryptedMediaDataSource(reader))
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)

            assertNotNull(duration, "no duration — the container was not parsed")
            // The asset is 6s; allow slack for container rounding rather than pinning an exact ms.
            assertTrue(duration.toLong() in 5_500..6_500, "duration was $duration ms")
            assertEquals("640", width)
            assertEquals("480", height)
        }
        retriever.release()
    }

    @Test
    fun decodesAFrameFromTheMiddleOfTheClip() {
        val retriever = MediaMetadataRetriever()
        store.openRandomAccess(blobRef, dek).use { reader ->
            retriever.setDataSource(EncryptedMediaDataSource(reader))
            // 3 seconds in: past the first encrypted frame, so this only succeeds if seeking back
            // and forth across frame boundaries returns coherent bytes.
            val bitmap = retriever.getFrameAtTime(3_000_000L)
            assertNotNull(bitmap, "no frame decoded at 3s")
            assertEquals(640, bitmap.width)
            assertEquals(480, bitmap.height)
        }
        retriever.release()
    }

    @Test
    fun extractorFindsAndReadsTheVideoTrack() {
        val extractor = MediaExtractor()
        store.openRandomAccess(blobRef, dek).use { reader ->
            extractor.setDataSource(EncryptedMediaDataSource(reader))
            assertTrue(extractor.trackCount > 0, "no tracks found")

            val videoTrack = (0 until extractor.trackCount).first { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            extractor.selectTrack(videoTrack)

            // Walk a few samples, then seek to the end and back to the start. Each of those is a
            // jump the reader has to serve from a different encrypted frame.
            val buffer = java.nio.ByteBuffer.allocate(1 shl 20)
            var samples = 0
            while (samples < 10 && extractor.readSampleData(buffer, 0) >= 0) {
                samples++
                extractor.advance()
            }
            assertTrue(samples > 0, "no samples readable")

            extractor.seekTo(5_000_000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            assertTrue(extractor.readSampleData(buffer, 0) > 0, "no sample after seeking near the end")

            extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            assertTrue(extractor.readSampleData(buffer, 0) > 0, "no sample after seeking back to the start")
        }
        extractor.release()
    }

    @Test
    fun playingLeavesNoPlaintextOnDisk() {
        val retriever = MediaMetadataRetriever()
        store.openRandomAccess(blobRef, dek).use { reader ->
            retriever.setDataSource(EncryptedMediaDataSource(reader))
            retriever.getFrameAtTime(1_000_000L)
        }
        retriever.release()

        // The mp4 box header is the recognisable signature of the plaintext. If any file the app can
        // write contains it, something staged a decrypted copy — which is the whole thing this
        // playback design exists to avoid.
        val needle = sourceBytes.copyOfRange(0, 32)
        val offenders = listOf(context.filesDir, context.cacheDir, context.codeCacheDir)
            .flatMap { it.walkTopDown().filter(File::isFile).toList() }
            .filter { it.length() >= needle.size && it.readBytes().containsSlice(needle) }
        assertTrue(offenders.isEmpty(), "plaintext video found on disk: $offenders")
    }

    private fun ByteArray.containsSlice(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (start in 0..size - needle.size) {
            for (i in needle.indices) if (this[start + i] != needle[i]) continue@outer
            return true
        }
        return false
    }
}
