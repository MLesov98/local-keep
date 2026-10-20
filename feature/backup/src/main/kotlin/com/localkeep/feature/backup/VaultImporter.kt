package com.localkeep.feature.backup

import android.content.Context
import android.net.Uri
import com.localkeep.core.common.AppError
import com.localkeep.core.common.Outcome
import com.localkeep.core.common.map
import com.localkeep.core.crypto.AeadAuthException
import com.localkeep.core.crypto.ConstantTime
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KeyCommitment
import com.localkeep.core.crypto.VaultSubkeys
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.Folder
import com.localkeep.core.model.FolderTree
import com.localkeep.core.model.ItemMerge
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import com.localkeep.core.model.VaultSchemaTooNewException
import com.localkeep.core.vault.VaultFileCodec
import com.localkeep.core.vault.VaultHeaderCodec
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultMutator
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.blobRefsOf
import com.localkeep.feature.files.FileBlobStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a `.vault` export via SAF and merges it into the currently unlocked vault. Thin
 * wrapper over [VaultFileCodec] (§H) — never duplicates the container format.
 *
 * DEVIATION from the plan: [VaultSubkeys] lives in `com.localkeep.core.crypto`, not
 * `com.localkeep.core.vault` (see [VaultExporter]'s doc comment for the same note).
 */
@Singleton
class VaultImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileBlobStore: FileBlobStore,
    private val lockManager: VaultLockManager,
) {

    /**
     * What a successful [readVault] returns: the imported vault, plus how many distinct files its
     * items reference that this backup's media section did not contain.
     *
     * [missingBlobRefCount] carries no duress knowledge and cannot be made to: it is purely
     * bundle-vs-vault media coverage, computed the same way whether the export was taken from the
     * real vault, a decoy vault, or is simply truncated or corrupted. That is deliberate, and a
     * backup taken in a duress session is the case it is deliberate for. Warning
     * about it at *export* time would require knowing a second vault might exist, which is exactly
     * the fact the decoy exists to hide; reporting it here, after the fact, on the restoring
     * device, needs nothing but the bundle's own contents and the restored vault's blob refs.
     *
     * [voidsRecoveryKit] — see [voidsRecoveryKit] the function: the file's vault had a Recovery Kit,
     * and that kit will not open the vault this import writes into.
     */
    data class ImportResult(
        val vault: Vault,
        val missingBlobRefCount: Int,
        val voidsRecoveryKit: Boolean = false,
    )

    /** The heap a file's own Argon2 cost is checked against. A seam for tests; always the real cap. */
    internal var heapLimitBytes: () -> Long = { Runtime.getRuntime().maxMemory() }

    /**
     * Reads and decrypts [source] with [password]. Only slot 0 (real/master-password) is tried.
     *
     * Also materialises the encrypted media the export carries, which is the whole reason exports
     * became a bundle: an item's `blobRef` is worthless if the bytes it names are not on this
     * device. Blobs are copied as ciphertext — see [restoreBlobs] for when that is valid and what
     * happens when it is not.
     */
    /**
     * The raw vault container inside [source], and nothing else.
     *
     * The whole file for a pre-bundle export, or the bundle's vault section for a `.vault` bundle —
     * the same first step [readVault] takes, stopped before it tries a password. No key is derived,
     * no media is touched, nothing is written, and no unlocked session is needed, so this is
     * callable **while the vault is locked**. That is its entire reason for existing: `:feature:
     * unlock` needs a restore route for a `vault.enc` that will not open (F34, spec §12.5 MUST),
     * and `VaultBundle` is `internal` to this module, so from outside there was no way to get at the
     * container bytes inside a file the user picked.
     *
     * The caller owns what comes back and owns the decision: it must validate slot 0 and
     * authenticate region 0 itself before writing anything over a live container. This returns
     * bytes, not a verdict — a file that is not a vault at all still comes back as bytes here and
     * fails the caller's own header decode, which is where that failure belongs.
     *
     * Same caps and the same failure literals as [readVault], because it is the same read:
     * `VaultBundle.MAX_VAULT_BYTES`, the deliberate `OutOfMemoryError` catch (see its comment
     * there), and the stream closed on every path.
     */
    suspend fun readContainerBytes(source: Uri): Outcome<ByteArray> = withContext(Dispatchers.IO) {
        val read = try {
            readContainer(source)
        } catch (e: Exception) {
            return@withContext Outcome.Failure(containerReadFailure(e))
        } catch (_: OutOfMemoryError) {
            return@withContext Outcome.Failure(
                AppError.Storage("that file is too large to open on this device"),
            )
        } ?: return@withContext Outcome.Failure(
            AppError.Storage("could not open the file you chose"),
        )
        try {
            Outcome.Success(read.vaultBytes.copyOf())
        } finally {
            read.close()
        }
    }

    suspend fun readVault(source: Uri, password: CharArray): Outcome<ImportResult> =
        withContext(Dispatchers.IO) {
            val read = try {
                readContainer(source)
            } catch (e: Exception) {
                return@withContext Outcome.Failure(containerReadFailure(e))
            } catch (e: OutOfMemoryError) {
                // Caught deliberately, and an Error rather than an Exception is the whole point.
                // `VaultBundle.open` reads a length field out of the file and allocates from it —
                // bounded by MAX_VAULT_BYTES, but that bound is 512 MB, eight times what
                // BreachFilter allows, and the bound cannot tell a truthful length from a lie. On a
                // phone that allocation is an OOM, which is an Error, so it slipped past the catch
                // above and escaped the coroutine uncaught: a crash, on a file the user picked.
                //
                // BreachFilter.ensureLoaded catches Throwable for exactly this reason and says so
                // in its own comment. This is the same decision, scoped to the one Error that can
                // actually happen here rather than to Throwable, so a genuine bug still propagates.
                return@withContext Outcome.Failure(
                    AppError.Storage("that file is too large to open on this device"),
                )
            } ?: return@withContext Outcome.Failure(
                AppError.Storage("could not open the file you chose"),
            )
            // The container owns a ContentResolver stream, and every early return below is a
            // failure path that used to walk straight past the one place that closed it
            // (restoreBlobs). A wrong password is the *expected* outcome here, not an edge case, so
            // the leak was one file descriptor per mistyped password.
            try {
                val fileBytes = read.vaultBytes

                val header = try {
                    VaultHeaderCodec.decode(fileBytes)
                } catch (e: Exception) {
                    return@withContext Outcome.Failure(AppError.Validation("not a valid .vault file", e))
                }

                val slot0 = header.slots[0]
                // 2026-10-02: the Argon2 cost here is the file's, not the calibrator's, and the header
                // admits up to 1 GiB. Every other in-app derivation is guarded; this one let the
                // OutOfMemoryError (an Error) past every catch and out of viewModelScope, killing the
                // process on a file the user picked. A cost larger than the whole heap is refused
                // before allocating; one that merely does not fit beside the UI is caught.
                val kdfMemoryBytes = slot0.kdfParams.memoryKib.toLong() * 1024
                val kek = if (kdfMemoryBytes > heapLimitBytes()) {
                    null
                } else {
                    try {
                        EnvelopeCrypto.deriveKek(password, slot0.salt, slot0.kdfParams)
                    } catch (_: OutOfMemoryError) {
                        null
                    }
                }
                if (kek == null) {
                    Zeroizer.wipe(password)
                    return@withContext Outcome.Failure(AppError.Storage(IMPORT_KDF_NEEDS_MORE_MEMORY))
                }
                val dek: ByteArray
                try {
                    val d = EnvelopeCrypto.unwrapDek(kek, slot0.wrappedDek)
                    val commitOk = KeyCommitment.verify(VaultSubkeys.commitKey(d), slot0.commitment)
                    if (!commitOk) {
                        Zeroizer.wipe(d)
                        return@withContext Outcome.Failure(AppError.Crypto("wrong password"))
                    }
                    dek = d
                } catch (e: AeadAuthException) {
                    return@withContext Outcome.Failure(AppError.Crypto("wrong password", e))
                } finally {
                    Zeroizer.wipe(kek)
                    Zeroizer.wipe(password)
                }

                try {
                    val voidsRecoveryKit = voidsRecoveryKit(fileBytes, dek)
                    decryptThenRestore(
                        decrypt = {
                            val encKey = VaultSubkeys.encKey(dek)
                            try {
                                VaultFileCodec.decrypt(fileBytes, encKey).second
                            } finally {
                                Zeroizer.wipe(encKey)
                            }
                        },
                        restoreBlobs = { read.restoreBlobs(dek) },
                    ).map { vault -> ImportResult(vault, read.missingBlobRefCount(vault), voidsRecoveryKit) }
                } finally {
                    Zeroizer.wipe(dek)
                }
            } finally {
                read.close()
            }
        }

    /**
     * Whether a Recovery Kit made for the file's vault stops covering the user once this import
     * lands — the file carries a kit, and its data key is not this session's.
     *
     * Both import modes write the file's *records* into the vault that is open, under its key; the
     * file's own container, key and kit block stay behind. So when the two keys differ, the kit the
     * user printed for the file's vault opens nothing here. That is the ordinary move to a new phone
     * — create a vault, restore the old one's export into it — and it used to finish on "Import
     * complete", leaving the user holding a kit they believed still worked (device report C3
     * finding 2, 2026-10-04).
     *
     * A file with no kit block answers false. An independent-passphrase export never writes one
     * ([VaultExporter.exportWithPassphrase]), and telling someone their "old Recovery Kit no longer
     * works" when their file never had one is the same false claim once made on the
     * password-change path. So is a locked session: nothing is known about which vault is open.
     *
     * Not a duress tell in practice: a decoy session reaches this only by opening the *real*
     * vault's master-password export, which takes the real master password.
     */
    private fun voidsRecoveryKit(fileBytes: ByteArray, fileDek: ByteArray): Boolean {
        val carriesKit = runCatching { VaultFileCodec.recoveryWrappedDek(fileBytes) != null }
            .getOrDefault(false)
        if (!carriesKit) return false
        val sessionDek = lockManager.sessionSnapshotOrNull()?.dek ?: return false
        return try {
            !ConstantTime.equals(sessionDek, fileDek)
        } finally {
            Zeroizer.wipe(sessionDek)
        }
    }

    /**
     * What was read out of the export: the vault file, and a way to unpack whatever media follows.
     *
     * A pre-bundle export has no media section at all, and [restoreBlobs] is then a no-op — the
     * format check is by magic bytes, so old backups keep working untouched.
     */
    private class Container(
        val vaultBytes: ByteArray,
        private val reader: VaultBundle.Reader?,
        private val stream: java.io.InputStream,
        private val fileBlobStore: FileBlobStore,
        /**
         * A *private copy* of the session DEK, or null when the vault is locked. The copy is
         * this class's to wipe — see [restoreBlobs], which takes exactly one per pass.
         */
        private val sessionDekCopy: () -> ByteArray?,
    ) {
        /**
         * Refs the bundle's media section actually carried, populated by [restoreBlobs] as it
         * walks the section. Empty until [restoreBlobs] has run, and permanently empty for a
         * pre-bundle export (no [reader], so no media section at all) — both are the correct
         * denominator for [missingBlobRefCount] to compare against.
         */
        private val bundleRefs = mutableSetOf<String>()

        /**
         * Writes the export's blobs into the local blob store.
         *
         * Two cases, and both used to lose files.
         *
         * **Same vault** — both export modes preserve the DEK (the passphrase export re-*wraps* it
         * rather than replacing it), so the bytes are already openable here and are copied verbatim.
         * The old condition was `!hasBlob(ref)`: a blob whose file already existed was skipped, on
         * the assumption that a present file is an intact one. It is not — an interrupted write, a
         * lost block, a half-restored app-data backup all leave a file under the right name that
         * will not authenticate — and restore then walked past the one good copy and kept the
         * damaged one. It now replaces anything that does not decrypt (see
         * [FileBlobStore.authenticates]).
         *
         * **Different vault** — the export's blobs are encrypted under a DEK this vault does not
         * have, so they used to be drained into a null sink. The *items* referencing them were
         * merged in regardless, which is the failure: the import reported success and produced a
         * vault full of attachments that cannot open, with the source vault possibly already gone.
         * We hold both keys at this moment, so the bytes are re-keyed on the way in and the media
         * actually arrives — except where the ref is already taken by a blob this session's key
         * cannot open, which is refused outright rather than re-keyed over. See the branch itself
         * for why that case exists and why the test is phrased the way it is.
         */
        fun restoreBlobs(exportDek: ByteArray) {
            val bundle = reader ?: return
            // A private copy, not the manager's own array, and taken once for the whole pass.
            //
            // `currentDekOrThrow()` hands back a reference to the array `lock()` zeroes in place,
            // and nothing here borrows the session, so an auto-lock or screen-off landing anywhere
            // in this loop used to zero the key under it. Two things went wrong with that. The
            // cross-vault branch below passes it to `transcodeEncrypted` as the destination key,
            // so every blob after the lock point was sealed under all zeroes — invisible until the
            // user re-unlocked, confirmed the import, and found media that would not open. And a
            // lock landing between this line and `sameVault` made a genuine same-vault restore
            // compare zeroes against the export DEK, fail, and take the cross-vault path anyway.
            //
            // A copy rather than a `withSessionDek` borrow deliberately: VaultLockManager's own
            // KDoc excludes borrows of arbitrary length, and a multi-gigabyte bundle is exactly
            // that — a borrow here would hold the vault open for as long as the restore runs.
            // The copy leaves lock() prompt and costs one key-sized array, wiped in the finally.
            val local = sessionDekCopy()
            val sameVault = local != null && ConstantTime.equals(local, exportDek)
            // Refs this pass actually wrote to disk (never a drained/skipped ref). Blobs land one
            // at a time, so a later ref throwing — a full disk, or a truncated bundle raising
            // EOFException mid-read — otherwise leaves every earlier ref an orphan: the caller
            // reports Failure, nothing merges the items that would reference them, and nothing
            // else will ever know to delete these bytes.
            val written = mutableListOf<String>()
            try {
                bundle.forEachBlob { ref, bytes, _ ->
                    bundleRefs += ref
                    when {
                        // Nothing to write into. Draining is all that is left, and the caller reports
                        // the failure — silently dropping media is what this whole method is fixing.
                        local == null -> {
                            bytes.copyTo(NullOutputStream)
                            throw VaultLockedDuringImportException()
                        }
                        sameVault ->
                            if (fileBlobStore.authenticates(ref, local)) {
                                // Drained rather than skipped: the reader cannot seek, so an unconsumed
                                // entry would leave every later one decoding as garbage.
                                bytes.copyTo(NullOutputStream)
                            } else {
                                fileBlobStore.writeEncrypted(ref, bytes)
                                written += ref
                            }
                        else -> {
                            // Refuse rather than re-key over a blob the live key does not own.
                            //
                            // `blobs/` is one flat namespace shared by both payload regions —
                            // FileBlobStore.blobFile is `blobs/<ref>.enc` with no region
                            // qualifier — and a session holds the key for exactly one region. So
                            // a file already sitting under this ref that does not open under
                            // `local` belongs to the *other* region, and transcoding would rename
                            // re-keyed ciphertext over the only copy of media that region's items
                            // still point at. Unrecoverable: `blobs/` is excluded from Auto Backup
                            // and device transfer, the rolling snapshot ring holds vault.enc only,
                            // and transcodeEncrypted keeps no journal of the bytes it replaces.
                            //
                            // Phrased with no duress knowledge on purpose. It asks only whether
                            // the live key opens what is already on disk, and answers that
                            // identically for a corrupt blob, a foreign export and a decoy
                            // session — so it cannot tell a coerced-password holder that a second
                            // vault exists. Asking the lock manager "is this a duress session"
                            // would answer the same question by confessing exactly that, which is
                            // also why [ImportResult.missingBlobRefCount] above is computed the
                            // way it is. BlobGarbageCollector declines the sweep in a duress
                            // session for the mirror hazard in the delete direction; this is the
                            // overwrite direction of the same flat namespace.
                            //
                            // Drained before throwing for the same reason the branches above
                            // drain: the reader cannot seek.
                            if (fileBlobStore.hasBlob(ref) && !fileBlobStore.authenticates(ref, local)) {
                                bytes.copyTo(NullOutputStream)
                                error("import would overwrite media this session cannot open")
                            }
                            fileBlobStore.transcodeEncrypted(ref, bytes, exportDek, local)
                            written += ref
                        }
                    }
                }
            } catch (t: Throwable) {
                written.forEach(fileBlobStore::deleteBlob)
                throw t
            } finally {
                // The copy exists only for this pass; nothing outside it may read the key again.
                Zeroizer.wipe(local)
            }
        }

        /**
         * Distinct blob refs [vault]'s items point at that [restoreBlobs] never saw in this
         * bundle's media section.
         *
         * Deliberately not a statement about whether the file is on disk *now* — a same-vault
         * blob this device already had is "present" here even though [restoreBlobs] drained it
         * rather than writing it, because the backup itself did carry it. What this counts is
         * backup completeness, not local disk state, which is what makes it identical whether the
         * export came from the real vault, a decoy vault, or was simply truncated: none of that
         * changes what bytes the file contains.
         */
        fun missingBlobRefCount(vault: Vault): Int =
            blobRefsOf(vault).count { it !in bundleRefs }

        /** Idempotent: the success path and the `finally` in [readVault] both reach it. */
        fun close() {
            runCatching { stream.close() }
        }
    }

    private object NullOutputStream : java.io.OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    /**
     * What a failure while opening the picked file means.
     *
     * Content that cannot be a vault — a bundle cut short ([java.io.EOFException] from the bundle's
     * own reader), a header that fails its own checks (`require`/`check`), a file over the size a
     * vault can be — is "not a valid .vault file". It used to be reported as a storage fault,
     * "Couldn't read the file you chose", for a file that was read to its end: the first 100 bytes
     * of a real backup got that answer, which invites a retry of the same file. Anything else the
     * stream throws is a genuine read failure and keeps the storage answer.
     */
    private fun containerReadFailure(e: Exception): AppError = when (e) {
        is java.io.EOFException, is IllegalArgumentException, is IllegalStateException ->
            AppError.Validation("not a valid .vault file", e)
        else -> AppError.Storage("failed to read import file", e)
    }

    private fun readContainer(source: Uri): Container? {
        val stream = context.contentResolver.openInputStream(source) ?: return null
        val buffered = stream.buffered()
        return try {
            buffered.mark(VaultBundle.MAGIC.size)
            val prefix = ByteArray(VaultBundle.MAGIC.size)
            // 2026-10-02: looped, not one read(). A single read returns short whenever the provider's
            // first chunk is short, and an intact bundle then fell into the pre-bundle branch and was
            // called "not a valid .vault file".
            var readCount = 0
            while (readCount < prefix.size) {
                val n = buffered.read(prefix, readCount, prefix.size - readCount)
                if (n < 0) break
                readCount += n
            }
            buffered.reset()
            if (readCount == prefix.size && VaultBundle.isBundle(prefix)) {
                val reader = VaultBundle.open(buffered)
                Container(reader.vaultBytes, reader, buffered, fileBlobStore) {
                    // A copy the container wipes, not the manager's live array — `lock()` zeroes
                    // that one in place, and a blob restore runs far too long to hold it.
                    lockManager.sessionSnapshotOrNull()?.dek
                }
            } else {
            // A pre-bundle export: the whole file is the vault, and there is no media section.
            //
            // Capped at the same ceiling the bundle branch checks its declared length against. This
            // branch had none: `readBytes()` buffers whatever the user picked before anything has
            // established the file is a vault at all, so a multi-gigabyte pick — an ordinary video,
            // chosen by mistake in the file picker — was an allocation the size of the file. The
            // `OutOfMemoryError` catch in [readVault] is a backstop for a declared length that
            // lies, not a substitute for a bound: by the time it fires the heap is already gone.
                Container(readCapped(buffered), null, buffered, fileBlobStore) { null }
            }
        } catch (t: Throwable) {
            runCatching { buffered.close() }
            throw t
        }
    }

    /**
     * [source]'s bytes, or a failure if there are more than [VaultBundle.MAX_VAULT_BYTES] of them.
     *
     * Reads one byte past the cap rather than trusting a size the stream reports: a SAF stream's
     * `available()` is a hint, and a content provider is free to under-report it.
     */
    private fun readCapped(source: java.io.InputStream): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val read = source.read(chunk)
            if (read < 0) break
            if (buffer.size().toLong() + read > VaultBundle.MAX_VAULT_BYTES) {
                error("import file is larger than a vault can be")
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    /**
     * Tombstone-aware merge (§H): identity = [VaultItem.id]; the item with the greater
     * `updatedAt` wins wholesale (including `deletedAt`), EXCEPT a password the loser changed more
     * recently is preserved (see [ItemMerge.preserveNewerPassword]) so re-importing a stale export
     * whose metadata was touched later can't silently clobber a fresher in-vault password.
     * Folders merge by id, but **field by field** rather than whole: `updatedAt` orders the name
     * and `parentUpdatedAt` orders the parent. A rename in one copy and a move in the other are two
     * things the user really did, and whole-folder recency would discard whichever lost. A null
     * stamp means "at an unknown time" — every folder written before its schema has one — and
     * deliberately loses to any real timestamp rather than being read as "now".
     *
     * The merged tree is then **normalized** before it is written. Two individually valid vaults can
     * merge into one that is not: A's parent is B and B's parent is A, each arriving from a copy
     * where only one of those edges existed. `VaultMutator.build` validates, so an unnormalized
     * merge would throw and make importing a perfectly good backup impossible to finish — a
     * failure with no way around it from inside the app. Normalizing first means the validation is
     * a final assert on data that has already been repaired, which is what it should be.
     */
    suspend fun mergeInto(repository: VaultRepository, imported: Vault): Outcome<MergeCounts> =
        withContext(Dispatchers.IO) {
            var counts = MergeCounts(added = 0, updated = 0, unchanged = 0)
            try {
                // Counted as the merge runs, because this is the only place the three outcomes are
                // distinguishable: afterwards the vault holds one list and nothing records which of
                // its items the file contributed. The whole result copy used to be the two words
                // "Import complete", on a path with no preview, no item list and no per-item
                // toggles — so a user who merged a backup had no way to learn whether it had
                // brought anything in (release sweep 2026-09-20, finding 1C-F1).
                repository.mutate { mutator: VaultMutator ->
                    // Assigned, not accumulated: `mutate` may run its block more than once, and
                    // counts carried across attempts would be a larger lie than no counts at all.
                    val result = mergeItems(existing = mutator.items, imported = imported.items)
                    counts = result.counts

                    val mergedFolders = LinkedHashMap<UUID, Folder>()
                    mutator.folders.forEach { mergedFolders[it.id] = it }
                    for (incoming in imported.folders) {
                        val existing = mergedFolders[incoming.id]
                        mergedFolders[incoming.id] = if (existing == null) {
                            incoming
                        } else {
                            FolderTree.mergeFolder(incoming = incoming, existing = existing)
                        }
                    }
                    val normalizedFolders = FolderTree.normalize(mergedFolders.values.toList()).folders

                    // A merge that changed nothing must not save. Every save emits `onSaved`, which
                    // takes a rolling snapshot out of a fixed set of five, so merging a file the vault
                    // already held pushed out the oldest restore point for a vault identical to the
                    // one before. Compared on the lists themselves rather than on the tally: the
                    // tally counts items only, and a folder arriving with no new items is a change.
                    // `mutate` commits only if its block returns, so throwing is how this declines.
                    if (result.items == mutator.items.toList() && normalizedFolders == mutator.folders.toList()) {
                        throw NothingToMerge()
                    }
                    mutator.items.clear()
                    mutator.items.addAll(result.items)
                    mutator.folders.clear()
                    mutator.folders.addAll(normalizedFolders)
                }
                Outcome.Success(counts)
            } catch (_: NothingToMerge) {
                Outcome.Success(counts)
            } catch (e: Exception) {
                Outcome.Failure(AppError.Unknown("merge failed", e))
            }
        }

    /** Replace mode: discards the current vault's items/folders entirely. */
    suspend fun replaceWith(repository: VaultRepository, imported: Vault): Outcome<Unit> =
        withContext(Dispatchers.IO) {
            try {
                repository.mutate { mutator: VaultMutator ->
                    mutator.items.clear(); mutator.items.addAll(imported.items)
                    // Normalized on the way in for the same reason the merge is: the file was
                    // written by something this build does not control, and a tree it cannot write
                    // would make the replace throw rather than complete.
                    mutator.folders.clear()
                    mutator.folders.addAll(FolderTree.normalize(imported.folders).folders)
                    // The verifier travels with the records it belongs to. Replace discards every
                    // item and folder this vault held, so keeping the local phrase would leave the
                    // imported hidden entries concealed behind a phrase that no longer exists — and
                    // the *merge* path deliberately does the opposite, keeping the local verifier
                    // because the local records are still there. A backup with nothing hidden
                    // carries a null verifier, which is the correct answer for a vault that now has
                    // nothing hidden either.
                    mutator.revealPhrase = imported.revealPhrase
                }
                Outcome.Success(Unit)
            } catch (e: Exception) {
                Outcome.Failure(AppError.Unknown("replace failed", e))
            }
        }
}

/** Thrown out of a `mutate` block to leave without committing: the merge had nothing to write. */
private class NothingToMerge : RuntimeException()

/** The item list a merge produced, and what it did to get there. */
internal data class MergeResult(val items: List<VaultItem>, val counts: MergeCounts)

/**
 * The item half of a vault-to-vault merge, lifted out of [VaultImporter.mergeInto] so both the
 * result and the tally can be tested without a vault, a repository or a device.
 *
 * Keyed by id, existing order preserved, and every incoming item resolved by [mergeImportedItem].
 * An incoming item the merge had nothing to take from counts as unchanged rather than updated —
 * the difference is the whole point of reporting the numbers, because "23 already current" and
 * "23 updated" describe opposite outcomes for the same file.
 */
internal fun mergeItems(existing: List<VaultItem>, imported: List<VaultItem>): MergeResult {
    val merged = LinkedHashMap<UUID, VaultItem>()
    existing.forEach { merged[it.id] = it }
    var added = 0
    var updated = 0
    var unchanged = 0
    for (incoming in imported) {
        val current = merged[incoming.id]
        if (current == null) {
            merged[incoming.id] = incoming
            added++
        } else {
            val result = mergeImportedItem(existing = current, incoming = incoming)
            merged[incoming.id] = result
            if (result == current) unchanged++ else updated++
        }
    }
    return MergeResult(
        merged.values.toList(),
        MergeCounts(added = added, updated = updated, unchanged = unchanged),
    )
}

/**
 * What a `.vault` merge actually did, per item in the imported file.
 *
 * [added] the file had and this vault did not, [updated] it had a newer copy of, [unchanged] it had
 * nothing newer to contribute to. The three sum to the imported file's item count, never to this
 * vault's: an item only this vault holds is not something the merge did.
 */
data class MergeCounts(val added: Int, val updated: Int, val unchanged: Int)

/**
 * One item's side of a vault-to-vault merge: who wins overall, and what the hidden lease ends up as.
 *
 * Extracted from [VaultImporter.mergeInto] so the decision can be tested without a vault, a
 * repository or a device — it is two independent rules and it had a defect in the interaction
 * between them.
 *
 * The rules:
 *  - **Overall recency** picks the winner, with [ItemMerge.preserveNewerPassword] rescuing the
 *    loser's password when the loser's is newer.
 *  - **Concealment merges on its own stamp, independently** — and therefore *outside* the recency
 *    branch, which is where it used to sit. A hide never touches `updatedAt` (`withHiddenLease`
 *    writes only the pair), so two devices that last *edited* an item at the same instant, one of
 *    which has since hidden it, tie on `updatedAt` — and the incoming lease was dropped unread. A
 *    hidden *folder* already merged correctly (`FolderTree.mergeFolder` has always been
 *    unconditional), so one backup could carry a folder's hide across and silently lose the
 *    identical hide on an item inside it. Found 2026-08-19.
 *
 * The comment this replaces claimed "an export is undated for hidden state". That was false: schema
 * 5 carries `hiddenUntil` and `hiddenUpdatedAt` through encode/decode, which `HiddenSchemaTest` pins.
 *
 * Nothing is released by merging unconditionally: [ItemMerge.mergeHiddenPair] keeps `existing` on a
 * null incoming stamp and on a tie, so a blank-sided import — every CSV/KDBX path — still cannot
 * reveal a local hide.
 */
internal fun mergeImportedItem(existing: VaultItem, incoming: VaultItem): VaultItem {
    val (until, stamp) = ItemMerge.mergeHiddenPair(existing = existing, incoming = incoming)
    val winner =
        if (incoming.updatedAt.isAfter(existing.updatedAt)) {
            ItemMerge.preserveNewerPassword(winner = incoming, loser = existing)
        } else {
            existing
        }
    return winner.copy(hiddenUntil = until, hiddenUpdatedAt = stamp)
}

/**
 * Runs the two phases an import is made of and keeps their failures apart.
 *
 * [decrypt] is the only phase a wrong password or a corrupt container can fail in, so only it
 * reports [AppError.Crypto]. [restoreBlobs] writes media to local storage long after the file has
 * been authenticated: a full disk or an unmounted volume there is [AppError.Storage], and telling
 * the user it was a decryption problem sends them off re-typing a password that was correct.
 *
 * The third outcome is [AppError.Validation]: a payload stamped with a schema this build cannot
 * read is neither a wrong password nor a damaged file, and it is the one decrypt-phase failure
 * that the user can actually fix — by updating the app.
 */
internal fun <T> decryptThenRestore(
    decrypt: () -> T,
    restoreBlobs: () -> Unit,
): Outcome<T> {
    val decrypted = try {
        decrypt()
    } catch (e: VaultSchemaTooNewException) {
        // Ahead of the blanket catch, and the reason it cannot stay blanket. Everything the
        // password can be wrong about has already been decided by the time [decrypt] runs — the
        // KEK unwrapped the DEK and the key commitment verified — so a decode refusal here is the
        // one failure that is neither a wrong password nor a damaged file: the payload declares a
        // schema this build does not understand. Reported as "failed to decrypt import file" it
        // sent the user back to re-type a password that was already correct, about a file that is
        // intact, with no hint that updating the app is the thing that opens it. The unlock path
        // separates the same exception for the same reason — see [VaultLockManager]'s slot trial.
        return Outcome.Failure(
            AppError.Validation("this backup was written by a newer version of LocalKeep", e),
        )
    } catch (e: Exception) {
        return Outcome.Failure(AppError.Crypto("failed to decrypt import file", e))
    }
    return try {
        restoreBlobs()
        Outcome.Success(decrypted)
    } catch (e: VaultLockedDuringImportException) {
        // Separated from the attachment failure below because it is not one. The vault auto-locked
        // while the file picker was open; no attachment was at fault and nothing in the backup was
        // damaged, but the user was told "some attachments in this backup could not be restored",
        // which reads as a broken backup file (G14, 2026-09-11).
        Outcome.Failure(AppError.Storage(VAULT_LOCKED_DURING_IMPORT, e))
    } catch (e: Exception) {
        Outcome.Failure(AppError.Storage("failed to restore attachments from import file", e))
    }
}

/** The session DEK went away mid-restore: the vault auto-locked while the file was being read. */
internal class VaultLockedDuringImportException :
    IllegalStateException("vault locked during import")

/** Message key [BackupSettingsViewModel] maps to user-facing copy. Kept beside the throw site. */
internal const val VAULT_LOCKED_DURING_IMPORT = "vault locked during import"

/** The file's own key-derivation cost does not fit this device's heap. Mapped like the one above. */
internal const val IMPORT_KDF_NEEDS_MORE_MEMORY = "import key derivation does not fit in memory"
