package com.localkeep.feature.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.localkeep.core.common.AppError
import com.localkeep.core.common.Outcome
import com.localkeep.core.crypto.Argon2Calibrator
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.VaultSubkeys
import com.localkeep.core.crypto.WrappedDek
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.Vault
import com.localkeep.core.vault.KdfNeedsMoreMemoryException
import com.localkeep.core.vault.VaultFileCodec
import com.localkeep.core.vault.VaultHeaderCodec
import com.localkeep.core.vault.VaultLoader
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.feature.files.BlobGarbageCollector
import com.localkeep.feature.files.FileBlobStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes a portable `.vault` export via SAF. Thin wrapper over [VaultFileCodec] — never
 * duplicates the container format (§H).
 *
 * DEVIATION from the plan: the real [VaultSubkeys] lives in `com.localkeep.core.crypto`
 * (confirmed by reading `core/crypto/src/main/kotlin/com/localkeep/core/crypto/Hkdf.kt`),
 * not `com.localkeep.core.vault` as the plan's import block assumed.
 */
@Singleton
class VaultExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileBlobStore: FileBlobStore,
    private val blobGarbageCollector: BlobGarbageCollector,
    private val vaultLockManager: VaultLockManager,
) {
    data class ExportManifest(val sizeBytes: Long, val exportedAt: Instant)

    /**
     * Exports the live `vault.enc` bytes as-is — the same master password unlocks the export.
     *
     * The payload is authenticated first, which the other export path gets for free and this one
     * never had. [exportWithPassphrase] below re-encrypts from the decrypted session model, so it
     * cannot produce a file that will not open. This one is a byte copy behind a header-only
     * re-authentication ([VaultLockManager.reauthenticate] verifies an unlock slot, and slots live
     * in the header), so nothing between "the password is right" and "N bytes written" ever looked
     * at the ciphertext. An export taken between a payload corruption and the next save or unlock
     * was therefore a bad backup, reported as an ordinary "Exported 4.5 MB" — the worst shape a
     * backup failure can take, because the user stops worrying (fixed 2026-09-13).
     *
     * Refused with the existing missing-or-damaged message rather than a new one: from the user's
     * side this is the same fact the unlock screen's `VAULT_UNREADABLE` states, and it points at
     * the same place — the snapshots further down this very screen.
     *
     * **Scoped to this one path on purpose.** The snapshot ring is deliberately NOT gated the same
     * way: `RollingBackupManager.snapshotNow` only ever runs straight after `AtomicVaultWriter`
     * re-sealed region 0 from memory, so it has no equivalent window — and the session that takes
     * a snapshot may be a decoy one, which cannot authenticate region 0 by construction. Gating the
     * ring on region 0 would stop every snapshot a decoy session takes and make the duress vault
     * visible by what the app failed to do. See [VaultLockManager.sessionAuthenticatesContainer],
     * which is region-addressed by session for the same reason.
     */
    suspend fun exportWithMasterPassword(destination: Uri): Outcome<ExportManifest> =
        withContext(Dispatchers.IO) {
            val fileBytes = when (val r = VaultLoader.loadFileBytes(context)) {
                is Outcome.Success -> r.value
                is Outcome.Failure -> return@withContext Outcome.Failure(r.error)
            }
            if (!vaultLockManager.sessionAuthenticatesContainer(fileBytes)) {
                return@withContext Outcome.Failure(
                    AppError.Crypto(
                        "vault file missing or structurally corrupt; backup recovery failed",
                    ),
                )
            }
            writeAndRecord(fileBytes, destination)
        }

    /**
     * Exports re-wrapped under an independent [exportPassphrase]. [vault]/[dek] are the
     * CURRENTLY unlocked vault's state (`VaultLockManager.lockState`) — under a duress
     * session this exports the decoy content, preserving the illusion (§8.3), with no
     * special-casing required here.
     */
    suspend fun exportWithPassphrase(
        destination: Uri,
        vault: Vault,
        dek: ByteArray,
        exportPassphrase: CharArray,
    ): Outcome<ExportManifest> = withContext(Dispatchers.IO) {
        try {
            val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
            val kdfParams = Argon2Calibrator.calibrate()
            // 2026-10-02: the ViewModel already catches KdfNeedsMoreMemoryException for this
            // derivation, and nothing ever threw it — the OutOfMemoryError (an Error) passed the
            // catch below and killed the process. Converted the way every in-app derivation is.
            val kek = try {
                deriveKek(exportPassphrase, salt, kdfParams)
            } catch (_: OutOfMemoryError) {
                throw KdfNeedsMoreMemoryException(
                    heapLimitMib = Runtime.getRuntime().maxMemory() / (1024 * 1024),
                )
            }
            val wrappedDek: WrappedDek
            val commitment: ByteArray
            try {
                wrappedDek = EnvelopeCrypto.wrapDek(kek, dek)
                commitment = EnvelopeCrypto.slotCommitment(dek)
            } finally {
                Zeroizer.wipe(kek)
            }

            val header = VaultHeaderCodec.create(salt, kdfParams, wrappedDek, commitment)
            val encKey = VaultSubkeys.encKey(dek)
            val fileBytes = try {
                // No Recovery Kit in an independent-passphrase export, deliberately. The whole
                // point of this export is that it is opened by a passphrase chosen for it and
                // nothing else — it is the copy that goes to cloud storage or a USB stick. Carrying
                // the kit across would mean that copy plus the 24 words on the user's paper is the
                // vault, quietly undoing the independence they asked for. The master-password
                // export copies the live file byte for byte and does carry it, which is right:
                // same file, same credentials.
                VaultFileCodec.encrypt(
                    vault,
                    encKey,
                    prevHeader = header,
                    recoveryBlock = VaultFileCodec.revokedRecoveryBlock(),
                )
            } finally {
                Zeroizer.wipe(encKey)
            }
            writeAndRecord(fileBytes, destination)
        } catch (e: KdfNeedsMoreMemoryException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failure(AppError.Crypto("failed to build independent-passphrase export", e))
        } finally {
            Zeroizer.wipe(exportPassphrase)
        }
    }

    /** The passphrase KEK derivation. A seam for tests; always [EnvelopeCrypto.deriveKek]. */
    internal var deriveKek: (CharArray, ByteArray, KdfParams) -> ByteArray = EnvelopeCrypto::deriveKek

    private fun writeAndRecord(fileBytes: ByteArray, destination: Uri): Outcome<ExportManifest> {
        return try {
            val written = writeToDestination(fileBytes, destination)
            BackupPreferences.recordExportNow(context)
            Outcome.Success(ExportManifest(written, Instant.now()))
        } catch (e: DeviceStorageFullException) {
            // Its own message, because the two failures send the user somewhere different: this
            // one means free space on the phone, and the one below means try another folder.
            // Reporting the destination for a staging failure sent people to change a destination
            // that was never the problem. No byte counts in the string — the numbers are on the
            // exception for a log, and the action ("free up space") does not depend on them.
            Outcome.Failure(AppError.Storage("not enough free space on this device to stage the export", e))
        } catch (e: Exception) {
            // Not `$destination`. This string is rendered and announced, and a `content://` URI
            // carries the provider authority and a document id — the folder someone chose, spoken
            // aloud and left on screen. It also tells the user nothing they can act on.
            Outcome.Failure(AppError.Storage("failed to write export to the chosen location", e))
        }
    }

    /**
     * Every blob the vault currently points at, as sources the bundle writer can stream.
     *
     * Filtered by [FileBlobStore.hasBlob] so a reference whose file has already gone — the dangling
     * state a half-finished remove used to leave behind — makes the export skip that one item's
     * media rather than fail the whole backup.
     */
    private fun blobSources(): List<VaultBundle.BlobSource> =
        blobGarbageCollector.referencedRefs()
            .filter(fileBlobStore::hasBlob)
            .map { ref ->
                VaultBundle.BlobSource(
                    ref = ref,
                    length = fileBlobStore.blobSizeBytes(ref),
                    open = { fileBlobStore.openEncrypted(ref) },
                )
            }

    /**
     * Writes the bundle to a local app-private temp file first, then copies the complete,
     * already-encrypted result to [destination] via SAF. SAF offers no atomic rename (§H) —
     * this ordering guarantees the destination is never left partially written by an
     * in-progress-encryption failure. The temp file holds ciphertext only.
     *
     * Returns the number of bytes written, which is now the whole bundle rather than the vault
     * file alone — the size shown to the user should be the size of what they actually have.
     */
    private fun writeToDestination(fileBytes: ByteArray, destination: Uri): Long {
        // `noBackupFilesDir`, not `cacheDir` and not `filesDir`. The platform is free to clear a
        // cache directory under storage pressure, and a large vault with media stages here for long
        // enough to be caught by it — the whole bundle is written before the SAF copy begins.
        // `filesDir` would be the obvious alternative and is the wrong one: the backup rules are an
        // allowlist over it (`<include domain="file" path="."/>`), so a staged export would be
        // eligible for cloud backup and device transfer, and a copy sealed under a passphrase the
        // user chose *for* an offline copy is the last thing that should ride along to Google.
        // `noBackupFilesDir` is persistent and excluded from both by construction.
        // Checked before staging, because staging needs room for a second complete copy of the
        // vault and every blob in it, on internal storage, whatever the destination is. Without
        // this, exporting a media-heavy vault to an SD card or Drive folder with plenty of space
        // failed at the ENOSPC with "couldn't write the export to the location you chose" — naming
        // the one place that was not out of room. The estimate is the sum of the parts, which is
        // what `VaultBundle.write` streams out; the margin covers the container framing rather than
        // trying to predict it exactly.
        val needed = fileBytes.size.toLong() + blobSources().sumOf(VaultBundle.BlobSource::length)
        val free = context.noBackupFilesDir.usableSpace
        // `0 ..<` rather than `1 ..<`: `usableSpace` returns 0 on a partition with no unallocated
        // bytes left, which is precisely the case this guard exists for — and the old lower bound
        // of 1 excluded it, so a completely full device sailed past the check and failed later,
        // mid-write, instead of here with a clear message. Every less-extreme low-space state was
        // caught; only "no space at all" was not.
        if (free in 0..<(needed + STAGING_MARGIN_BYTES)) {
            throw DeviceStorageFullException(needed = needed, available = free)
        }
        val tempFile = File.createTempFile("export", ".vault.tmp", context.noBackupFilesDir)
        try {
            FileOutputStream(tempFile).use { out ->
                VaultBundle.write(out, fileBytes, blobSources())
            }
            val out = context.contentResolver.openOutputStream(destination)
                // No URI in the message: this throwable becomes the `cause` on `AppError.Storage`,
                // and a cause is as renderable as the message itself.
                ?: error("could not open the export destination")
            try {
                out.use { stream -> tempFile.inputStream().use { it.copyTo(stream) } }
            } catch (e: Exception) {
                // The copy itself has no atomicity — a provider error or a full disk partway
                // through leaves a truncated file sitting in the user's file manager under the name
                // they chose, looking like a backup. That is worse than no backup at all: it is a
                // backup they will trust and that will not restore. Deleting it makes the failure
                // visible, which is what the "try again" the screen shows already assumes.
                //
                // Best effort — a provider may refuse the delete, and the original failure is the
                // one worth reporting either way.
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, destination) }
                throw e
            }
            return tempFile.length()
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Removes export stages left by a process death.
     *
     * The `finally` above is the only thing that deletes one, and a `finally` does not run when the
     * system kills the process — which it is free to do during the SAF copy, since that copy can be
     * an upload to a cloud provider and takes as long as the network does. What is left behind is
     * not a fragment: [VaultBundle.write] has already finished, so it is a **complete vault
     * container plus every referenced media blob**, sealed but sitting in app-private storage with
     * nothing that would ever clean it up. It survives every subsequent launch, and a second
     * interrupted export leaves a second one.
     *
     * `noBackupFilesDir` keeps them off cloud backup and device transfer, so this is about an
     * attacker who already holds the private directory (T2) rather than a stolen backup — but a
     * duplicate of the vault whose lifetime is "forever" is exactly what the rolling-backup sweep
     * exists to prevent elsewhere.
     *
     * Best effort and idempotent: called at startup, before anything can be exporting.
     */
    fun sweepStaleExportStages(): Int =
        context.noBackupFilesDir
            .listFiles { file -> file.isFile && file.name.startsWith("export") && file.name.endsWith(".vault.tmp") }
            .orEmpty()
            .count { it.delete() }

    private companion object {
        /**
         * Headroom demanded above the summed parts before staging begins.
         *
         * The estimate counts the vault file and every blob, which is what gets streamed, but not
         * the container framing around them, and `usableSpace` is itself a moment-in-time answer on
         * a device that may be writing elsewhere. Refusing slightly early is the cheap side of this
         * trade: the user frees some space and retries, where being slightly late means discovering
         * it at the ENOSPC, which is the failure this check exists to stop reporting badly.
         */
        const val STAGING_MARGIN_BYTES = 8L * 1024 * 1024
    }
}

/**
 * Internal storage cannot hold the staged copy the export has to write before it can copy
 * anything to the destination the user picked. Carries the numbers for a log; the message the
 * user sees is chosen by the caller and names the device, not the destination.
 */
internal class DeviceStorageFullException(val needed: Long, val available: Long) :
    java.io.IOException("staging needs $needed bytes, $available available")
