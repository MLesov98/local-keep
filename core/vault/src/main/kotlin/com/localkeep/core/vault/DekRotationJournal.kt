package com.localkeep.core.vault

import android.content.Context
import com.localkeep.core.crypto.AeadAuthException
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.Hkdf
import com.localkeep.core.crypto.WrappedDek
import com.localkeep.core.crypto.Zeroizer
import java.io.File
import java.io.FileOutputStream
import java.util.Base64

/**
 * Makes a DEK rotation's blob re-key pass survive process death.
 *
 * The pass has to read blobs under the retired key and write them under the new one, which means
 * the retired key must outlive the moment the new one is installed — otherwise a kill between the
 * header write and the last blob leaves ciphertext no key on the device can open. So the old DEK
 * is written here **wrapped under the new one**: recovering it requires already holding the new
 * DEK, which requires the new password, so this grants nothing to anyone who could not already
 * open the vault. It is deleted the moment the pass finishes, which is what actually retires the
 * old key.
 *
 * The file also carries the refs still to do, rewritten after each blob, so a resumed pass repeats
 * at most one blob's work and never re-keys an already-re-keyed blob (which would corrupt it).
 *
 * It holds a *list* of retired keys, not one, because rotations can stack. A pass that ends with
 * blobs left behind — a full disk, a damaged blob — leaves its retired key still needed by those
 * blobs. If the user then changes the master password again, the second rotation's journal used to
 * overwrite the first, and the only surviving copy of the key those blobs were sealed under went
 * with it: silent, permanent loss of the user's media, reported to them as
 * "It will finish the next time you unlock; nothing is lost." Each rotation now re-wraps every
 * still-pending entry under the DEK it is installing and appends its own, so a key stays
 * recoverable for exactly as long as some blob still needs it.
 *
 * Entries are keyed independently: one blob is sealed under whichever generation last converted
 * it, so a resumed pass runs once per entry rather than assuming a single retired key. An entry
 * whose refs have all been converted by a later generation drops out under the same
 * bounded-abandonment rule the single-key pass used — proof that there is nothing left to do,
 * never a retry count.
 */
internal object DekRotationJournal {
    private const val FILE_NAME = "dek-rotation.journal"

    /** Purpose separation: the new DEK wraps the journal under its own label, never as a raw key. */
    private const val LABEL_JOURNAL = "localkeep/v1/dek-rotation-journal"

    class Pending(
        /** Caller owns this array and must wipe it. */
        val oldDek: ByteArray,
        val refs: List<String>,
    )

    /**
     * The journal exists but its bytes could not be obtained, so what it owes is unknown.
     *
     * Carries neither cause nor message: the underlying message names a path, and nothing on this
     * path is ever shown to the user or logged, so there is nothing for either to be worth. The
     * class name is the whole signal, which is also why it holds no string for
     * `checkNoHardcodedUiStrings` to mistake for user-facing copy. A caller's only correct response
     * is to stop without touching the file.
     */
    class UnreadableException : Exception()

    /**
     * Records [entries] wrapped under [newDek], or deletes the journal when none has work left.
     * Called once before a pass starts and again after every blob.
     *
     * Every entry is re-wrapped here, so this is also how a rotation carries an unfinished older
     * generation forward: hand in the entries just read under the outgoing DEK plus the new one,
     * and they all come back wrapped under the incoming DEK.
     */
    fun write(
        context: Context,
        entries: List<Pending>,
        newDek: ByteArray,
    ) {
        val live = entries.filter { it.refs.isNotEmpty() }
        if (live.isEmpty()) {
            clear(context)
            return
        }
        val key = journalKey(newDek)
        try {
            val body = buildString {
                for (entry in live) {
                    val wrapped = EnvelopeCrypto.wrapDek(key, entry.oldDek)
                    // `nonce=` is what starts an entry, so it must lead every block — the reader
                    // splits on it.
                    append("nonce=").append(encode(wrapped.nonce)).append('\n')
                    append("old=").append(encode(wrapped.ciphertext)).append('\n')
                    entry.refs.forEach { append("ref=").append(it).append('\n') }
                }
            }
            val dir = context.filesDir
            val target = File(dir, FILE_NAME)
            val temp = File(dir, "$FILE_NAME.tmp")
            try {
                FileOutputStream(temp).use { out ->
                    out.write(body.toByteArray(Charsets.US_ASCII))
                    // Durable before the rename, matching AtomicVaultWriter: a journal whose rename
                    // survived a power loss but whose bytes did not is exactly the unreadable state
                    // this file exists to prevent.
                    out.fd.sync()
                }
                if (!temp.renameTo(target)) error("could not finalize DEK rotation journal")
            } finally {
                temp.delete()
            }
        } finally {
            Zeroizer.wipe(key)
        }
    }

    /**
     * Every unfinished pass for the session holding [newDek], oldest generation first, or empty if
     * there is none. The caller owns each entry's `oldDek` and must wipe them all.
     *
     * A journal that will not unwrap under [newDek] is deleted rather than kept. It belongs to a
     * rotation whose header write never landed — the pass only ever starts afterwards — so every
     * blob is still under the key this session is holding and nothing is lost by dropping it. The
     * alternative is a file no key on the device can ever open, re-read at every single unlock.
     *
     * All entries are wrapped under the same key, so one that fails to unwrap condemns the file:
     * they were written together by the rotation that installed [newDek]. Partial acceptance would
     * mean silently continuing with a subset of the retired keys, which is the loss this file
     * exists to prevent — so the whole journal is dropped, and only when *nothing* in it opens.
     *
     * That argument covers a journal that was *read*. One that could not be read is a different
     * state and throws [UnreadableException] instead of coming back empty; see the catch below.
     */
    fun read(context: Context, newDek: ByteArray): List<Pending> =
        readFrom(File(context.filesDir, FILE_NAME), newDek)

    /**
     * [read] against an explicit file, with the line read injectable.
     *
     * Both parameters exist so the read-failure branch can be driven from a JVM unit test: it has
     * no [Context], and a file that fails to open cannot be produced portably — chmod does not stop
     * a test runner running as root.
     */
    internal fun readFrom(
        file: File,
        newDek: ByteArray,
        readLines: (File) -> List<String> = { it.readLines() },
    ): List<Pending> {
        if (!file.isFile) return emptyList()
        val lines = try {
            readLines(file)
        } catch (_: Throwable) {
            // "Could not be read" is not "nothing in it unwraps", and collapsing the two —
            // `runCatching { ... }.getOrNull().orEmpty()` did exactly that — sent a journal nobody
            // had managed to open down the empty-`pending` branch below, which deletes it. That
            // destroys the only wrapped copy of the retired DEKs over a failure that may not even
            // repeat, and the blobs still sealed under them become unopenable. Thrown rather than
            // returned empty so that no caller can read it as "there is nothing to do": the
            // rotation path's next act is a whole-file journal replace, so "nothing" there is the
            // same loss by another route.
            //
            // Throwable, not IOException, matching what the old runCatching caught: the allocation
            // here is proportional to the journal — one line per attachment ref — so an
            // OutOfMemoryError arrives by this route too, and must not be what decides the file is
            // disposable.
            throw UnreadableException()
        }
        val blocks = mutableListOf<MutableList<String>>()
        for (line in lines) {
            if (line.startsWith("nonce=")) blocks += mutableListOf(line) else blocks.lastOrNull()?.add(line)
        }
        val key = journalKey(newDek)
        val pending = mutableListOf<Pending>()
        try {
            for (block in blocks) {
                val nonce = block.firstNotNullOfOrNull { it.removePrefixOrNull("nonce=") }?.let(::decode)
                val ciphertext = block.firstNotNullOfOrNull { it.removePrefixOrNull("old=") }?.let(::decode)
                val refs = block.mapNotNull { it.removePrefixOrNull("ref=") }.filter(String::isNotBlank)
                if (nonce == null || ciphertext == null || refs.isEmpty()) continue
                val oldDek = try {
                    EnvelopeCrypto.unwrapDek(key, WrappedDek(nonce, ciphertext))
                } catch (_: AeadAuthException) {
                    continue
                } catch (_: IllegalArgumentException) {
                    continue
                }
                pending += Pending(oldDek = oldDek, refs = refs)
            }
        } finally {
            Zeroizer.wipe(key)
        }
        if (pending.isEmpty()) {
            file.delete()
            return emptyList()
        }
        return pending
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }

    private fun journalKey(newDek: ByteArray): ByteArray = Hkdf.derive(newDek, LABEL_JOURNAL)

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(text) }.getOrNull()

    private fun String.removePrefixOrNull(prefix: String): String? =
        if (startsWith(prefix)) substring(prefix.length) else null
}
