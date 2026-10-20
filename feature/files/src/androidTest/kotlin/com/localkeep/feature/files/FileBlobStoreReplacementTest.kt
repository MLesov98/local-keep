package com.localkeep.feature.files

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.crypto.EnvelopeCrypto
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/** Replacement journal behavior exercised against the real app-private filesystem. */
@RunWith(AndroidJUnit4::class)
class FileBlobStoreReplacementTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = FileBlobStore(context)

    @AfterTest
    fun cleanup() {
        File(context.filesDir, "blobs").deleteRecursively()
        File(context.filesDir, "blob-replacements").deleteRecursively()
    }

    @Test
    fun abortRemovesEveryNewArtifactAndLeavesOldRefsReadable() {
        val dek = EnvelopeCrypto.generateDek()
        val old = store.encryptToBlob(ByteArrayInputStream(byteArrayOf(1, 2, 3)), dek)
        val tx = store.beginReplacement(setOf(old))
        val newBlob = tx.stageBlob(byteArrayOf(4, 5), dek)
        val newThumbnail = tx.stageThumbnail(byteArrayOf(6), dek)

        tx.abort()

        assertTrue(store.hasBlob(old))
        assertContentEquals(byteArrayOf(1, 2, 3), store.decryptToBytes(old, dek))
        assertFalse(store.hasBlob(newBlob))
        assertFalse(store.hasBlob(newThumbnail))
    }

    @Test
    fun recoveryAbortsPreparedJournalAfterProcessDeath() {
        val dek = EnvelopeCrypto.generateDek()
        val old = store.encryptToBlob(ByteArrayInputStream(byteArrayOf(7, 8)), dek)
        val tx = store.beginReplacement(setOf(old))
        val newBlob = tx.stageBlob(byteArrayOf(9, 10), dek)
        val newThumbnail = tx.stageThumbnail(byteArrayOf(11), dek)

        // Simulate the process dying without calling abort or commit.
        store.recoverJournals()

        assertTrue(store.hasBlob(old))
        assertFalse(store.hasBlob(newBlob))
        assertFalse(store.hasBlob(newThumbnail))
    }

    @Test
    fun commitPublishesBothArtifactsBeforeTheOldRefsCanBeCollected() {
        val dek = EnvelopeCrypto.generateDek()
        val old = store.encryptToBlob(ByteArrayInputStream(byteArrayOf(12)), dek)
        val tx = store.beginReplacement(setOf(old))
        val newBlob = tx.stageBlob(byteArrayOf(13, 14), dek)
        val newThumbnail = tx.stageThumbnail(byteArrayOf(15, 16), dek)

        tx.commit()

        assertTrue(store.hasBlob(old), "old content remains available until GC sees the new item")
        assertContentEquals(byteArrayOf(13, 14), store.decryptToBytes(newBlob, dek))
        assertContentEquals(byteArrayOf(15, 16), store.decryptToBytes(newThumbnail, dek))
    }

    @Test
    fun aFinishedTransactionLeavesNoJournalBehind() {
        // Terminal journals had no reader anywhere — `recoverJournals` acts on PREPARED only, and
        // the blob GC works from vault references, not from journals — so every commit and every
        // abort left a file in `blob-replacements/` that nothing would ever remove or read. The
        // directory grew by one file per "delete all metadata" for the life of the install, and
        // every one of them was re-read on the main thread at each cold start.
        val dek = EnvelopeCrypto.generateDek()
        val journals = File(context.filesDir, "blob-replacements")

        val committed = store.beginReplacement(emptySet())
        committed.stageBlob(byteArrayOf(1), dek)
        committed.commit()

        val aborted = store.beginReplacement(emptySet())
        aborted.stageBlob(byteArrayOf(2), dek)
        aborted.abort()

        assertContentEquals(
            emptyList(),
            journals.listFiles { f -> f.name.endsWith(".journal") }.orEmpty().map { it.name },
        )
    }

    @Test
    fun recoveryClearsTheJournalItHasFinishedWith() {
        // The control for the test above: recovery must not simply stop writing journals, it must
        // remove the one it just acted on — otherwise a single interrupted replacement is re-read,
        // and its already-deleted refs re-deleted, at every launch forever.
        val dek = EnvelopeCrypto.generateDek()
        val journals = File(context.filesDir, "blob-replacements")
        val tx = store.beginReplacement(emptySet())
        val staged = tx.stageBlob(byteArrayOf(3), dek)
        assertTrue(journals.listFiles { f -> f.name.endsWith(".journal") }.orEmpty().isNotEmpty())

        store.recoverJournals()

        assertFalse(store.hasBlob(staged))
        assertContentEquals(
            emptyList(),
            journals.listFiles { f -> f.name.endsWith(".journal") }.orEmpty().map { it.name },
        )
    }
}
