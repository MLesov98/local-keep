package com.localkeep.feature.files

import android.content.Context
import android.graphics.Bitmap
import com.localkeep.core.crypto.StreamingAead
import com.localkeep.core.crypto.StreamingAeadReader
import com.localkeep.core.vault.BlobReKeyStore
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val BLOB_DIR = "blobs"

/** One StreamingAead frame plus headroom, so neither half of a re-key pipe stalls the other. */
private const val PIPE_BUFFER_BYTES = 128 * 1024

/** Discards everything written to it — for decrypting purely to find out whether it works. */
private object NullSink : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}

/** How long a blob is protected from the sweep after it is written. See [sweepUnreferenced]. */
private const val UNREFERENCED_GRACE_MILLIS = 10L * 60L * 1_000L
private const val THUMBNAIL_JPEG_QUALITY = 80
private const val REPLACEMENT_DIR = "blob-replacements"

enum class BlobJournalState { PREPARED, COMMITTED, ABORTED }

/** A two-artifact replacement whose old refs remain valid until both new refs are staged. */
class BlobReplacementTransaction internal constructor(
    private val store: FileBlobStore,
    private val journalId: String,
    private val oldRefs: Set<String>,
) {
    private val newRefs = linkedSetOf<String>()
    private var finished = false

    fun stageBlob(bytes: ByteArray, dek: ByteArray): String = stage(bytes, dek)

    fun stageThumbnail(bytes: ByteArray, dek: ByteArray): String = stage(bytes, dek)

    // Both terminal paths *delete* the journal rather than rewriting it into a terminal state.
    // Nothing reads a terminal journal: recovery branches on PREPARED only, and BlobGarbageCollector
    // reclaims unreferenced blobs from the vault's own references, never from a journal. Rewriting
    // therefore left one file per replacement in `blob-replacements/` that no code would ever remove
    // or read — the directory grew for the life of the install, and every file in it was re-read on
    // the main thread at each cold start. Deleting is what "this transaction is over" means, and the
    // crash-safety argument is unchanged: clearing the PREPARED journal is the whole point, and a
    // delete clears it at least as well as an overwrite.
    fun commit() {
        check(!finished) { "replacement transaction already finished" }
        check(newRefs.isNotEmpty()) { "replacement has no staged artifacts" }
        store.deleteJournal(journalId)
        finished = true
    }

    fun abort() {
        if (finished) return
        // Blobs first, journal second: a crash in between leaves a PREPARED journal that recovery
        // re-runs, and deleting an already-deleted ref is a no-op. The other order would strand them.
        newRefs.forEach(store::deleteBlob)
        store.deleteJournal(journalId)
        finished = true
    }

    private fun stage(bytes: ByteArray, dek: ByteArray): String {
        check(!finished) { "replacement transaction already finished" }
        return store.encryptToBlob(ByteArrayInputStream(bytes), dek).also {
            newRefs += it
            // Keep the PREPARED journal truthful after every artifact. A process death between
            // the blob and thumbnail writes must still give startup recovery the exact refs to
            // remove; recording them only in memory would turn a crash into a permanent orphan.
            store.writeJournal(journalId, BlobJournalState.PREPARED, oldRefs, newRefs)
        }
    }
}

/**
 * Encrypted blob I/O for vault files/media (§5.5). Encrypts via [StreamingAead] (chunked, truncation-resistant —
 * P4). Decrypts ONLY into memory — no read API here returns a file path or a writable stream, by construction, so
 * a caller cannot accidentally stage plaintext on disk even by misusing it. That holds for the seekable
 * [openRandomAccess] too: it decrypts a frame at a time into RAM and hands back a reader, never a location.
 * The session DEK is supplied by the caller (obtained from [VaultLockManager] via [currentDekOrThrow]) —
 * this class holds no key material itself.
 */
@Singleton
class FileBlobStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : BlobReKeyStore {
    /** Creates a journal before either replacement artifact is written. */
    fun beginReplacement(oldRefs: Set<String>): BlobReplacementTransaction {
        val id = UUID.randomUUID().toString()
        writeJournal(id, BlobJournalState.PREPARED, oldRefs, emptySet())
        return BlobReplacementTransaction(this, id, oldRefs)
    }

    /** Aborts staged refs from a process death before the vault can reference them. */
    fun recoverJournals() {
        val dir = File(context.filesDir, REPLACEMENT_DIR)
        dir.listFiles { file -> file.isFile && file.name.endsWith(".journal") }
            .orEmpty()
            .forEach { journal ->
                val lines = runCatching { journal.readLines() }.getOrNull().orEmpty()
                val state = lines.firstOrNull()?.substringAfter('=', "")
                    ?.let { runCatching { BlobJournalState.valueOf(it) }.getOrNull() }
                if (state == BlobJournalState.PREPARED) {
                    lines.filter { it.startsWith("new=") }
                        .map { it.removePrefix("new=") }
                        .filter(String::isNotBlank)
                        .forEach(::deleteBlob)
                }
                // Then drop it, whatever it said. A PREPARED journal has just been acted on and has
                // nothing left to say; a terminal one is a leftover from before terminal journals
                // were deleted, and re-reading it at every launch is all it would otherwise do.
                journal.delete()
            }
    }

    /** Streams [input] through [StreamingAead.encrypt] into a new app-private `.enc` file. Never fully buffers
     *  [input] in memory — safe for arbitrarily large files/videos (§5.5). Returns the new blobRef. */
    fun encryptToBlob(input: InputStream, dek: ByteArray): String {
        val blobId = UUID.randomUUID().toString()
        val blobFile = blobFile(blobId)
        blobFile.parentFile?.mkdirs()
        try {
            FileOutputStream(blobFile).use { out -> StreamingAead.encrypt(dek, input, out) }
        } catch (t: Throwable) {
            // blobId escapes only via the return value below, so a failure here leaves an
            // unreferenceable orphan under a name nothing else will ever know to sweep for
            // (see UNREFERENCED_GRACE_MILLIS) until the *next* interrupted write reuses the name,
            // which a random UUID never does. Delete it now instead of leaking the partial bytes.
            blobFile.delete()
            throw t
        }
        return blobId
    }

    /**
     * Replaces an existing ciphertext in place. The old blob remains readable until the complete
     * replacement has been encrypted and fsynced, so metadata cleanup cannot leave a live vault
     * item pointing at a truncated or half-written file after a crash.
     */
    fun replaceBlob(blobRef: String, input: InputStream, dek: ByteArray) {
        val target = blobFile(blobRef)
        require(target.isFile) { "blob does not exist: $blobRef" }
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { out ->
                StreamingAead.encrypt(dek, input, out)
                out.fd.sync()
            }
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (t: Throwable) {
            temp.delete()
            throw t
        } finally {
            temp.delete()
        }
    }

    /**
     * Decrypts [blobRef] fully into memory. RAM ONLY — never writes a byte of plaintext to disk.
     * Throws [com.localkeep.core.crypto.AeadAuthException] if the blob was tampered with or truncated.
     */
    fun decryptToBytes(blobRef: String, dek: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        FileInputStream(blobFile(blobRef)).use { input -> StreamingAead.decrypt(dek, input, out) }
        return out.toByteArray()
    }

    /**
     * Streams the plaintext of [blobRef] into [out], one frame at a time.
     *
     * The size-safe counterpart to [decryptToBytes], for a caller that has somewhere to put the
     * bytes rather than needing them all at once — restoring to the gallery, above all. Videos are
     * deliberately uncapped on import because they stream in; they have to stream back out too, or
     * the one path that buffers a whole blob is the one handling the largest files there are.
     */
    fun decryptToStream(blobRef: String, dek: ByteArray, out: OutputStream) {
        FileInputStream(blobFile(blobRef)).use { input -> StreamingAead.decrypt(dek, input, out) }
    }

    /** Same in-memory decrypt as [decryptToBytes], exposed as a `Stream` for callers that want that shape
     *  (e.g. handing to a decoder that takes an `InputStream`). Backed by a `ByteArrayInputStream` — still
     *  memory only, never a file path. */
    fun openDecryptingSource(blobRef: String, dek: ByteArray): InputStream =
        ByteArrayInputStream(decryptToBytes(blobRef, dek))

    /**
     * A seekable decrypting view of [blobRef], for a decoder that reads by offset rather than
     * start-to-finish — in practice, video playback (see [EncryptedMediaDataSource]).
     *
     * Still no file path and still no plaintext on disk: the reader decrypts one 64 KiB frame at a
     * time into RAM, so this is the only read API here that is safe on a blob too large to hold in
     * memory. The caller owns the returned reader and must `close()` it — it holds an open file
     * descriptor and a derived key until then.
     */
    fun openRandomAccess(blobRef: String, dek: ByteArray): StreamingAeadReader =
        StreamingAeadReader(dek, RandomAccessFile(blobFile(blobRef), "r"))

    /** JPEG-compresses [bitmap] in memory, encrypts the compressed bytes, and recycles [bitmap]. */
    fun encryptThumbnail(bitmap: Bitmap, dek: ByteArray): String {
        return encryptToBlob(ByteArrayInputStream(thumbnailJpegBytes(bitmap)), dek)
    }

    /** Encodes a thumbnail without touching disk; the caller owns the returned bytes. */
    fun thumbnailJpegBytes(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_JPEG_QUALITY, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /** Encrypted (on-disk) size — use this to sanity-check before calling [decryptToBytes] on something huge. */
    fun blobSizeBytes(blobRef: String): Long =
        if (isWellFormedRef(blobRef)) blobFile(blobRef).length() else 0L

    override fun hasBlob(blobRef: String): Boolean = isWellFormedRef(blobRef) && blobFile(blobRef).isFile

    /**
     * Decrypts [blobRef] under [dek] and discards the plaintext, reporting only whether it
     * authenticated. See [BlobReKeyStore.opensUnder] for why the re-key pass needs to ask.
     *
     * The output goes nowhere — no file, no buffer that outlives the call — so this never stages
     * plaintext anywhere, on disk or in memory, which is the same constraint `reKeyBlob` is held
     * to. It does read the whole blob: AES-GCM's tag is at the end, so there is no cheaper honest
     * answer, and this runs only on a blob whose re-key has already failed rather than on the
     * ordinary path.
     */
    override fun opensUnder(blobRef: String, dek: ByteArray): Boolean = runCatching {
        FileInputStream(blobFile(blobRef)).use { input ->
            StreamingAead.decrypt(dek, input, NullOutputStream)
        }
        true
    }.getOrDefault(false)

    /** Discards everything written to it. Used only by [opensUnder]. */
    private object NullOutputStream : java.io.OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    /**
     * The blob's raw ciphertext, exactly as stored.
     *
     * For copying a blob into or out of a backup without decrypting it. Both export modes keep the
     * same DEK — the passphrase export re-*wraps* it rather than replacing it — so the stored bytes
     * remain openable by the exported vault, and no key is needed on either side of the copy. The
     * caller closes the stream.
     */
    fun openEncrypted(blobRef: String): InputStream = FileInputStream(blobFile(blobRef))

    /**
     * Stores raw ciphertext under [blobRef], as produced by [openEncrypted]. Used by restore.
     *
     * Written to a temp file and renamed, so an interrupted restore cannot leave a truncated blob
     * that would later fail to authenticate and look like tampering.
     */
    fun writeEncrypted(blobRef: String, input: InputStream) {
        val target = blobFile(blobRef)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(temp).use { out ->
                input.copyTo(out)
                // Force the bytes to the platter before the rename, matching AtomicVaultWriter /
                // RollingBackupManager / DeviceKeyStore. Without this the rename can be durable while
                // the data behind it is not, so a crash/power-loss leaves a ref pointing at a
                // truncated blob that later fails to authenticate and reads as tampering.
                out.fd.sync()
            }
            if (!temp.renameTo(target)) error("could not finalize restored blob $blobRef")
        } finally {
            temp.delete()
        }
    }

    /**
     * True when [blobRef] exists and decrypts cleanly under [dek], end to end.
     *
     * Restore used to write a blob only when none was present locally, on the reasoning that a
     * blob already there must be the same blob. That is true of its *name* and says nothing about
     * its bytes: a write interrupted before the rename, a filesystem that lost a block, a partially
     * synced app-data backup all leave a file under the right ref that will not authenticate. The
     * restore then walked past the one intact copy in existence and left the damaged one in place.
     *
     * Costs a full decrypt pass, which is why only restore calls it — never a read path.
     */
    fun authenticates(blobRef: String, dek: ByteArray): Boolean =
        hasBlob(blobRef) &&
            runCatching {
                FileInputStream(blobFile(blobRef)).use { input ->
                    StreamingAead.decrypt(dek, input, NullSink)
                }
            }.isSuccess

    /**
     * Re-keys [input]'s ciphertext from [fromDek] to [toDek] and stores it under [blobRef].
     *
     * This is what makes media survive an import from a *different* vault. Blobs are encrypted
     * under the vault's DEK, so another vault's bytes are meaningless here — and the previous
     * behaviour was to drop them on the floor while [VaultImporter] happily merged in the items
     * that referenced them, producing an import that looked complete and was full of attachments
     * that would not open. Silent, and unrecoverable once the source vault was gone.
     *
     * Plaintext never touches the disk: the two halves run either side of a pipe, one frame at a
     * time, which is also what keeps a multi-gigabyte video from being buffered in RAM. The
     * ciphertext lands on a temp file and is renamed, so an interruption cannot leave a truncated
     * blob behind.
     */
    fun transcodeEncrypted(
        blobRef: String,
        input: InputStream,
        fromDek: ByteArray,
        toDek: ByteArray,
    ) {
        val target = blobFile(blobRef)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        try {
            val plaintextSink = PipedOutputStream()
            val plaintextSource = PipedInputStream(plaintextSink, PIPE_BUFFER_BYTES)
            // The decrypt half runs on its own thread because both halves block on the pipe: doing
            // them in sequence would need the whole plaintext somewhere first, which is the one
            // thing this must not do.
            var decryptFailure: Throwable? = null
            val decrypting = Thread {
                try {
                    plaintextSink.use { out -> StreamingAead.decrypt(fromDek, input, out) }
                } catch (t: Throwable) {
                    decryptFailure = t
                    runCatching { plaintextSink.close() }
                }
            }
            decrypting.start()
            try {
                FileOutputStream(temp).use { out ->
                    StreamingAead.encrypt(toDek, plaintextSource, out)
                    // Durable before the rename — see writeEncrypted. A re-keyed blob that survives
                    // the rename but not the write would fail to authenticate on the very next open.
                    out.fd.sync()
                }
            } finally {
                // Close the read end BEFORE joining, not after. These two lines were the other way
                // round, and the order is the whole difference between a reported failure and a
                // hung import: when the encrypt half throws — a full disk is the ordinary way —
                // it stops draining the pipe, and the decrypt thread is left blocked inside
                // `PipedOutputStream.write` with a full buffer. `join()` then waits for a thread
                // that is waiting for the reader that `join()` is standing in the way of.
                // `PipedInputStream.close` is what breaks it: the blocked writer wakes on its own
                // one-second poll, sees `closedByReader`, and throws "Pipe closed".
                runCatching { plaintextSource.close() }
                decrypting.join()
            }
            // Checked after the encrypt half, not instead of it: a decrypt that fails partway
            // still produces a well-formed shorter ciphertext, so without this the blob would be
            // silently truncated rather than reported.
            decryptFailure?.let { throw it }
            if (!temp.renameTo(target)) error("could not finalize re-keyed blob $blobRef")
        } finally {
            temp.delete()
        }
    }

    /**
     * [BlobReKeyStore] — what a DEK rotation calls for each stored blob.
     *
     * Reads the live ciphertext and writes the re-keyed one through [transcodeEncrypted], which
     * renames a completed temp file over the target. Holding the read descriptor across that
     * rename is safe and deliberate: the rename replaces the directory entry, and this stream
     * keeps reading the inode it already opened, so the old bytes stay whole until the new ones
     * are durable. A crash anywhere in between leaves the original blob intact.
     */
    override fun reKeyBlob(blobRef: String, fromDek: ByteArray, toDek: ByteArray) {
        openEncrypted(blobRef).use { transcodeEncrypted(blobRef, it, fromDek, toDek) }
    }

    fun deleteBlob(blobRef: String) {
        if (isWellFormedRef(blobRef)) blobFile(blobRef).delete()
    }

    /** Removes a journal whose transaction is over. See [BlobReplacementTransaction.commit]. */
    internal fun deleteJournal(journalId: String) {
        File(File(context.filesDir, REPLACEMENT_DIR), "$journalId.journal").delete()
    }

    internal fun writeJournal(
        journalId: String,
        state: BlobJournalState,
        oldRefs: Set<String>,
        newRefs: Set<String>,
    ) {
        val dir = File(context.filesDir, REPLACEMENT_DIR).apply { mkdirs() }
        val target = File(dir, "$journalId.journal")
        val temp = File(dir, "$journalId.tmp")
        try {
            FileOutputStream(temp).use { out ->
                out.write("state=$state\n".toByteArray())
                oldRefs.forEach { out.write("old=$it\n".toByteArray()) }
                newRefs.forEach { out.write("new=$it\n".toByteArray()) }
                out.fd.sync()
            }
            if (!temp.renameTo(target)) error("could not finalize blob replacement journal")
        } finally {
            temp.delete()
        }
    }

    /**
     * Deletes every `.enc` blob NOT in [referencedRefs]. The safe way to reclaim blobs orphaned by
     * item delete / trash purge / a photo replaced before save — deleting only what nothing points
     * at, rather than eagerly deleting on each mutation where a still-referenced blob could be
     * dropped. Returns the number deleted.
     */
    fun sweepUnreferenced(referencedRefs: Set<String>): Int {
        val dir = File(context.filesDir, BLOB_DIR)
        val cutoff = System.currentTimeMillis() - UNREFERENCED_GRACE_MILLIS
        // 2026-10-02: staging temps too (`<ref>.enc.tmp`, `.<ref>.enc.<uuid>.tmp`). Only a `finally`
        // removed them, which a killed process never reaches, and nothing else lists them — a restore
        // interrupted mid-blob left its partial ciphertext here for good. Same age guard as the blobs
        // below: a write in flight keeps touching its temp, so only an abandoned one goes stale.
        dir.listFiles { f -> f.isFile && f.name.endsWith(".tmp") && f.lastModified() <= cutoff }
            ?.forEach { it.delete() }
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".enc") } ?: return 0
        var deleted = 0
        for (f in files) {
            val blobId = f.name.removeSuffix(".enc")
            if (blobId in referencedRefs) continue
            // Age guard. Every write here is "blob first, vault item second", so between those two
            // steps a blob is legitimately unreferenced — and the sweep runs on unlock, which is
            // exactly what happens if an import or an attach is interrupted by the auto-lock and
            // then resumed. Without this the sweep deleted the bytes the half-finished operation
            // had just written, and the user got items pointing at nothing.
            //
            // Ten minutes rather than a flag, because the dangerous case is a *killed* process,
            // where no in-memory "import in progress" guard would survive to protect anything.
            // A genuine orphan simply waits for the next sweep.
            if (f.lastModified() > cutoff) continue
            if (f.delete()) deleted++
        }
        return deleted
    }

    /**
     * 2026-10-02: a ref is a file name, and refs reach this class from files the user picked — a
     * `.vault` bundle's blob records and an imported vault's own items. `../vault` resolved to
     * `filesDir/vault.enc`, and a restore renamed attacker bytes over it. Refused here as well as in
     * the bundle reader, so no caller can reopen it. FileNotFoundException because, to every read
     * path, a ref that cannot name a blob is a blob that is not there.
     */
    private fun blobFile(blobId: String): File {
        if (!isWellFormedRef(blobId)) throw FileNotFoundException("not a blob ref")
        return File(File(context.filesDir, BLOB_DIR), "$blobId.enc")
    }

    /** Whether [blobRef] can only ever name a file directly inside `blobs/`. */
    private fun isWellFormedRef(blobRef: String): Boolean =
        blobRef.isNotEmpty() && blobRef != "." && blobRef != ".." &&
            blobRef.none { it == '/' || it == '\\' || it.code == 0 }
}

/**
 * The current session's DEK, used to encrypt/decrypt file blobs. This is a REFERENCE to the same array
 * [VaultLockManager] holds and zeroizes on `lock()` — callers must read it, never wipe or mutate it
 * themselves; only the lock manager owns its lifecycle. Throws if the vault is locked.
 */
fun VaultLockManager.currentDekOrThrow(): ByteArray =
    (lockState.value as? LockState.Unlocked)?.dek
        ?: throw IllegalStateException("vault is locked — no session DEK available")
