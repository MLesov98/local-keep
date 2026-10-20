package com.localkeep.credential

import com.localkeep.core.crypto.AeadAuthException
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.WrappedBytes
import com.localkeep.core.crypto.Zeroizer
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec

/** Software P-256 keys, wrapped by the vault session DEK for portable encrypted backup. */
internal object PasskeyCrypto {
    data class GeneratedPasskey(
        val publicKey: ECPublicKey,
        val wrappedPrivateKey: ByteArray,
    )

    fun generateAndWrap(dek: ByteArray): GeneratedPasskey {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val pair = generator.generateKeyPair()
        val encoded = pair.private.encoded
        return try {
            GeneratedPasskey(
                publicKey = pair.public as ECPublicKey,
                wrappedPrivateKey = serialize(EnvelopeCrypto.wrapBytes(dek, encoded, AAD)),
            )
        } finally {
            Zeroizer.wipe(encoded)
        }
    }

    @Throws(AeadAuthException::class)
    fun unwrap(
        dek: ByteArray,
        blob: ByteArray,
    ): ByteArray {
        if (
            blob.size <= HEADER_SIZE + CryptoConstants.AEAD_TAG_LEN ||
            !blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) ||
            blob[MAGIC.size] != VERSION
        ) {
            throw AeadAuthException("invalid wrapped passkey")
        }
        val nonceStart = MAGIC.size + 1
        val ciphertextStart = nonceStart + CryptoConstants.AEAD_NONCE_LEN
        val wrapped = WrappedBytes(
            nonce = blob.copyOfRange(nonceStart, ciphertextStart),
            ciphertext = blob.copyOfRange(ciphertextStart, blob.size),
        )
        return EnvelopeCrypto.unwrapBytes(dek, wrapped, AAD)
    }

    fun privateKeyFromPkcs8(pkcs8: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

    fun publicKeyCoordinates(publicKey: ECPublicKey): Pair<ByteArray, ByteArray> =
        fixedUnsigned(publicKey.w.affineX) to fixedUnsigned(publicKey.w.affineY)

    private fun serialize(wrapped: WrappedBytes): ByteArray =
        MAGIC + byteArrayOf(VERSION) + wrapped.nonce + wrapped.ciphertext

    private fun fixedUnsigned(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val trimmed =
            if (raw.size > COORDINATE_SIZE) {
                raw.copyOfRange(raw.size - COORDINATE_SIZE, raw.size)
            } else {
                raw
            }
        return ByteArray(COORDINATE_SIZE).also {
            trimmed.copyInto(it, destinationOffset = COORDINATE_SIZE - trimmed.size)
        }
    }

    private val MAGIC = byteArrayOf('L'.code.toByte(), 'K'.code.toByte(), 'P'.code.toByte(), 'K'.code.toByte())
    private const val VERSION: Byte = 1
    private const val COORDINATE_SIZE = 32
    private const val HEADER_SIZE = 4 + 1 + CryptoConstants.AEAD_NONCE_LEN
    private val AAD = "LocalKeep passkey private key v1".toByteArray(Charsets.UTF_8)
}
