package com.localkeep.feature.files

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.crypto.EnvelopeCrypto
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * The two ways restoring a backup used to lose media, both exercised against real files.
 *
 * These are instrumented rather than JVM tests because [FileBlobStore] is defined entirely by what
 * it puts on disk, and the bugs were about what happens to bytes that are already there.
 */
@RunWith(AndroidJUnit4::class)
class FileBlobStoreRestoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = FileBlobStore(context)

    @AfterTest
    fun cleanup() {
        File(context.filesDir, "blobs").deleteRecursively()
    }

    @Test
    fun corruptExistingBlobIsReplacedByTheRestoredCopy() {
        val dek = EnvelopeCrypto.generateDek()
        // Larger than one 64 KiB StreamingAead frame, so the damage lands mid-stream rather than
        // in a header the reader might reject for unrelated reasons.
        val plaintext = Random(7).nextBytes(200_000)
        val ref = store.encryptToBlob(ByteArrayInputStream(plaintext), dek)
        val goodCiphertext = store.openEncrypted(ref).use { it.readBytes() }

        flipAByteInsideTheBlob(ref)
        assertTrue(store.hasBlob(ref), "the damaged file is still present — that was the trap")
        assertFalse(store.authenticates(ref, dek))

        store.writeEncrypted(ref, ByteArrayInputStream(goodCiphertext))

        assertTrue(store.authenticates(ref, dek))
        assertContentEquals(plaintext, store.decryptToBytes(ref, dek))
    }

    @Test
    fun aBlobFromAnotherVaultIsRekeyedRatherThanDropped() {
        val exportDek = EnvelopeCrypto.generateDek()
        val localDek = EnvelopeCrypto.generateDek()
        // Spans several frames: the re-key runs the two halves either side of a pipe, and a
        // single-frame payload would never exercise the blocking that makes that necessary.
        val plaintext = Random(11).nextBytes(300_000)

        val exportRef = store.encryptToBlob(ByteArrayInputStream(plaintext), exportDek)
        val exportCiphertext = store.openEncrypted(exportRef).use { it.readBytes() }
        store.deleteBlob(exportRef)

        store.transcodeEncrypted(
            blobRef = exportRef,
            input = ByteArrayInputStream(exportCiphertext),
            fromDek = exportDek,
            toDek = localDek,
        )

        // The point of the fix: the item's blobRef now resolves, under THIS vault's key.
        assertTrue(store.authenticates(exportRef, localDek))
        assertContentEquals(plaintext, store.decryptToBytes(exportRef, localDek))
        assertFalse(store.authenticates(exportRef, exportDek))
    }

    /** A truncated or wrong-key source must fail loudly, not leave a short blob behind. */
    @Test
    fun aRekeyThatCannotDecryptItsSourceFailsAndWritesNothing() {
        val exportDek = EnvelopeCrypto.generateDek()
        val wrongDek = EnvelopeCrypto.generateDek()
        val localDek = EnvelopeCrypto.generateDek()
        val plaintext = Random(13).nextBytes(200_000)

        val ref = store.encryptToBlob(ByteArrayInputStream(plaintext), exportDek)
        val ciphertext = store.openEncrypted(ref).use { it.readBytes() }
        store.deleteBlob(ref)

        assertFailsWith<Exception> {
            store.transcodeEncrypted(ref, ByteArrayInputStream(ciphertext), wrongDek, localDek)
        }

        assertFalse(store.hasBlob(ref), "a failed re-key must not leave a partial blob behind")
    }

    /**
     * A failed encrypt must not leave an orphaned partial blob under an unreferenceable UUID.
     *
     * `encryptToBlob` mints its own blob id and only returns it on success, so an interrupted
     * encrypt (disk full, killed process) used to leave a partial `.enc` file that nothing could
     * ever reference or clean up promptly — it would sit past `UNREFERENCED_GRACE_MILLIS` (10
     * minutes) before the unlock sweep could even consider it, worst case persisting across the
     * very low-disk session that caused it.
     */
    @Test
    fun aFailedEncryptDeletesItsPartialBlob() {
        val dek = EnvelopeCrypto.generateDek()
        val blobsDir = File(context.filesDir, "blobs")
        blobsDir.mkdirs()
        val before = blobsDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()

        val boom = object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("simulated encrypt failure")
        }

        assertFailsWith<Exception> { store.encryptToBlob(boom, dek) }

        val after = blobsDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        assertEquals(before, after, "a failed encrypt must not leave a partial blob file behind")
    }

    private fun flipAByteInsideTheBlob(ref: String) {
        val file = File(File(context.filesDir, "blobs"), "$ref.enc")
        RandomAccessFile(file, "rw").use { raf ->
            val at = file.length() / 2
            raf.seek(at)
            val b = raf.readByte()
            raf.seek(at)
            raf.writeByte(b.toInt() xor 0x5A)
        }
    }
}
