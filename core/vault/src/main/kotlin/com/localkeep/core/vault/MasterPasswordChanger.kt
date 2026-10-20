package com.localkeep.core.vault

import com.localkeep.core.crypto.KdfParams

data class ChangeResult(
    val success: Boolean,
    val oldRecoveryKitVoided: Boolean = false,
    /**
     * Local backup copies re-encrypted under the new key, so they survive the rotation and still
     * restore. See [StaleContainerSweeper] — before it, these kept opening under the retired
     * password while Settings claimed the old credentials no longer worked.
     */
    val staleContainersReKeyed: Int = 0,
    /** Copies this build could no longer read, deleted rather than left open to an old password. */
    val staleContainersDestroyed: Int = 0,
    /**
     * Copies that could be neither re-keyed nor deleted — the one case where a retired password
     * still opens something on disk. Non-zero means the user has to be told plainly.
     */
    val staleContainersLeftBehind: Int = 0,
    /**
     * Stored files, photos and attachments re-encrypted under the new key. Blobs are keyed off the
     * DEK, so before this pass existed a password change made every one of them permanently
     * unreadable — silently. See [BlobReKeyStore].
     */
    val blobsReKeyed: Int = 0,
    /**
     * Blobs the pass could not re-key on this attempt. They stay in the rotation journal and are
     * retried on the next unlock; the retired key is kept (wrapped under the new one) until they
     * land. Non-zero is not yet data loss, but it is the only state in which a stored file is
     * temporarily unreadable.
     */
    val blobsLeftBehind: Int = 0,
)

object MasterPasswordChanger {
    /**
     * Changes the master password, rotating the data key with it. Every other route to the old key
     * therefore dies: any Recovery Kit ([ChangeResult.oldRecoveryKitVoided] reports it) and the
     * device-wrapped copy behind biometric unlock, which has to be enrolled again.
     */
    suspend fun changePassword(
        lockManager: VaultLockManager,
        currentPassword: CharArray,
        newPassword: CharArray,
    ): ChangeResult = lockManager.changeMasterPassword(currentPassword, newPassword)

    /**
     * The same act inside a decoy session: re-wraps the **decoy** key under a new password, in the
     * duress slot, and touches neither the real slot nor either payload region.
     *
     * Kept apart from [changePassword] rather than folded into it behind an `isDuress` read. The
     * two are different operations — one rotates the data key and sweeps every container on the
     * device, the other rewrites 120 bytes of header — and the guard that keeps each inside its
     * own session lives in `VaultLockManager`, where the session is. See
     * [VaultLockManager.changeDuressPassword] for what it deliberately does not do.
     */
    suspend fun changeDecoyPassword(
        lockManager: VaultLockManager,
        currentPassword: CharArray,
        newPassword: CharArray,
    ): ChangeResult = lockManager.changeDuressPassword(currentPassword, newPassword)

    /**
     * Sets a new master password for a user who has forgotten the old one, proving authority with
     * the Recovery Kit's 24 words instead. Rotates the data key like [changePassword] does, so the
     * kit just used stops working and a new one is needed — see [ChangeResult.oldRecoveryKitVoided].
     */
    suspend fun setPasswordWithRecoveryKit(
        lockManager: VaultLockManager,
        mnemonic: String,
        newPassword: CharArray,
    ): ChangeResult = lockManager.setMasterPasswordWithRecoveryKit(mnemonic, newPassword)

    // `rotateDekAndReEncrypt` used to sit here — a `suspend fun` over
    // `VaultLockManager.rotateRealDek`, offering "rotate the data key without changing the
    // password". It was **deleted on 2026-09-13** because it
    // had no production caller and could not acquire one: spec §4.4's two-tier design was
    // superseded on 2026-07-27, every password change rotates the key now, and no screen in the app
    // offers a bare rotation. This object is the public face of the operations Settings performs,
    // so a public entry point here reads as a capability the app has — and the next caller to reach
    // for it would have been reaching for a path nothing exercises outside a test. The rule here
    // is "unreachable code hides defects; wire it or delete it", and wiring it would be inventing a
    // feature nobody asked for.
    //
    // `VaultLockManager.rotateRealDek` itself is still there, `internal`, called only by the
    // rotation-invariant tests in this module's `androidTest`. Removing it too takes
    // `rotateDekUnderSlotZero`'s `biometricCipher` branch with it, which is a change to live crypto
    // and wants a device run; it is a tracked follow-up rather than done as a side effect here.

    /**
     * Re-derives slot 0 under [newParams], keeping the same password and the same key
     * (design.md §11.1's re-benchmark on restore to different hardware).
     *
     * Grouped with the two above because this object is the public face of every operation that
     * re-derives slot 0, not only the ones that change a password. This one retires nothing: the
     * Recovery Kit, biometric unlock and the local snapshots all keep working, which is exactly why
     * it is not a rotation.
     */
    suspend fun recalibrateKdf(
        lockManager: VaultLockManager,
        currentPassword: CharArray,
        newParams: KdfParams,
    ): RewrapOutcome = lockManager.rewrapKdfParams(currentPassword, newParams)
}
