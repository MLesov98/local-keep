package com.localkeep.core.vault

import com.localkeep.core.model.Attachment
import com.localkeep.core.model.Vault
import com.localkeep.core.model.File as FileContent

/**
 * The blob-store operations a DEK rotation needs.
 *
 * Media blobs are encrypted under a stream key HKDF-derived from the vault DEK
 * (`StreamingAead` / `VaultSubkeys.fileStreamKey`), so rotating the DEK makes every stored file,
 * photo and attachment unreadable unless the ciphertext is re-keyed with it. The blob directory
 * lives in `:feature:files`, which depends on this module and not the other way round, so the
 * rotation reaches it through this interface and nothing else. Registered once at process start —
 * see [VaultLockManager.registerBlobReKeyStore].
 */
interface BlobReKeyStore {
    /** True when [blobRef] exists on disk. A ref the vault holds but the disk does not is skipped. */
    fun hasBlob(blobRef: String): Boolean

    /**
     * Re-encrypts [blobRef] in place from [fromDek] to [toDek]. Must be atomic per blob — the old
     * ciphertext stays readable until the new one is complete and durable — and must never stage
     * plaintext on disk.
     */
    fun reKeyBlob(blobRef: String, fromDek: ByteArray, toDek: ByteArray)

    /**
     * Whether [blobRef]'s ciphertext authenticates under [dek], without decrypting all of it.
     *
     * Only asked after a re-key of that blob has failed, to tell two situations apart that look
     * identical from the outside: a blob still under the retired key, which must stay in the
     * journal and be retried, and one already converted whose journal entry never got rewritten
     * because the process died between the rename and the write. The second is not a failure at
     * all, and without this probe it was retried at every unlock forever — the pass could never
     * finish, so the journal, and the retired key inside it, was never dropped.
     *
     * Defaults to false, which is the conservative answer: a store that cannot tell keeps the ref
     * and retries, which is what every implementation did before this existed. Never used to decide
     * that a blob may be abandoned on a count of attempts — clearing the journal destroys the only
     * remaining copy of the retired DEK, so a ref may be dropped only on proof that nothing is owed
     * for it, never on a guess that nothing will work.
     */
    fun opensUnder(blobRef: String, dek: ByteArray): Boolean = false
}

/**
 * Every blob ref the vault holds, from the complete item list rather than any presentation getter.
 *
 * The same denominator [com.localkeep.feature.files.BlobGarbageCollector] uses, and for the same
 * reason: a concealed item is absent from every presentation read by design, and an item trashed
 * with its folder is absent from the trash screen's list. Missing either here would leave exactly
 * those blobs behind under the retired key — permanently, and silently.
 *
 * Public rather than `internal` since `:feature:backup` needs the same denominator for
 * `VaultImporter.ImportResult.missingBlobRefCount` — the restore-side count of referenced files a
 * backup's media section did not contain. `internal` is per-module in Kotlin, so a dependent
 * module cannot see it however the Gradle dependency points; exposing this one function beat
 * duplicating the item/attachment walk a second time.
 */
fun blobRefsOf(vault: Vault): List<String> {
    val refs = linkedSetOf<String>()
    fun add(ref: String?) {
        if (!ref.isNullOrEmpty()) refs += ref
    }
    for (item in vault.items) {
        (item.content as? FileContent)?.let { file ->
            add(file.blobRef)
            add(file.thumbnailRef)
        }
        for (attachment: Attachment in item.attachments) {
            add(attachment.blobRef)
            add(attachment.thumbnailRef)
        }
    }
    return refs.toList()
}
