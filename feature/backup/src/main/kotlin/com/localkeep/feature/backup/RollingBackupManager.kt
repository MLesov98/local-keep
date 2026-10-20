package com.localkeep.feature.backup

import android.content.Context
import android.util.Log
import com.localkeep.core.common.AppError
import com.localkeep.core.common.Outcome
import com.localkeep.core.crypto.VaultSubkeys
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.vault.RestoreOutcome
import com.localkeep.core.vault.StaleContainerSweeper
import com.localkeep.core.vault.VaultFileCodec
import com.localkeep.core.vault.VaultLoader
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.blobRefsOf
import com.localkeep.feature.files.FileBlobStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Rolling versioned local backups (§12.5 SHOULD): keeps the newest [MAX_SNAPSHOTS] timestamped
 * copies of `vault.enc` in an app-private `backups/` directory (excluded from Auto Backup —
 * see the XML rules in Task 8), taken after every successful [VaultRepository.onSaved].
 */
@Singleton
class RollingBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultRepository: VaultRepository,
    private val vaultLockManager: VaultLockManager,
    private val fileBlobStore: FileBlobStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    /** Begins collecting [VaultRepository.onSaved]. Safe to call more than once. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            vaultRepository.onSaved.collect {
                // Silence here would read as "the automatic snapshot happened" — the same failure
                // this function already surfaces below for a stuck prune. Never log the error's
                // message or cause: a write/rename failure can carry a path, and repo security law
                // forbids echoing that into a log.
                if (snapshotNow() is Outcome.Failure) {
                    Log.w(LOG_TAG, "automatic rolling backup snapshot failed after a vault save")
                }
            }
        }
    }

    suspend fun snapshotNow(): Outcome<File> = withContext(Dispatchers.IO) {
        val fileBytes = when (val r = VaultLoader.loadFileBytes(context)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return@withContext Outcome.Failure(r.error)
        }
        try {
            val dir = File(context.filesDir, BACKUPS_DIR).apply { mkdirs() }
            val base = TIMESTAMP_FORMAT.format(Instant.now())
            val snapshotFile = uniqueSnapshotFile(dir, base)
            // Write to a temp file, force it to the platter, then atomically rename — the same
            // three steps, in the same order, as AtomicVaultWriter. Process death mid-write was
            // already covered by the temp-and-rename: the rename never happens, so no truncated
            // file ever gets a snapshot's name. The fsync covers the case above that one. Without
            // it the bytes may still be sitting in the page cache when the rename returns, so a
            // power loss or a kernel panic in the seconds afterwards leaves a snapshot with the
            // right name, the right timestamp, and nothing much in it. That copy is not merely
            // useless — restore rejects it, and correctly — it is useless *and* counts against
            // MAX_SNAPSHOTS, so it evicts a good generation to take its place.
            val tmp = File(dir, snapshotFile.name + TMP_SUFFIX)
            var moveStarted = false
            try {
                withStagedSnapshotCleanup(tmp) {
                    FileOutputStream(tmp).use {
                        it.write(fileBytes)
                        it.fd.sync()
                    }
                    moveStarted = true
                    Files.move(
                        tmp.toPath(),
                        snapshotFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } catch (e: IOException) {
                return@withContext if (moveStarted) {
                    Outcome.Failure(
                        AppError.Storage(
                            "failed to finalize rolling backup snapshot ${snapshotFile.name}",
                            e,
                        ),
                    )
                } else {
                    Outcome.Failure(AppError.Storage("failed to write rolling backup snapshot", e))
                }
            }
            // A snapshot that was written is a snapshot the user has, so a prune that could not
            // remove an older generation does not fail it — but it is not swallowed either: those
            // generations stay on disk, and silence there reads as "cleanup happened".
            val undeleted = prune(dir, keep = snapshotFile)
            if (undeleted.isNotEmpty()) {
                Log.w(
                    LOG_TAG,
                    "kept ${undeleted.size} rolling backup(s) past the newest $MAX_SNAPSHOTS — " +
                        "could not delete ${undeleted.joinToString { it.name }}",
                )
            }
            Outcome.Success(snapshotFile)
        } catch (e: Exception) {
            Outcome.Failure(AppError.Storage("failed to write rolling backup snapshot", e))
        }
    }

    /** Newest first. */
    fun listSnapshots(): List<File> = snapshotsNewestFirst(File(context.filesDir, BACKUPS_DIR))

    /**
     * Restores [file] as the live vault via [VaultLockManager.restoreVaultFile] — serialized against
     * concurrent saves and validated before overwrite (a corrupt snapshot is rejected, not written).
     * Forces a re-lock on success.
     */
    suspend fun restoreSnapshot(file: File): Outcome<Unit> = withContext(Dispatchers.IO) {
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            // Literals on the three paths a user can act on carry no filename — not to hide the
            // name from this class, but because `AppError.toBackupMessage` matches on exact text
            // and an interpolated name can never match. All three used to embed one, so all three
            // fell to "Something went wrong. Please try again." — a retry offer for three
            // permanent conditions, none of which a retry changes. The name is the snapshot's own
            // `vault-<UTC stamp>.enc`, which is an internal storage id the UI does not show
            // anywhere, so nothing is lost by leaving it out.
            return@withContext Outcome.Failure(AppError.Storage("failed to read the chosen snapshot", e))
        }
        // Captured before `restoreVaultFile`, which force-locks (and wipes the live session's own
        // copy) on success — this is the only chance to read it. Used only to count blob refs the
        // restored snapshot's items point at that this device no longer has on disk (see
        // [missingBlobRefCount]). A password change or Recovery-Kit reset re-keys every rolling
        // snapshot to the new key (StaleContainerSweeper converts each one or deletes it), so a
        // snapshot normally decrypts with this DEK and, once restored, opens with the CURRENT master
        // password (see backup_restore_dialog_body). A container sealed under some other DEK simply
        // fails to decrypt here and the count comes back 0 — unknown, not wrong, and no worse than
        // an unqualified success for that case.
        val sessionDek = vaultLockManager.sessionSnapshotOrNull()?.dek?.copyOf()
        try {
            when (vaultLockManager.restoreVaultFile(bytes)) {
                RestoreOutcome.OK -> {
                    // Recorded here, not by the caller. `restoreVaultFile` locks the vault before it
                    // returns, and the lock pops the whole nav graph — which cancels the ViewModel
                    // scope this call is running in, so anything after the suspend call in
                    // BackupSettingsViewModel is never reached. This line is not a suspension point,
                    // so it still runs; MainActivity posts the notice after the next unlock.
                    BackupPreferences.setRestoreNoticePending(context, missingBlobRefCount(bytes, sessionDek))
                    Outcome.Success(Unit)
                }
                RestoreOutcome.INVALID_FILE ->
                    Outcome.Failure(AppError.Storage("that snapshot is not a valid vault file"))
                RestoreOutcome.CORRUPT ->
                    Outcome.Failure(AppError.Storage("that snapshot is corrupted and was not restored"))
                RestoreOutcome.NEWER_VERSION ->
                    Outcome.Failure(
                        AppError.Storage("that snapshot was written by a newer version of LocalKeep"),
                    )
            }
        } catch (e: Exception) {
            // Keeps its filename, and keeps falling through to the generic string on purpose:
            // nothing is known about this failure, so there is nothing specific to say about it,
            // and the exact-match table is what guarantees the name cannot reach the UI through a
            // partial match. The three cases above are the ones with a real answer.
            Outcome.Failure(AppError.Storage("failed to restore snapshot ${file.name}", e))
        } finally {
            Zeroizer.wipe(sessionDek)
        }
    }

    /**
     * Distinct blob refs the restored snapshot's items point at that this device does not have on
     * disk — the restore-side equivalent of [VaultImporter.ImportResult.missingBlobRefCount].
     *
     * Unlike an imported `.vault` bundle, a rolling snapshot is a byte copy of `vault.enc` alone —
     * "the rolling snapshot ring holds vault.enc only" (see [VaultImporter]'s cross-namespace
     * comment) — so it carries no media of its own; every item's attachment lives in the one
     * `blobs/` directory the whole app shares. Rolling back to an older generation can un-delete an
     * item whose attachment [BlobGarbageCollector] has since swept as orphaned once nothing current
     * referenced it, and until this count existed that landed as a dangling attachment behind an
     * unqualified "restored" — the snapshot equivalent of an import's silent success. Existence
     * only ([FileBlobStore.hasBlob]), not [FileBlobStore.authenticates]: a present-but-wrong-key
     * blob still opens the attachment viewer to a real (if wrong) error, which is a different, much
     * rarer condition than the file being gone outright.
     *
     * [dek] is null when locked and wiped on every other return path — see the call site — so this
     * never mutates or wipes it itself. Returns 0, not a thrown failure, for anything that stops the
     * snapshot's own region 0 from decrypting: a duress session's decoy DEK against the real region,
     * a pre-rotation snapshot sealed under an older DEK, or (orthogonally) a schema this build
     * predates. All three already have a place they are handled — the point of this function is
     * narrowly the "device is missing a file the vault references" case, and reporting "0 missing"
     * for a count that could not be computed is the same silence this whole system already accepted
     * for those cases, not a new one.
     */
    private fun missingBlobRefCount(fileBytes: ByteArray, dek: ByteArray?): Int {
        if (dek == null) return 0
        var encKey: ByteArray? = null
        return try {
            encKey = VaultSubkeys.encKey(dek)
            val vault = VaultFileCodec.decrypt(fileBytes, encKey).second
            blobRefsOf(vault).count { ref -> !fileBlobStore.hasBlob(ref) }
        } catch (_: Exception) {
            0
        } finally {
            Zeroizer.wipe(encKey)
        }
    }

    /** `vault-<ts>.enc`, or `vault-<ts>-<n>.enc` if that second already produced a snapshot — so two
     *  saves in the same UTC second never collide and silently overwrite each other's snapshot. */
    private fun uniqueSnapshotFile(dir: File, base: String): File {
        val first = File(dir, "vault-$base.enc")
        if (!first.exists() && !File(dir, first.name + TMP_SUFFIX).exists()) return first
        var n = 1
        while (true) {
            val f = File(dir, "vault-$base-$n.enc")
            if (!f.exists() && !File(dir, f.name + TMP_SUFFIX).exists()) return f
            n++
        }
    }

    companion object {
        // One source of truth: `:core:vault`'s StaleContainerSweeper has to find this directory
        // during a key rotation, and a second copy of the name is how the sweep would come to miss
        // it — leaving containers open to a retired password, which is the whole point of the sweep.
        private const val BACKUPS_DIR = StaleContainerSweeper.BACKUPS_DIR
        private const val TMP_SUFFIX = ".tmp"
        private const val LOG_TAG = "LocalKeepBackup"
        const val MAX_SNAPSHOTS = 5
        private val TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
        private val SNAPSHOT_NAME = Regex("""vault-(\d{8}-\d{6})(?:-(\d+))?\.enc""")

        internal fun snapshotsNewestFirst(dir: File): List<File> =
            dir.listFiles { f -> f.isFile && SNAPSHOT_NAME.matches(f.name) }
                ?.sortedWith(
                    // Sort on the PARSED (timestamp, collision-index) key, not the raw string, so
                    // the "-<n>" collision suffix orders chronologically instead of
                    // lexicographically.
                    compareByDescending<File> { sortKey(it).first }
                        .thenByDescending { sortKey(it).second },
                )
                ?: emptyList()

        private fun sortKey(f: File): Pair<String, Int> {
            val m = SNAPSHOT_NAME.matchEntire(f.name) ?: return "" to 0
            return m.groupValues[1] to (m.groupValues[2].toIntOrNull() ?: 0)
        }

        /**
         * When the snapshot was taken, read out of its own filename.
         *
         * Deliberately not `File.lastModified()`, which is what the list used to label rows with.
         * A master-password change re-keys every container beside the live vault, and
         * `StaleContainerSweeper.convert` does it by writing a sibling and renaming it over the
         * original — so every snapshot's mtime is reset to the moment of the rotation, all five at
         * once. A list whose only purpose is "go back to how it was at time T" then offered five
         * identical T's and no way to choose between them (fixed 2026-09-15).
         *
         * The name is the copy that survives, because the sweep renames each container back onto
         * the name it already had — the same UTC stamp [TIMESTAMP_FORMAT] wrote when the snapshot
         * was taken, which is also what [sortKey] already orders on.
         *
         * Null for a file whose name is not a snapshot name, so the caller decides what to fall
         * back to rather than being handed a wrong instant.
         */
        internal fun snapshotTakenAt(file: File): Instant? {
            val m = SNAPSHOT_NAME.matchEntire(file.name) ?: return null
            return try {
                TIMESTAMP_FORMAT.parse(m.groupValues[1], Instant::from)
            } catch (_: DateTimeParseException) {
                null
            }
        }

        /**
         * Deletes every snapshot older than the newest [MAX_SNAPSHOTS] and returns the ones that
         * refused to go — `File.delete()` reports a locked or unwritable file by returning false,
         * not by throwing, so the caller has to look at the result to know cleanup happened.
         * [delete] is a seam for tests; production always deletes for real.
         *
         * [keep] is the snapshot just written, and it is never pruned (2026-10-02). Names are
         * wall-clock stamps, so after the device clock moves back — a manual change, an RTC reset —
         * every new snapshot sorted below five later-stamped ones and was deleted the instant it was
         * written, while `snapshotNow` reported success. It counts as one of the [MAX_SNAPSHOTS], so
         * an ordinary save prunes exactly what it always did.
         */
        internal fun prune(
            dir: File,
            keep: File? = null,
            delete: (File) -> Boolean = File::delete,
        ): List<File> {
            val others = snapshotsNewestFirst(dir).filterNot { it.name == keep?.name }
            val room = if (keep?.isFile == true) MAX_SNAPSHOTS - 1 else MAX_SNAPSHOTS
            return others.drop(room).filterNot(delete)
        }
    }
}

internal inline fun <T> withStagedSnapshotCleanup(tmp: File, block: () -> T): T =
    try {
        block()
    } catch (t: Throwable) {
        tmp.delete()
        throw t
    }
