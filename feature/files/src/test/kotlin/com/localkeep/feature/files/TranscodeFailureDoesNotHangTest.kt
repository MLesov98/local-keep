package com.localkeep.feature.files

import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.crypto.StreamingAead
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A failing encrypt half must fail the re-key, not hang it.
 *
 * `transcodeEncrypted` runs decrypt and encrypt either side of a pipe so a multi-gigabyte video is
 * never buffered whole. Its cleanup used to `join()` the decrypt thread and only then close the
 * pipe's read end — and those two lines in that order are a deadlock: when the encrypt half throws
 * (a full disk is the ordinary way), it stops draining, the decrypt thread blocks inside
 * `PipedOutputStream.write` with a full buffer, and `join()` waits for a thread that is waiting for
 * the reader `join()` is standing in the way of. A cross-vault import hung there with its spinner
 * turning, with no way out but killing the app.
 *
 * **Deliberately not `@Test(timeout = …)`.** JUnit's timeout interrupts the test thread, and
 * `Thread.join()` is interruptible — so the interrupt breaks the very deadlock the test exists to
 * catch, the call then completes, and the test reports a pass. Measured: with the fix reverted, the
 * timeout form passed after hanging for 30.9 seconds. The work runs on a daemon thread instead and
 * the assertion is on a latch, so a stuck call stays stuck and is reported as one.
 */
@RunWith(RobolectricTestRunner::class)
class TranscodeFailureDoesNotHangTest {

    private val fromDek = ByteArray(32) { it.toByte() }
    private val toDek = ByteArray(32) { (it + 100).toByte() }

    @Test
    fun `an encrypt half that cannot open its temp file fails instead of hanging`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = FileBlobStore(context)
        val blobRef = "deadlock-probe"

        // Enough plaintext to overfill the 128 KiB pipe several times, so the decrypt thread is
        // certainly blocked on a full buffer when the encrypt half gives up. A payload that fits
        // in the pipe would complete either way and prove nothing.
        val plaintext = ByteArray(512 * 1024) { (it % 251).toByte() }
        val ciphertext = ByteArrayOutputStream().also {
            StreamingAead.encrypt(fromDek, ByteArrayInputStream(plaintext), it)
        }.toByteArray()

        // Standing in for the disk-full failure: the temp path the encrypt half opens is a
        // directory, so `FileOutputStream` throws before a single byte is drained. Any failure at
        // that point leaves the pipe exactly as a full disk does.
        val blobs = File(context.filesDir, "blobs").apply { mkdirs() }
        File(blobs, "$blobRef.enc.tmp").mkdirs()

        val finished = CountDownLatch(1)
        var thrown: Throwable? = null
        // Daemon, so a genuinely deadlocked call cannot keep the JVM alive after this test fails.
        Thread {
            try {
                store.transcodeEncrypted(blobRef, ByteArrayInputStream(ciphertext), fromDek, toDek)
            } catch (t: Throwable) {
                thrown = t
            } finally {
                finished.countDown()
            }
        }.apply { isDaemon = true }.start()

        assertTrue(
            finished.await(20, TimeUnit.SECONDS),
            "transcodeEncrypted never returned — the re-key pipe deadlocked on the failure path",
        )
        assertNotNull(thrown, "the encrypt failure was swallowed rather than reported")
    }
}
