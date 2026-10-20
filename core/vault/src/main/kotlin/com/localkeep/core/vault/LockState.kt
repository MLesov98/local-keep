package com.localkeep.core.vault

import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.model.Vault

sealed interface LockState {
    data object Locked : LockState

    data class Unlocked(
        val dek: ByteArray,
        val vault: Vault,
        val isDuress: Boolean = false,
        /**
         * This session was opened with the Recovery Kit's 24 words rather than the master password.
         *
         * Carried so the vault list can say so once. The user has just proven they do *not* know the
         * master password, and the next lock will ask for it again — but nothing told them the reset
         * exists, and it sits two levels deep behind a row whose summary reads "Needs your current
         * one", which reads as "not for you" to exactly the person it is for (G3, 2026-09-11).
         *
         * Session state, never persisted: it dies with the unlock, which is the whole point — the
         * note is for the session that came through the kit, not for every session afterwards.
         */
        val openedWithRecoveryKit: Boolean = false,
    ) : LockState {
        init {
            require(dek.size == CryptoConstants.KEY_LEN) { "session DEK must be 256 bits" }
        }

        override fun equals(other: Any?): Boolean =
            other is Unlocked &&
                dek.contentEquals(other.dek) &&
                vault == other.vault &&
                isDuress == other.isDuress &&
                openedWithRecoveryKit == other.openedWithRecoveryKit

        override fun hashCode(): Int {
            var result = dek.contentHashCode()
            result = 31 * result + vault.hashCode()
            result = 31 * result + isDuress.hashCode()
            result = 31 * result + openedWithRecoveryKit.hashCode()
            return result
        }

        override fun toString(): String =
            "Unlocked(itemCount=${vault.items.size}, folderCount=${vault.folders.size}, " +
                "isDuress=$isDuress)"
    }
}

/**
 * Why an unlock attempt ended the way it did.
 *
 * The failure cases used to be a single `FAILURE`, which the unlock screen rendered as "Wrong
 * password" — so a cancelled fingerprint, a vault file that would not decode, and an actually
 * mistyped password all accused the user of the same thing. That is worse than unhelpful: told
 * their password is wrong, a user retypes it, fails again, and concludes they have lost the vault.
 *
 * Distinguishing them costs nothing here and lets the UI say what happened. It leaks nothing an
 * attacker does not already have — someone holding the device can observe all three states
 * directly.
 */
enum class UnlockResult {
    /** Opened with the real key. */
    REAL,

    /** Opened the duress vault. Indistinguishable from [REAL] to anyone watching. */
    DURESS,

    /** The credential did not match. The only case that means "you typed it wrong". */
    WRONG_CREDENTIAL,

    /**
     * The vault file is missing, truncated, or its header will not decode.
     *
     * Retyping the password cannot fix this, so saying "wrong password" sends the user in exactly
     * the wrong direction — what they need is the backup-restore path.
     */
    VAULT_UNREADABLE,

    /**
     * The container is intact but declares a vault format this build does not know.
     *
     * Split from [VAULT_UNREADABLE] because the remedy is the opposite one. "Unreadable" points the
     * user at restoring a backup; doing that to a vault that is merely *newer* than the installed
     * app overwrites good data with old data and cannot be undone. What they need is to update
     * LocalKeep — usually because the app was reinstalled from an older source, or the vault was
     * restored from a device running a newer build.
     */
    VAULT_FROM_NEWER_VERSION,

    /**
     * The vault's stored Argon2id cost is larger than this device will give one app.
     *
     * The password is very probably right — this is not a guess that failed, and it must never be
     * reported as one. A vault carries the KDF parameters chosen when it was created, and derives
     * with them wherever it goes; Argon2id's cost parameter *is* a heap allocation, and Android caps
     * an ordinary app at `dalvik.vm.heapgrowthlimit` (48 MiB on API 28, 192 MiB on API 35). So a
     * vault created on a strong phone and restored onto a weaker one asks for memory that device
     * will not give, and the derivation dies rather than failing.
     *
     * Retyping cannot fix it and neither can the Recovery Kit, which derives through the same KDF.
     * `rewrapKdfParams` is the remedy and it requires an unlock first — so as of 2026-07-29 the
     * honest answer is "open it on a device with more memory", and a real recovery path is recorded
     * as a follow-up rather than pretended at here.
     */
    VAULT_NEEDS_MORE_MEMORY,

    /**
     * The derivation ran out of heap, but this vault's own KDF cost is within
     * [com.localkeep.core.crypto.KdfParams.PORTABLE_MAX_MEMORY_KIB] — so it fits this device, and
     * what was full is the *process*, not the phone.
     *
     * Split from [VAULT_NEEDS_MORE_MEMORY] on 2026-09-11. Both came back as "open it on a phone
     * with more memory, then lower its encryption settings" — advice that is true for a vault built
     * beyond the portable ceiling and false, alarming and unactionable for this case. Measured on an
     * API 29 device with a 48 MiB growth limit: after half an hour of ordinary use the process sat
     * at 34 MiB and a **three-item** vault made on that same device refused to open three times in a
     * row; `am force-stop` and the identical password opened it in seconds. The user was being told
     * to buy a different phone for a vault that device had created, and the one thing that works —
     * close the app fully and reopen it — was never suggested.
     *
     * No automatic retry sits behind this. Retrying the derivation in place would mean holding the
     * plaintext password past the `finally` that wipes it, and a transient allocation failure is not
     * worth keeping a master password alive in the heap for. The remedy is named in the copy instead.
     */
    VAULT_MEMORY_TRANSIENT,

    /**
     * The biometric cipher could not unwrap the device key: prompt cancelled or dismissed, or the
     * Keystore key invalidated by a fingerprint being added or removed.
     *
     * Says nothing about the master password, and must never be reported as if it did.
     */
    BIOMETRIC_UNAVAILABLE,

    /**
     * The vault was already open when the attempt was made — something other than this screen
     * unlocked it. The credential provider activity shares this manager, so answering an autofill
     * or passkey request from another app does exactly that.
     *
     * Not an error: the user asked for an open vault and the vault is open. This used to be an
     * `IllegalStateException` that surfaced to the unlock screen as "Unlock failed" for a correct
     * master password, with no way out but waiting for the idle timer to re-lock.
     */
    ALREADY_UNLOCKED,

    /**
     * A lock landed while the key was still being derived, so the session this attempt belonged to
     * no longer exists. Argon2 takes long enough for an auto-lock to fall inside it.
     *
     * Retyping the password is exactly the right response — which is why this cannot be
     * [VAULT_UNREADABLE], whose message sends the user to restore a backup over a healthy vault.
     */
    INTERRUPTED,
    ;

    /** True when the vault is now open, by either key. */
    val opened: Boolean get() = this == REAL || this == DURESS || this == ALREADY_UNLOCKED
}

class VaultLockedException(
    val retryAfterMs: Long,
) : IllegalStateException("too many failed attempts; retry later")

/**
 * The duress password offered is the vault's real master password.
 *
 * Its own type rather than a bare `IllegalArgumentException` for the reason
 * [VaultSessionEndedException] gives: it is neither a bug nor unknowable, and answering it with
 * "That didn't go through. Try again." sends the user round a loop that reproduces the same failure
 * forever. There is no oracle argument for withholding the reason — this path is reachable only
 * from an authenticated real session, and the duress rows are withheld entirely in a decoy one.
 * Found by the F14 QA persona, 2026-08-30.
 */
class DuressPasswordCollisionException :
    IllegalArgumentException("duress password must differ from the master password")

/**
 * A lock landed part-way through an operation, so the session it was working against is gone.
 *
 * Distinct from a bare `IllegalStateException` because it is not a bug and it is not unknowable: it
 * happens when a long derivation — changing the master password runs three of them — overlaps a
 * background grace of five seconds on the Aggressive preset. The abort is clean, and the user needs
 * to be told that rather than "Operation could not be completed", which reads like data loss.
 */
class VaultSessionEndedException(
    val operation: String,
) : IllegalStateException("vault locked during $operation")

/**
 * The vault no longer fits in this device's heap, so it could not be written.
 *
 * The whole vault is one encrypted blob: every save serializes all of it, encrypts all of it, and
 * writes all of it. At 10,000 items that is ~8 MiB serialized and several times that live, and
 * Android caps an ordinary app at `dalvik.vm.heapgrowthlimit` — 192 MiB on the API 35 image, but
 * **48 MiB on API 28**, this app's own minSdk. Measured 2026-07-29: at 48 MiB the save dies growing
 * an 8 MiB buffer with 2.6 MiB free.
 *
 * Distinct from every other save failure because the honest advice is the opposite one. The generic
 * message tells the user their changes are still there and to try again; here retrying will fail
 * identically, every time, until the vault holds less. Telling someone to retry a thing that cannot
 * succeed is the worse of the two lies available.
 *
 * An `IllegalStateException` subclass so existing `catch (e: Exception)` sites keep working, and so
 * a caller that has nothing specific to say still says something rather than crashing.
 */
/**
 * A key derivation that runs *inside* the open app — duress setup, a master-password change, a
 * KDF re-wrap — could not get the memory Argon2id asks for. Unlock fits on the same device because
 * nothing else is resident yet; these run beside the UI, and on a 48 MiB heap even the FLOOR rung
 * does not fit in what is left. The `OutOfMemoryError` used to surface as the generic "That
 * didn't go through. Try again." (device, 2026-09-14), which is advice that reproduces the failure.
 * Nothing on disk has been touched when this is thrown.
 */
class KdfNeedsMoreMemoryException(
    val heapLimitMib: Long,
) : IllegalStateException("key derivation does not fit in this device's ${heapLimitMib} MiB heap cap")

class VaultTooLargeForDeviceException(
    val heapLimitMib: Long,
) : IllegalStateException("vault does not fit in this device's ${heapLimitMib} MiB heap cap")

/**
 * A save cannot be written in place because the vault has outgrown the fixed payload bucket its
 * container was built around, so [VaultFileCodec.replaceRegion] cannot fit the new bytes.
 *
 * Like [VaultTooLargeForDeviceException] the honest advice is not the generic "try again" — this
 * exact save fails identically until the serialized vault is smaller. It is distinct, though,
 * because it *is* recoverable by ordinary means: removing items brings the payload back under the
 * bucket. Its user-facing message deliberately says only that and never why the bucket is fixed,
 * because the fixed bucket is a consequence of a configured decoy vault and naming that would
 * defeat the deniability the decoy exists to provide.
 *
 * An [IllegalStateException] subclass so existing `catch (e: Exception)` sites keep working.
 */
class VaultBucketExhaustedException :
    IllegalStateException("vault no longer fits its shared payload bucket")
