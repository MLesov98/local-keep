package com.localkeep.core.vault

import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.WrappedDek

/**
 * The data key, wrapped under a key derived from the Recovery Kit's 24 words, stored in the vault
 * file itself.
 *
 * **Why this exists.** Before it, a kit was the words *plus* the wrapped key, and the wrapped key
 * existed only inside the QR code. The PDF — the export the app pushes hardest, and the one people
 * actually keep — carried the words alone, so it could not open anything. Putting the wrapped key
 * in the vault means the words are the whole kit, which is what every other manager does and what
 * the printed page has always claimed.
 *
 * **Why it is appended to the container rather than added to the header.** The obvious home is a
 * third [UnlockSlot]. It cannot go there. `VaultHeaderCodec.payloadAad` projects the format
 * version, the slot and region counts, and both region descriptors into the AAD that authenticates
 * the payload. Adding a slot changes the count and shifts both regions, so every byte of both
 * payloads would have to be resealed — and region 1 holds the duress vault under a key that only a
 * duress unlock ever derives. A header change would therefore destroy the decoy of every user who
 * has configured one. The same argument rules out bumping the header's format version, which is why
 * this block carries a version of its own.
 *
 * **Why living outside the AAD is safe.** The block is not authenticated by the container, and does
 * not need to be. Stripping it removes the ability to recover — a denial, available to anyone who
 * can already delete the file. Substituting another vault's block yields a foreign data key, which
 * fails the slot-0 commitment check in `VaultLockManager.unlockWithRecoveryKit` and is rejected.
 * Forging one means forging AES-GCM under a key derived from 256 bits of entropy. None of those is
 * a path to the plaintext.
 *
 * **Why it is always present.** A vault with no kit carries a block of CSPRNG noise, so the file's
 * length and shape say nothing about whether a Recovery Kit exists — the same construction the
 * duress slot already uses for the same reason. "No kit" and "wrong words" are the same failure: an
 * AEAD tag that does not verify.
 */
internal object VaultRecoveryBlock {
    private const val VERSION: Int = 1
    private val MAGIC = "LKRC".toByteArray(Charsets.US_ASCII)

    private const val WRAPPED_LEN = CryptoConstants.KEY_LEN + CryptoConstants.AEAD_TAG_LEN

    /** Magic, version, nonce, wrapped DEK. Fixed, so the container stays a constant shape. */
    const val ENCODED_SIZE: Int =
        4 + 1 + CryptoConstants.AEAD_NONCE_LEN + WRAPPED_LEN

    fun encode(wrappedDek: WrappedDek): ByteArray {
        require(wrappedDek.nonce.size == CryptoConstants.AEAD_NONCE_LEN) {
            "recovery nonce must be ${CryptoConstants.AEAD_NONCE_LEN} bytes"
        }
        require(wrappedDek.ciphertext.size == WRAPPED_LEN) {
            "wrapped recovery DEK must be $WRAPPED_LEN bytes"
        }
        return MAGIC + byteArrayOf(VERSION.toByte()) + wrappedDek.nonce + wrappedDek.ciphertext
    }

    /**
     * Reads the block at [offset], or null when there is nothing usable there.
     *
     * Null covers three cases the caller treats identically: a container written before this block
     * existed, a block of noise standing in for "no kit", and a corrupted one. All three mean "no
     * Recovery Kit here", and none of them should be distinguishable to someone holding the file.
     */
    fun decode(bytes: ByteArray, offset: Int): WrappedDek? {
        if (offset < 0 || bytes.size - offset < ENCODED_SIZE) return null
        for (index in MAGIC.indices) {
            if (bytes[offset + index] != MAGIC[index]) return null
        }
        if ((bytes[offset + 4].toInt() and 0xff) != VERSION) return null
        var position = offset + 5
        val nonce = bytes.copyOfRange(position, position + CryptoConstants.AEAD_NONCE_LEN)
        position += CryptoConstants.AEAD_NONCE_LEN
        val ciphertext = bytes.copyOfRange(position, position + WRAPPED_LEN)
        return WrappedDek(nonce, ciphertext)
    }

    /**
     * A block that decodes to nothing: uniformly random across its whole length, magic included.
     *
     * Deliberately not "valid magic wrapping garbage". A reader that could tell "a kit was here and
     * was revoked" from "there has never been a kit" would leak exactly the fact the noise is
     * there to hide.
     */
    fun random(): ByteArray = SecureRandomProvider.nextBytes(ENCODED_SIZE)
}
