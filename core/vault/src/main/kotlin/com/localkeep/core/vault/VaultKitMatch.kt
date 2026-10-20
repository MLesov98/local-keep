package com.localkeep.core.vault

import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.WrappedDek
import com.localkeep.core.crypto.Zeroizer

/**
 * The one mistake the Recovery-phrase screen can catch outright: someone typing *this vault's own*
 * Recovery Kit words into a Recovery-phrase item, which would put the key to the vault inside the
 * vault.
 *
 * The vault never stores its mnemonic (see [RecoveryKit] — it "never touches the device, only the
 * user's paper"), so there is nothing to string-compare against. What the vault *does* hold is the
 * DEK sealed under a key derived from those words ([VaultRecoveryBlock]'s wrapped DEK). So the test
 * is behavioural, not textual: derive the recovery KEK from the entered words and see whether it
 * unwraps that block. It can only succeed for the one 24-word phrase this vault was set up with.
 *
 * Fail-open by construction: any objection — wrong length, bad checksum, no recovery block on this
 * vault, a wrong key — returns false, i.e. "not the kit, allow the save". The refusal is a safety
 * catch on an easy footgun, not a security boundary, so when it cannot be sure it does not block.
 */
object VaultKitMatch {
    /**
     * True iff [words] are the mnemonic that unwraps [wrappedDek] — this vault's own Recovery Kit.
     *
     * @param wrappedDek the vault's recovery-block wrapped DEK, or null if this vault has no
     *   recovery block (then there is nothing to be, and the answer is false).
     */
    fun isVaultRecoveryKit(words: List<String>, wrappedDek: WrappedDek?): Boolean {
        if (wrappedDek == null) return false
        // A Recovery Kit is always 24 words; anything else cannot be one, and deriveRecoveryKek
        // would reject it anyway. Cheap gate before the Argon-free but still non-trivial HKDF.
        if (words.size != 24 || words.any { it.isBlank() }) return false

        val kek = try {
            RecoveryKit.deriveRecoveryKek(words.joinToString(" "))
        } catch (_: Exception) {
            // Not valid BIP-39 / not 24 words after canonicalisation — cannot be the kit.
            return false
        }
        return try {
            val dek = EnvelopeCrypto.unwrapDek(kek, wrappedDek)
            // It unwrapped, so these words open this vault's recovery block. Do not keep the DEK.
            Zeroizer.wipe(dek)
            true
        } catch (_: Exception) {
            // AeadAuthException (wrong key) is the common, expected case: a perfectly valid but
            // unrelated seed phrase. Any other failure is treated the same — fail open.
            false
        } finally {
            Zeroizer.wipe(kek)
        }
    }
}
