package com.localkeep.feature.unlock

import android.content.Context
import com.localkeep.core.crypto.AeadAuthException
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KeyCommitment
import com.localkeep.core.crypto.VaultSubkeys
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.vault.UnsupportedVaultFormatException
import com.localkeep.core.vault.VaultLoader
import com.localkeep.core.vault.VaultFileCodec
import com.localkeep.core.vault.VaultHeaderCodec
import java.io.File

/**
 * What trying one candidate container against one password established.
 *
 * Deliberately carries no bytes and no key: the caller already holds the bytes it handed in, and
 * nothing on this screen ever needs the DEK. A pre-unlock screen that produced a usable key would
 * be a second unlock path, which is not what this is — this proves the user can open the backup,
 * and that is all it is allowed to prove.
 */
internal enum class BackupCheck {
    /** Slot 0 opened AND region 0 authenticated: this file is a vault this password unlocks. */
    OPENS,

    /** Slot 0 did not open under this password. */
    WRONG_PASSWORD,

    /**
     * The file is not a container, or it is one whose payload will not authenticate.
     *
     * Kept apart from [WRONG_PASSWORD] for the reason repo law gives everywhere else: mapping an
     * I/O or parser failure to "wrong password" sends the user off retyping something that was
     * never the problem — and here it would send them off retyping it at the one screen that
     * exists because retyping does not help.
     */
    UNREADABLE,

    /**
     * A well-formed container from a build newer than this one.
     *
     * Restoring it would not help and the app cannot read it, so it is refused before the password
     * is even tried — the same split `UnlockResult.VAULT_FROM_NEWER_VERSION` makes at unlock.
     */
    NEWER_VERSION,
}

/**
 * The pre-unlock half of the restore flow: everything that must happen *before* a single byte of
 * the live container is touched.
 *
 * Both functions here exist to enforce one rule, which is the whole security argument of this
 * screen: **no destructive action happens until the user has proved they can open the candidate,
 * and even then the container being replaced is copied aside first.** Anyone holding the phone can
 * reach the unlock screen, so a restore route that wrote first and asked later would hand a thief a
 * one-tap vault wipe.
 */
internal object PreUnlockRestore {
    /**
     * Where the damaged copies go.
     *
     * A directory rather than siblings in `filesDir`, for one reason that is not cosmetic: Android
     * matches `<exclude domain="file">` by canonical path and does no prefix matching, so a
     * timestamped copy sitting beside the real one could never be excluded from Auto Backup, and
     * every restore would push a dead ciphertext copy into the user's cloud backup set — under a
     * 25 MB cap that the live vault also has to fit inside. One directory is one exclude line,
     * which the two XML rule files under `app/src/main/res/xml` and
     * `LocalKeepBackupAgent.EXCLUDED_PATHS` now carry (and `BackupInclusionSetTest` pins in both
     * directions). `VaultLoader.quarantineContainers` takes the directory as a parameter for the
     * same reason: the module that owns those XML rules is the one that has to name it.
     *
     * Not `backups/`, which would have been the obvious home: `StaleContainerSweeper` re-keys every
     * container in there after a password change and **deletes** the ones it cannot convert. A
     * damaged container is by definition one it cannot convert, so the quarantine would be swept
     * away by the first password change after the restore.
     */
    const val QUARANTINE_DIR: String = "damaged"

    /**
     * Copies every committed container aside under [stamp] — true only if nothing was left behind.
     *
     * A thin adapter over [VaultLoader.quarantineContainers], which owns the container filenames
     * and the copy. Two reasons it is not called directly from the view-model: the caller needs a
     * verdict rather than an exception, because "false" maps to one sentence the user reads and an
     * `IOException` maps to nothing; and having one function makes the ordering rule — quarantine
     * first, write second — a single thing to read and a single thing to test.
     *
     * **Copies, and does not move.** Moving is tidier and costs no space, and it is wrong: between
     * the move and the write the app has *no* container at all, and a failure in that window leaves
     * `VaultLoader.hasVaultFiles` false — which routes the next launch to onboarding, where creating
     * a new vault writes over the space the damaged one used to occupy.
     *
     * All three are copied, not just `vault.enc`. Which of them is the recoverable remnant is not
     * knowable here: the `.bak` is a whole previous generation and the `.tmp` is a fsynced save that
     * never got renamed, and either can be the better one when the primary is what got damaged.
     * `AtomicVaultWriter.write` rotates `vault.enc` onto `.bak` with `REPLACE_EXISTING`, so
     * restoring destroys the old `.bak` outright; this is the only moment it can be kept.
     */
    fun preserveDamagedContainers(context: Context, stamp: String): Boolean =
        try {
            VaultLoader.quarantineContainers(context, QUARANTINE_DIR, stamp)
            true
        } catch (_: Exception) {
            false
        }

    /**
     * The rolling snapshot ring, newest first, read straight off disk.
     *
     * `RollingBackupManager.listSnapshots()` in `:feature:backup` answers the same question and was
     * the first implementation of this. It cannot be used: that module is flavoured (`foss`/`play`)
     * and this one is not, so the dependency is a variant-ambiguity failure rather than a design
     * choice — and resolving it either pins the unlock screen to one flavour of the other module or
     * gives this module a flavour dimension it has no other use for. Reading the directory needs
     * only `StaleContainerSweeper.BACKUPS_DIR`, which `:core:vault` makes public precisely so the
     * writer and the sweeper "cannot disagree about where they live".
     *
     * Filters on `.enc` rather than on the `vault-<stamp>.enc` name pattern: the suffix excludes the
     * sweeper's `.enc.sweep.tmp` half-written temporaries — the only other thing that lives there —
     * while still offering a snapshot written under some older naming scheme, which the pattern
     * would silently hide from a user who has nothing else left. Ordered by mtime, which is also
     * what the rows are labelled with, so the list cannot claim an order the labels contradict.
     */
    fun snapshotsNewestFirst(backupsDir: File): List<File> =
        backupsDir
            .listFiles { f -> f.isFile && f.name.endsWith(".enc") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    /**
     * Whether [password] opens [containerBytes] — header slot 0 only, payload authenticated.
     *
     * Two halves, and both are load-bearing:
     *
     * - **Slot 0 only.** Never slot 1. Trying the decoy slot would make this screen the one place
     *   in the app that answers the question the duress vault exists to leave unanswerable, and it
     *   would do it to whoever is holding the phone. The user is restoring *their* vault with
     *   *their* master password; a decoy password answering "that didn't open this backup" here is
     *   indistinguishable from any other wrong password, which is the required behaviour.
     * - **Region 0 is authenticated, not just unwrapped.** A right password against a container
     *   whose ciphertext is damaged unwraps the DEK and matches the commitment perfectly well —
     *   `VaultLockManager.SlotTrial` splits exactly this case out for the same reason. Restoring a
     *   backup that passes the key check and then will not open is the failure this whole screen
     *   exists to prevent, so the check has to reach the payload.
     *
     * Consumes and wipes [password], like `VaultImporter.readVault` and
     * `VaultLockManager.reauthenticate` do. Every intermediate key is wiped on every path.
     */
    fun check(containerBytes: ByteArray, password: CharArray): BackupCheck {
        try {
            val header = try {
                VaultHeaderCodec.decode(containerBytes)
            } catch (_: UnsupportedVaultFormatException) {
                return BackupCheck.NEWER_VERSION
            } catch (_: Exception) {
                return BackupCheck.UNREADABLE
            }
            val slot = header.slots.getOrNull(0) ?: return BackupCheck.UNREADABLE
            var kek: ByteArray? = null
            var dek: ByteArray? = null
            var commitKey: ByteArray? = null
            var encKey: ByteArray? = null
            try {
                kek = EnvelopeCrypto.deriveKek(password, slot.salt, slot.kdfParams)
                dek = try {
                    EnvelopeCrypto.unwrapDek(kek, slot.wrappedDek)
                } catch (_: AeadAuthException) {
                    return BackupCheck.WRONG_PASSWORD
                }
                commitKey = VaultSubkeys.commitKey(dek)
                if (!KeyCommitment.verify(commitKey, slot.commitment)) {
                    return BackupCheck.WRONG_PASSWORD
                }
                encKey = VaultSubkeys.encKey(dek)
                return if (VaultFileCodec.authenticatesRegion(containerBytes, encKey, regionIndex = 0)) {
                    BackupCheck.OPENS
                } else {
                    BackupCheck.UNREADABLE
                }
            } catch (_: OutOfMemoryError) {
                // Argon2id's cost parameter is a heap allocation, and this derivation runs with the
                // *candidate's* parameters — which can be larger than anything this device has ever
                // had to satisfy, because the backup may have come from a stronger phone. An Error
                // rather than an Exception, so the catch above cannot see it; uncaught it would
                // crash the app on a file the user picked. Reported as unreadable-on-this-device
                // rather than as a wrong password, which is the one thing it certainly is not.
                return BackupCheck.UNREADABLE
            } catch (_: Exception) {
                return BackupCheck.UNREADABLE
            } finally {
                Zeroizer.wipe(kek)
                Zeroizer.wipe(dek)
                Zeroizer.wipe(commitKey)
                Zeroizer.wipe(encKey)
            }
        } finally {
            Zeroizer.wipe(password)
        }
    }
}
