package com.localkeep.core.vault

import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams

/**
 * The one mutation a decoy session is allowed to make to the vault container: re-wrapping the decoy
 * data key in slot 1, under a password the user has just chosen.
 *
 * Separated from [VaultLockManager.changeDuressPassword] so the invariant can be *proved* rather
 * than argued. The manager needs a `Context`, a lock state and an Argon2id run; this needs a byte
 * array and a key, which is what this module's plain-JVM tests can hand it. The property that has
 * to hold — nothing outside slot 1 moves — is a property of these bytes, so this is where it is
 * asserted. Same reasoning as `AppSignatureCache.groupPairs`, which was split out from its
 * DataStore read for the same reason.
 *
 * It is safe to rewrite the header alone because `VaultHeaderCodec.payloadAad` deliberately
 * excludes the unlock slots from the AAD that authenticates the payload: changing which password
 * unwraps a key therefore invalidates no ciphertext. That is the property that already lets a real
 * master-password change avoid decrypting region 1 under a duress password it does not hold, and it
 * is what makes the reverse safe here.
 */
internal object DuressSlotRewrap {

    /**
     * [fileBytes] with slot 1 rebuilt so that [newKek] unwraps [decoyDek], and every other byte of
     * the container carried across unchanged.
     *
     * The commitment is copied from the existing slot rather than recomputed: it binds the data
     * key, and the data key has not moved. The KDF parameters come from slot 1's own copy, which
     * `VaultHeaderCodec` requires to equal slot 0's — [KdfParams] parity is re-validated by
     * `encode` below, so a container that had drifted is refused here rather than written.
     *
     * @throws IllegalArgumentException if [fileBytes] is not a container this build can decode.
     */
    fun rewrap(
        fileBytes: ByteArray,
        newSalt: ByteArray,
        newKek: ByteArray,
        decoyDek: ByteArray,
    ): ByteArray {
        val header = VaultHeaderCodec.decode(fileBytes)
        VaultFileCodec.validateContainer(header, fileBytes.size)
        val previous = header.slots[1]
        val updated = header.copy(
            slots = listOf(
                header.slots[0],
                UnlockSlot(
                    salt = newSalt,
                    kdfParams = previous.kdfParams,
                    wrappedDek = EnvelopeCrypto.wrapDek(newKek, decoyDek),
                    commitment = previous.commitment,
                ),
            ),
        )
        // Encoded first, so the codec's own size and slot-parity checks run before anything is
        // spliced into a copy of the live container.
        val encoded = VaultHeaderCodec.encode(updated)
        return fileBytes.copyOf().also { encoded.copyInto(it) }
    }
}
