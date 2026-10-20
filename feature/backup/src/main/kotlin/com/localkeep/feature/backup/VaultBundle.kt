package com.localkeep.feature.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * The `.vault` export container: the vault file plus the encrypted media it refers to.
 *
 * **Why it exists.** An export used to be the raw `vault.enc` bytes and nothing else, while the
 * media those items point at lives in the app-private `blobs` directory. So a backup carried every `blobRef`
 * and none of the blobs. Restoring it produced a vault full of attachments whose bytes were
 * nowhere, and — because the restored item set no longer referenced anything newer — the next
 * unlock's garbage sweep deleted those newer blobs for good. For a photo imported through the
 * capture flow, which offers to delete the gallery original, that was the only remaining copy.
 *
 * **Nothing is re-encrypted here.** Both export modes keep the same DEK: the master-password export
 * copies the vault file verbatim, and the passphrase export re-*wraps* the same DEK under a new
 * KEK rather than replacing it. So the blob files are already ciphertext that the exported vault
 * can open, and they are copied byte for byte. This class never sees a key.
 *
 * **Layout** — big-endian, streamed, so a multi-gigabyte video is never held in memory:
 *
 * ```
 * "LKBUNDLE"   8 bytes, ASCII
 * version      1 byte
 * vaultLength  4 bytes, then that many bytes of vault file
 * blobCount    4 bytes
 *   refLength  2 bytes, then that many bytes of UTF-8 blob ref
 *   blobLength 8 bytes, then that many bytes of blob ciphertext
 * ```
 *
 * A file that does not begin with [MAGIC] is a pre-bundle export — read it as a bare vault file.
 * That check is why the magic is first and fixed: it has to be answerable from the first 8 bytes,
 * without parsing anything.
 */
internal object VaultBundle {

    val MAGIC: ByteArray = "LKBUNDLE".toByteArray(Charsets.US_ASCII)
    const val VERSION: Int = 1

    /** One blob to carry across. [open] is called at most once, while the bundle is being written. */
    data class BlobSource(val ref: String, val length: Long, val open: () -> InputStream)

    /** True when [prefix] starts a bundle rather than a bare vault file. */
    fun isBundle(prefix: ByteArray): Boolean =
        prefix.size >= MAGIC.size && MAGIC.indices.all { prefix[it] == MAGIC[it] }

    fun write(out: OutputStream, vaultBytes: ByteArray, blobs: List<BlobSource>) {
        val data = DataOutputStream(out)
        data.write(MAGIC)
        data.writeByte(VERSION)
        data.writeInt(vaultBytes.size)
        data.write(vaultBytes)
        data.writeInt(blobs.size)
        for (blob in blobs) {
            val ref = blob.ref.toByteArray(Charsets.UTF_8)
            // The length field is a uint16 and `writeShort` keeps only the low 16 bits, silently.
            // An over-long ref would therefore write a length that does not describe the bytes that
            // follow it, and the reader — which trusts that number to know where the next record
            // starts — would decode the rest of the bundle as garbage. Refs are UUIDs in practice,
            // so this can only fire on a bug; failing the export beats writing a file that restores
            // to nonsense.
            require(ref.size <= MAX_REF_BYTES) { "blob ref too long: ${ref.size} bytes" }
            data.writeShort(ref.size)
            data.write(ref)
            data.writeLong(blob.length)
            // Copied, not read into memory: this is where the videos are.
            blob.open().use { source -> source.copyTo(data) }
        }
        data.flush()
    }

    /**
     * Reads the header and the vault file, leaving [input] positioned at the first blob.
     *
     * Two phases rather than one call, because the caller has to decide *what to do with the
     * blobs* using something only the vault file can tell it — whether this export belongs to the
     * vault being restored into. The layout puts the vault first precisely so that question can be
     * answered without buffering the media.
     */
    fun open(input: InputStream): Reader {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        data.readFully(magic)
        require(isBundle(magic)) { "not a vault bundle" }
        val version = data.readUnsignedByte()
        require(version == VERSION) { "unsupported bundle version $version" }

        val vaultLength = data.readInt()
        require(vaultLength in 0..MAX_VAULT_BYTES) { "implausible vault length $vaultLength" }
        val vaultBytes = ByteArray(vaultLength)
        data.readFully(vaultBytes)
        return Reader(data, vaultBytes)
    }

    class Reader internal constructor(
        private val data: DataInputStream,
        val vaultBytes: ByteArray,
    ) {
        /**
         * Streams each blob to [writeBlob] in turn.
         *
         * The stream handed over is bounded to that blob's length and must be consumed fully — the
         * reader cannot skip past what a caller left behind, and a half-read entry would make every
         * later one decode as garbage. Pass a sink that drains it even when discarding.
         */
        fun forEachBlob(writeBlob: (ref: String, bytes: InputStream, length: Long) -> Unit) {
            val blobCount = data.readInt()
            require(blobCount in 0..MAX_BLOBS) { "implausible blob count $blobCount" }
            repeat(blobCount) {
                val refLength = data.readUnsignedShort()
                val refBytes = ByteArray(refLength)
                data.readFully(refBytes)
                val ref = String(refBytes, Charsets.UTF_8)
                // 2026-10-02: the ref becomes a file name (`blobs/<ref>.enc`) the moment it reaches
                // FileBlobStore, and these records sit outside the vault container's AEAD, so the
                // password check vouches for none of them. A ref of `../vault` renamed attacker bytes
                // over vault.enc at stage time. Every ref this app has ever minted is
                // `UUID.randomUUID().toString()` (FileBlobStore.encryptToBlob), so nothing else is let
                // through to name a path.
                require(BLOB_REF.matches(ref)) { "malformed blob ref in bundle" }
                val length = data.readLong()
                require(length >= 0) { "negative blob length for $ref" }
                val bounded = BoundedInputStream(data, length)
                writeBlob(ref, bounded, length)
                check(bounded.remaining == 0L) { "blob $ref was not fully consumed" }
            }
        }
    }

    /**
     * Guards against a corrupt length turning into a multi-gigabyte allocation.
     *
     * `internal` because [VaultImporter]'s pre-bundle branch needs the same ceiling: that path reads
     * the whole picked file rather than a declared length, so without a cap it is the one import
     * route with no bound at all.
     */
    internal const val MAX_VAULT_BYTES = 512 * 1024 * 1024
    private const val MAX_BLOBS = 1_000_000

    /** The ref length field is a uint16 on the wire. */
    private const val MAX_REF_BYTES = 65_535

    /** The only shape a blob ref has: `UUID.toString()`, lowercase hex. */
    private val BLOB_REF = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
}

/** Reads at most [remaining] bytes of [source], and never closes it. */
private class BoundedInputStream(
    private val source: InputStream,
    var remaining: Long,
) : InputStream() {

    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = source.read()
        if (b == -1) throw EOFException("bundle truncated")
        remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val toRead = minOf(len.toLong(), remaining).toInt()
        val read = source.read(b, off, toRead)
        if (read == -1) throw EOFException("bundle truncated")
        remaining -= read
        return read
    }

    override fun available(): Int = minOf(remaining, source.available().toLong()).toInt()

    /** Deliberately a no-op: the underlying stream belongs to the bundle reader. */
    override fun close() = Unit
}
