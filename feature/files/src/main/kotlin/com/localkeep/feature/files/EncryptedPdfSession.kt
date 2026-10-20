package com.localkeep.feature.files

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import com.localkeep.core.crypto.StreamingAeadReader
import java.io.Closeable
import kotlin.math.min

/**
 * A [PdfRenderer] reading an encrypted blob, with no plaintext copy anywhere.
 *
 * `PdfRenderer` needs a *seekable* file descriptor, which rules out the obvious shortcut of
 * decrypting into a byte array — and rules out the other obvious one, writing the decrypted PDF to
 * `cacheDir`, which puts the whole document on disk in the clear where every backup agent can reach
 * it. That is the thing this feature exists not to do.
 *
 * So the descriptor is a *proxy*: [StorageManager.openProxyFileDescriptor] routes each read back
 * here, and the read decrypts exactly the frames it covers through the same [StreamingAeadReader]
 * that already backs video playback. The plaintext in existence at any moment is one buffer's worth,
 * in this process; the renderer sees an ordinary file.
 *
 * Separate from the composable so the platform question — can PdfRenderer actually parse a document
 * through a proxy descriptor that decrypts underneath it? — can be asked on a device by a test.
 * That is the same reason [EncryptedMediaDataSource] has one.
 */
internal class EncryptedPdfSession private constructor(
    val renderer: PdfRenderer,
    private val descriptor: ParcelFileDescriptor,
    private val reader: StreamingAeadReader,
    private val thread: HandlerThread,
) : Closeable {

    val pageCount: Int get() = renderer.pageCount

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
        runCatching { reader.close() }
        thread.quitSafely()
    }

    companion object {
        /** Throws if the blob cannot be decrypted, or is not a PDF this renderer will open. */
        fun open(
            context: Context,
            fileBlobStore: FileBlobStore,
            blobRef: String,
            dek: ByteArray,
        ): EncryptedPdfSession {
            val storage = context.getSystemService(StorageManager::class.java)
            // Reads land on this thread, never the main one: each one decrypts.
            val thread = HandlerThread("pdf-proxy").apply { start() }
            var reader: StreamingAeadReader? = null
            var descriptor: ParcelFileDescriptor? = null
            try {
                val source = fileBlobStore.openRandomAccess(blobRef, dek)
                reader = source
                descriptor = storage.openProxyFileDescriptor(
                    ParcelFileDescriptor.MODE_READ_ONLY,
                    object : ProxyFileDescriptorCallback() {
                        override fun onGetSize(): Long = source.plaintextLength

                        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                            if (offset >= source.plaintextLength) return 0
                            val wanted = min(size.toLong(), source.plaintextLength - offset).toInt()
                            var read = 0
                            // readAt is free to return a short read at a frame boundary; the
                            // descriptor contract is to fill what was asked for.
                            while (read < wanted) {
                                val n = source.readAt(offset + read, data, read, wanted - read)
                                if (n <= 0) break
                                read += n
                            }
                            return read
                        }

                        override fun onRelease() = Unit
                    },
                    Handler(thread.looper),
                )
                return EncryptedPdfSession(PdfRenderer(descriptor), descriptor, source, thread)
            } catch (e: Throwable) {
                runCatching { descriptor?.close() }
                runCatching { reader?.close() }
                thread.quitSafely()
                throw e
            }
        }
    }
}
