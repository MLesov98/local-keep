package com.localkeep.core.vault

import android.content.Context
import com.localkeep.core.crypto.WrappedDek
import com.localkeep.core.crypto.Zeroizer
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Brings every other vault container on disk onto the key the live vault just rotated to.
 *
 * `vault.enc` is not the only container this app writes. `AtomicVaultWriter` keeps the previous
 * generation as `vault.enc.bak`, and `RollingBackupManager` keeps the newest five saves under
 * `backups/`. Rotating the master password rewrote exactly one of those — so a user who changed
 * their password *because it leaked* was left with up to six containers that the leaked password
 * still opened, holding a vault at most a few saves old, while Settings told them "your old
 * Recovery Kit no longer works". Every route the rotation was supposed to close stayed open, and
 * the screen said otherwise.
 *
 * ## The invariant
 *
 * **After a rotation, no container under `filesDir` opens under a retired credential.** That is the
 * whole contract, and `StaleContainerSweepTest` asserts it directly by sweeping the directory and
 * trying the old key against everything in it.
 *
 * ## Convert, or destroy — never leave
 *
 * Each container is re-encrypted under the new key rather than deleted, because a rotation is a
 * terrible moment to take away someone's rollback: the snapshots exist for corruption and for the
 * accidental mass-delete, and both of those outlive a password change. Conversion keeps the safety
 * net and closes the hole.
 *
 * Anything that cannot be converted is **deleted**, not skipped. A container this build can no
 * longer read is either damaged or keyed to something even older, and in both cases leaving it is
 * the exact failure this class exists to end. Silence is not an option either — the counts come
 * back in [Result] so the UI can say what happened instead of implying nothing did.
 *
 * ## Why the header swap after sealing is safe
 *
 * The conversion mirrors [VaultLockManager.rotateDekUnderSlotZero]: seal the payload with the new
 * key against the *existing* header's AAD, then overwrite the slot block. That is sound because
 * [VaultHeaderCodec.payloadAad] deliberately covers everything except the slots — it is the two
 * ranges either side of them — so replacing slot 0 cannot invalidate a region that was already
 * sealed. Region 1 (the duress decoy) is never touched: its key is independent of the master
 * password and was not rotated, so re-keying it here would lock the duress password out of the one
 * region it exists to open.
 *
 * ## The second trigger: a replaced Recovery Kit
 *
 * [refreshRecoveryBlock] holds the same "no container beside the live vault outlives a retired
 * credential" line for the other way a credential is retired — minting a new kit — which does not
 * rotate anything and therefore cannot use [sweep]. See its own doc.
 */
object StaleContainerSweeper {

    /**
     * Directory holding the rolling snapshots.
     *
     * Public only so `:feature:backup`'s manager — which owns writing them — and this sweep, which
     * has to find them during a rotation, cannot disagree about where they live. [sweep] itself
     * stays internal: nothing outside `:core:vault` holds a region key.
     */
    const val BACKUPS_DIR: String = "backups"

    private const val TMP_SUFFIX = ".sweep.tmp"

    /**
     * @param converted containers re-encrypted under the new key and still usable.
     * @param destroyed containers that could not be converted and were deleted, by file name.
     * @param unreachable containers that could be neither converted nor deleted — the only way this
     *   class can fail to hold its invariant, so it is reported rather than folded into [destroyed].
     */
    internal data class Result(
        val converted: Int = 0,
        val destroyed: List<String> = emptyList(),
        val unreachable: List<String> = emptyList(),
    )

    /**
     * Re-keys every container beside the live vault.
     *
     * Never throws: a rotation that already committed to disk must not be reported as a failure
     * because a snapshot misbehaved. Per-file failure is contained to that file, which is then
     * deleted — or, when it could not even be read, reported in [Result.unreachable].
     *
     * "Never throws" covers `Error` as well as `Exception`, and did not until 2026-09-07: the sweep
     * reads whole containers into heap at the operation's high-water mark, and an `OutOfMemoryError`
     * walked straight through the `catch (_: Exception)` guards below. See the per-file guard in the
     * loop for what that cost the user.
     *
     * @param oldEncKey the region key retired by this rotation.
     * @param newEncKey the region key the live vault was just written under.
     * @param newSlots the slot block the live vault was just written with — slot 0 carries the new
     *   password's salt and wrapped DEK, slot 1 the untouched duress slot.
     */
    internal fun sweep(
        context: Context,
        oldEncKey: ByteArray,
        newEncKey: ByteArray,
        newSlots: List<UnlockSlot>,
    ): Result = sweep(candidates(context), backupsDir(context), oldEncKey, newEncKey, newSlots)

    /**
     * The sweep proper, over an already-resolved candidate list.
     *
     * Split out from the [Context]-taking overload above purely so the containment below can be
     * proven on the JVM: this module's unit tests run without Robolectric, so there is no `Context`
     * to hand the other overload, and every other test of this class needs a device.
     *
     * @param files the containers to sweep, in order.
     * @param backupsDir where half-written conversions are cleaned up from.
     */
    internal fun sweep(
        files: List<File>,
        backupsDir: File,
        oldEncKey: ByteArray,
        newEncKey: ByteArray,
        newSlots: List<UnlockSlot>,
    ): Result {
        var converted = 0
        val destroyed = mutableListOf<String>()
        val unreachable = mutableListOf<String>()

        try {
            for (file in files) {
                try {
                    when (classify(file, oldEncKey, newEncKey)) {
                        Disposition.ALREADY_CURRENT -> Unit
                        Disposition.CONVERT ->
                            if (convert(file, oldEncKey, newEncKey, newSlots)) {
                                converted++
                            } else {
                                if (file.delete()) destroyed += file.name else unreachable += file.name
                            }
                        Disposition.DESTROY ->
                            if (file.delete()) destroyed += file.name else unreachable += file.name
                    }
                } catch (_: OutOfMemoryError) {
                    // This whole class reads containers into heap — `classify` and `convert` each
                    // hold at least one full copy — and it runs *after* the rotation has already
                    // been written to disk, with the caller's own `currentBytes` and `updatedFile`
                    // still live. That is the high-water mark of the entire operation, on a minSdk
                    // 28 device whose heap growth limit is 48 MiB against a vault measured at
                    // ~8 MiB serialized (`VaultScaleTest`, 2026-07-29).
                    //
                    // The guards inside `classify` and `convert` are `catch (_: Exception)`, which
                    // does not catch this. Before 2026-09-07 it therefore escaped a function whose
                    // contract above is "Never throws", aborting the loop with every remaining
                    // container still open under the retired credential — and the caller answered a
                    // rotation that had *succeeded* with a generic failure, after which retrying
                    // the form checked the current password, which was by then the new one, and
                    // told the user their password was wrong. Twice told nothing had changed, while
                    // it had.
                    //
                    // Contained per file and reported as unreachable, which is exactly what
                    // unreachable means: neither converted nor destroyed, so the invariant is
                    // stated as not held for this file rather than silently assumed. Catching an
                    // `Error` is deliberate and has precedent here — `VaultLockManager.unlock`,
                    // `VaultLockManager.commit`, `Argon2Calibrator.fits` and
                    // `VaultRepository.searchIndex` all do it, on the same reasoning: the failed
                    // allocation is one container copy and becomes unreachable the moment it
                    // fails, so the heap recovers and the next file gets a fair attempt.
                    unreachable += file.name
                }
            }
        } finally {
            // A half-written conversion must never be mistaken for a snapshot. In `finally` because
            // an `Error` raised outside the per-file guard above — appending to these lists, or the
            // iteration itself — must not be the reason a `.sweep.tmp` is left behind: `convert`
            // deletes its temp only on the `Exception` path, so an `Error` mid-conversion leaves
            // one, and this is the only thing that removes it.
            runCatching {
                backupsDir.listFiles { f -> f.name.endsWith(TMP_SUFFIX) }?.forEach { it.delete() }
            }
        }

        return Result(converted, destroyed, unreachable)
    }

    /**
     * Carries a freshly minted Recovery Kit's wrapped key into every container beside the live vault.
     *
     * The kit path retires a credential without rotating anything: `commitPreparedRecoveryKit`
     * splices the new block into `vault.enc` and stops. But that write had just moved the previous
     * generation to `vault.enc.bak`, and `backups/` holds the newest five saves — each still
     * carrying the *retired* kit's block, wrapping the very same DEK. The 24 words the app had just
     * declared void therefore went on unwrapping the live data key out of a file sitting next to the
     * live vault. Measured 2026-09-13.
     *
     * Splices the new block in rather than stripping the old one out.
     * [VaultFileCodec.withoutRecoveryKit] is what a *rotation* wants, and it is wrong here: it would
     * kill the new kit in every snapshot too, so a user who restored one shortly after saving a kit
     * would find the words they had just written down dead, and `VaultLockManager.restoreVaultFile`
     * would correctly manufacture a no-kit debt on a vault whose kit they had only just saved.
     *
     * The splice is keyless — the block sits outside both payload regions and outside the payload
     * AAD ([VaultFileCodec.withRecoveryBlock]) — so this needs no password, no duress key and no
     * decryption, which is the same property that lets the live splice work with a decoy configured.
     *
     * Only containers whose region 0 still authenticates under [currentEncKey] are touched, which is
     * [classify]'s ALREADY_CURRENT test reused for this decision. One on an older key cannot be
     * carrying the retiring kit's block — a rotation strips it — and writing this block there would
     * install a dud that unwraps a key opening nothing. Anything unreadable is skipped, never
     * deleted: this runs *after* the kit has been committed to the live vault, so a misbehaving
     * snapshot must not cost the user data or be reported as a failed kit.
     *
     * @param wrappedDek the DEK sealed under the new kit's recovery KEK — the block to install.
     * @param currentEncKey the live region key, used only to recognise which containers share it.
     */
    internal fun refreshRecoveryBlock(
        context: Context,
        wrappedDek: WrappedDek,
        currentEncKey: ByteArray,
    ) {
        for (file in candidates(context)) {
            val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
            try {
                val bytes = file.readBytes()
                if (!VaultFileCodec.authenticatesRegion(bytes, currentEncKey, regionIndex = 0)) {
                    continue
                }
                // Temp, fsync, atomic rename — the same three steps, in the same order, as `convert`
                // above and as AtomicVaultWriter. A snapshot must never exist half-written.
                FileOutputStream(tmp).use {
                    it.write(VaultFileCodec.withRecoveryBlock(bytes, wrappedDek))
                    it.fd.sync()
                }
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                // Contained to this file, and the file itself is left exactly as it was.
            } catch (_: OutOfMemoryError) {
                // Same containment, and the same reasoning as the sweep loop above: two whole
                // container copies are live here, against a 48 MiB heap growth limit on this app's
                // own minSdk 28. The failed allocation becomes unreachable as the frame unwinds, so
                // the next file gets a fair attempt.
            } finally {
                // Deletes nothing on the success path — the rename consumed it — and removes a
                // half-written conversion on every other.
                tmp.delete()
            }
        }
    }

    /**
     * Carries a *removed* decoy out of every container beside the live vault.
     *
     * `removeDuress` rewrites region 1 of `vault.enc` with fresh filler under the real key and
     * stops — but that write had just moved the previous generation to `vault.enc.bak`, and
     * `backups/` holds the newest five saves, every one of them still carrying the decoy ciphertext
     * and (in the header) the duress unlock slot that opens it. Nothing swept them, so a decoy the
     * user had been told was gone survived beside the live vault until five ordinary saves had
     * pushed the last copy out of the ring. Measured 2026-09-13.
     *
     * The slot goes with the region, and both are replaced rather than removed: the header's slot 1
     * is overwritten with [VaultHeaderCodec.randomUnusedSlot] exactly as `removeDuress` does to the
     * live file, so the retired duress password no longer unwraps anything, and a snapshot restored
     * afterwards lands in the same no-decoy state the live vault is in rather than in a half-removed
     * one. Swapping a slot cannot invalidate an already-sealed region — [VaultHeaderCodec.payloadAad]
     * covers everything *except* the slots — which is the same property [convert] relies on.
     *
     * **Nothing is ever deleted here.** A `.bak` is the user's rollback from a corrupt live vault,
     * and this runs after a duress removal that has already committed, so a container that cannot be
     * rewritten must be left exactly as it was rather than destroyed. That is the opposite of
     * [sweep]'s convert-or-destroy rule, and deliberately: a retired *password* is a live route into
     * the real vault, while a retired decoy is only decoy plaintext to someone who already holds the
     * duress password and the files.
     *
     * Only containers that still hold a decoy are touched — region 0 authenticating under
     * [realEncKey] and region 1 *not* authenticating under it, which is exactly
     * `VaultLockManager.isDuressConfigured`'s test applied per file. A container whose region 1
     * already opens under the real key is filler and has nothing to remove; rewriting it would reuse
     * that region's key and nonce for no gain (see [VaultFileCodec.replaceRegionWithFiller]).
     *
     * @param realEncKey the live region key of the real vault, which is unchanged by a removal.
     */
    internal fun sweepRemovedDecoy(context: Context, realEncKey: ByteArray): Unit =
        sweepRemovedDecoy(candidates(context), realEncKey)

    /**
     * The pass proper, over an already-resolved candidate list.
     *
     * Split out from the [Context]-taking overload for the same reason [sweep] is: this module's
     * unit tests run without Robolectric, so there is no `Context` to hand the other overload, and
     * the decoy-removal invariant is exactly the kind that has to be provable on the JVM.
     */
    internal fun sweepRemovedDecoy(files: List<File>, realEncKey: ByteArray) {
        for (file in files) {
            val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
            var rewritten: ByteArray? = null
            try {
                val bytes = file.readBytes()
                if (!VaultFileCodec.authenticatesRegion(bytes, realEncKey, regionIndex = 0)) continue
                if (VaultFileCodec.authenticatesRegion(bytes, realEncKey, regionIndex = 1)) continue

                val header = VaultHeaderCodec.decode(bytes)
                rewritten = VaultFileCodec
                    .replaceRegionWithFiller(bytes, realEncKey, regionIndex = 1)
                    .also {
                        val slots = listOf(
                            header.slots[0],
                            VaultHeaderCodec.randomUnusedSlot(header.slots[0].kdfParams),
                        )
                        VaultHeaderCodec.encode(header.copy(slots = slots)).copyInto(it)
                    }
                // Refuse to install anything this build cannot read back — [convert]'s check, for
                // the same reason. A bug here would quietly replace the rollback set with
                // unopenable files, and the user would find out at the only moment one matters.
                check(VaultFileCodec.authenticatesRegion(rewritten, realEncKey, regionIndex = 0)) {
                    "resealed container no longer authenticates under the real key"
                }
                check(VaultFileCodec.authenticatesRegion(rewritten, realEncKey, regionIndex = 1)) {
                    "resealed filler does not authenticate under the real key"
                }

                // Temp, fsync, atomic rename — the same three steps, in the same order, as
                // [convert] and AtomicVaultWriter. A snapshot must never exist half-written.
                FileOutputStream(tmp).use {
                    it.write(rewritten)
                    it.fd.sync()
                }
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                // Contained to this file, and the file itself is left exactly as it was.
            } catch (_: OutOfMemoryError) {
                // Same containment and the same reasoning as the sweep loop above: two whole
                // container copies are live here, against a 48 MiB heap growth limit on this app's
                // own minSdk 28. The failed allocation becomes unreachable as the frame unwinds.
            } finally {
                tmp.delete()
                Zeroizer.wipe(rewritten)
            }
        }
    }

    /** What [resumeRemovedDecoySweep] found, and therefore what the caller owes the journal. */
    internal enum class ResumeOutcome {
        /** The walk ran to the end. The journal has been honoured and may be deleted. */
        SWEPT,

        /**
         * The live container has a decoy again, so the removal the journal describes no longer
         * describes the vault. Either the user configured a new decoy — which rewrote both regions
         * and both slots anyway — or they restored a snapshot from before the removal. Nothing is
         * swept, and the journal is spent rather than retried: retrying could only mean stripping a
         * decoy the user currently has.
         */
        SUPERSEDED,

        /**
         * The live container could not be read, so neither of the above is known. The journal must
         * be kept and the next unlock must try again — deleting it here would silently abandon the
         * walk on a failure that may not repeat.
         */
        UNKNOWN,
    }

    /**
     * Finishes a [sweepRemovedDecoy] that a process death interrupted.
     *
     * The decision is a **conjunction**, and it has to be. The journal alone says a removal started;
     * the live container alone says whether a decoy exists now. Acting on either one by itself is a
     * data-destroying bug, and the two ways it goes wrong are different enough to name:
     *
     * - **On the container alone**, "region 0 authenticates, region 1 does not" identifies both a
     *   removal leftover *and* every backup of a vault whose duress vault is configured right now.
     *   An unconditional pass erases a live decoy from `vault.enc.bak` and all five snapshots.
     * - **On a "the live file says duress is off" gate**, that is also true after the user restores
     *   a pre-duress snapshot over a vault that had duress — and the pass would then strip the
     *   decoys out of every remaining snapshot, destroying a setup nobody removed.
     *
     * Requiring both — a journal written by an actual removal, *and* a live container that still
     * reflects it — excludes both. The second check is also what makes a stale journal harmless:
     * a removal interrupted, then a pre-removal snapshot restored, leaves the journal on disk over a
     * vault whose decoy is live again, and that is exactly [ResumeOutcome.SUPERSEDED].
     *
     * @param liveBytes the current `vault.enc`.
     * @param files the containers beside it — `vault.enc.bak` and the snapshot ring.
     */
    internal fun resumeRemovedDecoySweep(
        liveBytes: ByteArray,
        files: List<File>,
        realEncKey: ByteArray,
    ): ResumeOutcome {
        val liveHasDecoy = try {
            !VaultFileCodec.authenticatesRegion(liveBytes, realEncKey, regionIndex = 1)
        } catch (_: Exception) {
            return ResumeOutcome.UNKNOWN
        } catch (_: OutOfMemoryError) {
            return ResumeOutcome.UNKNOWN
        }
        if (liveHasDecoy) return ResumeOutcome.SUPERSEDED
        sweepRemovedDecoy(files, realEncKey)
        return ResumeOutcome.SWEPT
    }

    /** Every container beside the live vault, for callers outside this file. */
    internal fun staleContainers(context: Context): List<File> = candidates(context)

    private enum class Disposition { ALREADY_CURRENT, CONVERT, DESTROY }

    /**
     * Every container beside the live vault, found by *location* rather than by name.
     *
     * Deliberately not the snapshot filename pattern: this is a security sweep, so anything a
     * future feature drops into `backups/` has to be swept too. A file there that turns out not to
     * be a container at all is classified [Disposition.DESTROY] below and removed — nothing else
     * has any business in that directory.
     */
    private fun candidates(context: Context): List<File> = buildList {
        val bak = File(context.filesDir, AtomicVaultWriter.BACKUP_FILE)
        if (bak.isFile) add(bak)
        backupsDir(context).listFiles { f -> f.isFile && !f.name.endsWith(TMP_SUFFIX) }
            ?.let(::addAll)
    }

    private fun backupsDir(context: Context) = File(context.filesDir, BACKUPS_DIR)

    private fun classify(file: File, oldEncKey: ByteArray, newEncKey: ByteArray): Disposition = try {
        val bytes = file.readBytes()
        when {
            // Already on the current key — a snapshot taken after this rotation, or one this sweep
            // already converted on an earlier pass. Rewriting it would be pointless churn.
            VaultFileCodec.authenticatesRegion(bytes, newEncKey, regionIndex = 0) ->
                Disposition.ALREADY_CURRENT
            VaultFileCodec.authenticatesRegion(bytes, oldEncKey, regionIndex = 0) ->
                Disposition.CONVERT
            // Opens under neither the outgoing key nor the incoming one: damaged, foreign, or keyed
            // to a credential retired even earlier — which is exactly the hole being closed.
            else -> Disposition.DESTROY
        }
    } catch (_: Exception) {
        Disposition.DESTROY
    }

    /** True when [file] now holds the same vault state, sealed under [newEncKey]. */
    private fun convert(
        file: File,
        oldEncKey: ByteArray,
        newEncKey: ByteArray,
        newSlots: List<UnlockSlot>,
    ): Boolean {
        var rekeyed: ByteArray? = null
        val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
        return try {
            val bytes = file.readBytes()
            val (header, vault) = VaultFileCodec.decrypt(bytes, oldEncKey)
            rekeyed = VaultFileCodec.withoutRecoveryKit(
                VaultFileCodec
                    .replaceRegion(
                        fileBytes = bytes,
                        vault = vault,
                        encKey = newEncKey,
                        regionIndex = 0,
                    )
                    .also { VaultHeaderCodec.encode(header.copy(slots = newSlots)).copyInto(it) },
            )
            // Refuse to install anything this build cannot read back. Without it a conversion bug
            // would quietly replace five working snapshots with five unopenable ones, and the user
            // would find out at the only moment a snapshot matters.
            check(VaultFileCodec.authenticatesRegion(rekeyed, newEncKey, regionIndex = 0)) {
                "re-keyed container does not authenticate under the new key"
            }

            // Temp, fsync, atomic rename — the same three steps, in the same order, as
            // AtomicVaultWriter and RollingBackupManager. A snapshot must never exist half-written.
            FileOutputStream(tmp).use {
                it.write(rekeyed)
                it.fd.sync()
            }
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        } catch (_: Exception) {
            tmp.delete()
            false
        } finally {
            Zeroizer.wipe(rekeyed)
        }
    }
}
