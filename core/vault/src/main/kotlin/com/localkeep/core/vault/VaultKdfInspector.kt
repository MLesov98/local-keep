package com.localkeep.core.vault

import android.content.Context
import com.localkeep.core.crypto.Argon2
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.common.Outcome

/**
 * Reads what the vault currently costs to open, without opening it.
 *
 * The two halves of the re-benchmark design.md §11.1 asks for: what the header says, and what that
 * actually costs on this hardware. Kept out of [VaultLockManager] because neither needs a session,
 * a key, or the operation mutex — this is a measurement, not an operation, and a measurement that
 * had to take the vault's lock would be a measurement nobody could take while anything else ran.
 */
object VaultKdfInspector {

    /** The Argon2id parameters in the live vault's header, or null if there is no readable vault. */
    fun storedParams(context: Context): KdfParams? = when (
        val bytes = VaultLoader.loadFileBytes(context)
    ) {
        is Outcome.Success -> runCatching {
            VaultHeaderCodec.decode(bytes.value).slots[0].kdfParams
        }.getOrNull()
        is Outcome.Failure -> null
    }

    /**
     * Milliseconds to derive one key at [params] on this device, right now.
     *
     * Uses a throwaway password and salt rather than the user's: the number wanted is the cost of
     * the *parameters*, which does not depend on the secret, and a benchmark is no place to handle
     * one. Both are wiped regardless.
     */
    fun timeDerivation(params: KdfParams): Long {
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val probe = "calibration-probe".toCharArray()
        var key: ByteArray? = null
        return try {
            val start = System.nanoTime()
            key = Argon2.deriveKey(probe, salt, params)
            (System.nanoTime() - start) / 1_000_000
        } finally {
            Zeroizer.wipe(key)
            Zeroizer.wipe(probe)
            Zeroizer.wipe(salt)
        }
    }
}
