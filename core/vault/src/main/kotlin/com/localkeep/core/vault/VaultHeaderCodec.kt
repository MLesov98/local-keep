package com.localkeep.core.vault

import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.KdfParamsCodec
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.VersionedHeader
import com.localkeep.core.crypto.WrappedDek
import java.io.ByteArrayOutputStream

/**
 * A container whose header is perfectly well-formed but declares a format this build predates.
 *
 * A subclass of [IllegalArgumentException] so every existing `catch` keeps working, but a distinct
 * type so the ones that care can tell it apart. That distinction is the whole point: "written by a
 * newer LocalKeep" and "damaged" are opposite situations, and the advice for the second — restore
 * an older backup over it — destroys the vault in the first.
 */
class UnsupportedVaultFormatException(val formatVersion: Int) : IllegalArgumentException(
    "unsupported vault format version: $formatVersion",
)

/** Fixed-size version-1 vault header codec with a stable structural payload-AAD projection. */
object VaultHeaderCodec {
    private const val SLOT_SIZE = 120
    private const val REGION_SIZE = 20
    const val ENCODED_SIZE: Int =
        VersionedHeader.ENCODED_SIZE +
            2 +
            VaultHeader.NUM_SLOTS * SLOT_SIZE +
            VaultHeader.NUM_REGIONS * REGION_SIZE

    fun encode(header: VaultHeader): ByteArray {
        validateAlgorithms(header.versioned)
        validateSlotKdfParity(header)
        val output = ByteArrayOutputStream(ENCODED_SIZE)
        output.write(header.versioned.encode())
        output.write(VaultHeader.NUM_SLOTS)
        output.write(VaultHeader.NUM_REGIONS)
        header.slots.forEach { writeSlot(output, it) }
        header.regions.forEach { writeRegion(output, it) }
        return output.toByteArray().also {
            check(it.size == ENCODED_SIZE) { "vault header must encode to $ENCODED_SIZE bytes" }
        }
    }

    fun decode(bytes: ByteArray): VaultHeader {
        require(bytes.size >= ENCODED_SIZE) {
            "vault header truncated: expected $ENCODED_SIZE bytes"
        }
        val versioned = VersionedHeader.decode(bytes)
        return when (versioned.formatVersion) {
            CryptoConstants.FORMAT_VERSION_1 -> decodeVersion1(bytes, versioned)
            else -> throw UnsupportedVaultFormatException(versioned.formatVersion)
        }
    }

    fun create(
        salt: ByteArray,
        kdfParams: KdfParams,
        wrappedDek: WrappedDek,
        commitment: ByteArray,
    ): VaultHeader {
        val placeholder = PayloadRegion(
            nonce = ByteArray(CryptoConstants.AEAD_NONCE_LEN),
            offset = 0,
            length = 0,
        )
        return VaultHeader(
            versioned = VersionedHeader(
                formatVersion = CryptoConstants.FORMAT_VERSION_1,
                kdfAlgId = CryptoConstants.KDF_ARGON2ID,
                aeadAlgId = CryptoConstants.AEAD_AES256_GCM_SIV,
            ),
            slots = listOf(
                UnlockSlot(salt, kdfParams, wrappedDek, commitment),
                randomUnusedSlot(kdfParams),
            ),
            regions = listOf(placeholder, placeholder),
        )
    }

    /**
     * Stable payload AAD: format/count prefix plus both region descriptors.
     *
     * Unlock slots are excluded because changing a password only re-wraps a DEK. Authenticating
     * mutable slots as payload AAD would force both independently keyed regions to be decrypted
     * during every password or duress-slot change. Slot integrity is independently enforced by
     * AES-GCM DEK wrapping and constant-time DEK commitment verification.
     */
    fun payloadAad(header: VaultHeader): ByteArray {
        val encoded = encode(header)
        return encoded.copyOfRange(0, SLOTS_OFFSET) +
            encoded.copyOfRange(REGIONS_OFFSET, ENCODED_SIZE)
    }

    internal fun randomUnusedSlot(kdfParams: KdfParams): UnlockSlot = UnlockSlot(
        salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN),
        kdfParams = kdfParams,
        wrappedDek = WrappedDek(
            nonce = SecureRandomProvider.nextBytes(CryptoConstants.AEAD_NONCE_LEN),
            ciphertext = SecureRandomProvider.nextBytes(
                CryptoConstants.KEY_LEN + CryptoConstants.AEAD_TAG_LEN,
            ),
        ),
        commitment = SecureRandomProvider.nextBytes(UnlockSlot.COMMITMENT_SIZE),
    )

    private fun decodeVersion1(
        bytes: ByteArray,
        versioned: VersionedHeader,
    ): VaultHeader {
        validateAlgorithms(versioned)
        var position = VersionedHeader.ENCODED_SIZE
        val slotCount = bytes[position++].toInt() and 0xff
        val regionCount = bytes[position++].toInt() and 0xff
        require(
            slotCount == VaultHeader.NUM_SLOTS &&
                regionCount == VaultHeader.NUM_REGIONS,
        ) { "unsupported slot/region count: $slotCount/$regionCount" }

        val slots = ArrayList<UnlockSlot>(slotCount)
        repeat(slotCount) {
            val decoded = readSlot(bytes, position)
            position = decoded.first
            slots += decoded.second
        }

        val regions = ArrayList<PayloadRegion>(regionCount)
        repeat(regionCount) {
            val decoded = readRegion(bytes, position)
            position = decoded.first
            regions += decoded.second
        }
        check(position == ENCODED_SIZE) { "vault header decode size mismatch" }
        return VaultHeader(versioned, slots, regions).also(::validateSlotKdfParity)
    }

    private fun validateAlgorithms(versioned: VersionedHeader) {
        require(versioned.formatVersion == CryptoConstants.FORMAT_VERSION_1) {
            "unsupported vault format version: ${versioned.formatVersion}"
        }
        require(versioned.kdfAlgId == CryptoConstants.KDF_ARGON2ID) {
            "unsupported KDF algorithm: ${versioned.kdfAlgId}"
        }
        require(versioned.aeadAlgId == CryptoConstants.AEAD_AES256_GCM_SIV) {
            "unsupported payload AEAD algorithm: ${versioned.aeadAlgId}"
        }
    }

    private fun validateSlotKdfParity(header: VaultHeader) {
        require(header.slots[0].kdfParams == header.slots[1].kdfParams) {
            "unlock slots must use equal KDF parameters"
        }
    }

    private fun writeSlot(output: ByteArrayOutputStream, slot: UnlockSlot) {
        output.write(slot.salt)
        output.write(KdfParamsCodec.encode(slot.kdfParams))
        output.write(slot.wrappedDek.nonce)
        output.write(slot.wrappedDek.ciphertext)
        output.write(slot.commitment)
    }

    private fun writeRegion(output: ByteArrayOutputStream, region: PayloadRegion) {
        output.write(region.nonce)
        writeInt(output, region.offset)
        writeInt(output, region.length)
    }

    private fun readSlot(
        bytes: ByteArray,
        start: Int,
    ): Pair<Int, UnlockSlot> {
        var position = start
        val salt = bytes.copyOfRange(position, position + CryptoConstants.SALT_LEN)
        position += CryptoConstants.SALT_LEN
        val kdfParams = KdfParamsCodec.decode(bytes, position)
        position += KdfParamsCodec.ENCODED_SIZE
        val nonce = bytes.copyOfRange(position, position + CryptoConstants.AEAD_NONCE_LEN)
        position += CryptoConstants.AEAD_NONCE_LEN
        val ciphertext = bytes.copyOfRange(
            position,
            position + CryptoConstants.KEY_LEN + CryptoConstants.AEAD_TAG_LEN,
        )
        position += ciphertext.size
        val commitment = bytes.copyOfRange(
            position,
            position + UnlockSlot.COMMITMENT_SIZE,
        )
        position += commitment.size
        return position to UnlockSlot(
            salt = salt,
            kdfParams = kdfParams,
            wrappedDek = WrappedDek(nonce, ciphertext),
            commitment = commitment,
        )
    }

    private fun readRegion(
        bytes: ByteArray,
        start: Int,
    ): Pair<Int, PayloadRegion> {
        var position = start
        val nonce = bytes.copyOfRange(position, position + CryptoConstants.AEAD_NONCE_LEN)
        position += CryptoConstants.AEAD_NONCE_LEN
        val offset = readInt(bytes, position)
        position += Int.SIZE_BYTES
        val length = readInt(bytes, position)
        position += Int.SIZE_BYTES
        return position to PayloadRegion(nonce, offset, length)
    }

    private fun writeInt(output: ByteArrayOutputStream, value: Int) {
        output.write(value ushr 24)
        output.write(value ushr 16)
        output.write(value ushr 8)
        output.write(value)
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private const val SLOTS_OFFSET = VersionedHeader.ENCODED_SIZE + 2
    private const val REGIONS_OFFSET =
        SLOTS_OFFSET + VaultHeader.NUM_SLOTS * SLOT_SIZE
}
