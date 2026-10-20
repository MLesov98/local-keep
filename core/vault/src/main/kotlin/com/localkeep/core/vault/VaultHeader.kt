package com.localkeep.core.vault

import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.VersionedHeader
import com.localkeep.core.crypto.WrappedDek

data class UnlockSlot(
    val salt: ByteArray,
    val kdfParams: KdfParams,
    val wrappedDek: WrappedDek,
    val commitment: ByteArray,
) {
    init {
        require(salt.size == CryptoConstants.SALT_LEN) { "slot salt must be 16 bytes" }
        require(wrappedDek.nonce.size == CryptoConstants.AEAD_NONCE_LEN) {
            "wrapped DEK nonce must be 12 bytes"
        }
        require(
            wrappedDek.ciphertext.size ==
                CryptoConstants.KEY_LEN + CryptoConstants.AEAD_TAG_LEN,
        ) { "wrapped DEK ciphertext must be 48 bytes" }
        require(commitment.size == COMMITMENT_SIZE) { "slot commitment must be 32 bytes" }
    }

    override fun equals(other: Any?): Boolean =
        other is UnlockSlot &&
            salt.contentEquals(other.salt) &&
            kdfParams == other.kdfParams &&
            wrappedDek == other.wrappedDek &&
            commitment.contentEquals(other.commitment)

    override fun hashCode(): Int {
        var result = salt.contentHashCode()
        result = 31 * result + kdfParams.hashCode()
        result = 31 * result + wrappedDek.hashCode()
        result = 31 * result + commitment.contentHashCode()
        return result
    }

    companion object {
        const val COMMITMENT_SIZE: Int = 32
    }
}

data class PayloadRegion(
    val nonce: ByteArray,
    val offset: Int,
    val length: Int,
) {
    init {
        require(nonce.size == CryptoConstants.AEAD_NONCE_LEN) {
            "payload nonce must be 12 bytes"
        }
        require(offset >= 0) { "payload offset must be non-negative" }
        require(length >= 0) { "payload length must be non-negative" }
    }

    override fun equals(other: Any?): Boolean =
        other is PayloadRegion &&
            nonce.contentEquals(other.nonce) &&
            offset == other.offset &&
            length == other.length

    override fun hashCode(): Int {
        var result = nonce.contentHashCode()
        result = 31 * result + offset
        result = 31 * result + length
        return result
    }
}

data class VaultHeader(
    val versioned: VersionedHeader,
    val slots: List<UnlockSlot>,
    val regions: List<PayloadRegion>,
) {
    init {
        require(slots.size == NUM_SLOTS) { "vault header must contain two unlock slots" }
        require(regions.size == NUM_REGIONS) { "vault header must contain two payload regions" }
    }

    companion object {
        const val NUM_SLOTS: Int = 2
        const val NUM_REGIONS: Int = 2
    }
}
