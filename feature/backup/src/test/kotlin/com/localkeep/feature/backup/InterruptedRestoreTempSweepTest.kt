package com.localkeep.feature.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.localkeep.feature.files.FileBlobStore
import java.io.File
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A restore killed mid-blob must not leave its partial ciphertext in `blobs/` forever (S2-11,
 * 2026-10-02).
 *
 * `writeEncrypted` and `transcodeEncrypted` stage to `<ref>.enc.tmp` and only a `finally` removed it —
 * which a killed process never reaches — while the sweep listed `*.enc` alone. Lives here rather than
 * in `:feature:files` because the stage-time restore is this module's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InterruptedRestoreTempSweepTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val blobs = File(context.filesDir, "blobs").apply { mkdirs() }

    private fun file(name: String, ageMillis: Long) = File(blobs, name).apply {
        writeBytes(ByteArray(32) { 1 })
        setLastModified(System.currentTimeMillis() - ageMillis)
    }

    @Test
    fun `a stale restore temp is swept with the unreferenced blobs`() {
        val restoreTemp = file("${UUID.randomUUID()}.enc.tmp", ageMillis = HOUR)
        val replaceTemp = file(".${UUID.randomUUID()}.enc.${UUID.randomUUID()}.tmp", ageMillis = HOUR)

        FileBlobStore(context).sweepUnreferenced(emptySet())

        assertFalse(restoreTemp.exists(), "an abandoned restore temp must not outlive the sweep")
        assertFalse(replaceTemp.exists(), "an abandoned replacement temp must not outlive the sweep")
    }

    /** The control: a temp a write is still filling is protected by the same grace as a fresh blob. */
    @Test
    fun `a temp still being written is left alone`() {
        val live = file("${UUID.randomUUID()}.enc.tmp", ageMillis = 0)
        val referenced = UUID.randomUUID().toString()
        val blob = file("$referenced.enc", ageMillis = HOUR)

        FileBlobStore(context).sweepUnreferenced(setOf(referenced))

        assertTrue(live.exists(), "a write in flight must keep its temp")
        assertTrue(blob.exists(), "a referenced blob must survive")
    }

    private companion object {
        const val HOUR = 60L * 60L * 1_000L
    }
}
