package com.localkeep.core.crypto

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Stock-JCE AES-256-GCM for single-shot DEK wrapping. */
object AesGcmAead : Aead {
    override fun seal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        validateAeadInputs(key, nonce, plaintext.size, decrypting = false)
        return cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext)
    }

    override fun open(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        validateAeadInputs(key, nonce, ciphertext.size, decrypting = true)
        return try {
            cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(ciphertext)
        } catch (exception: AEADBadTagException) {
            throw AeadAuthException("GCM authentication failed", exception)
        }
    }

    private fun cipher(
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
    ): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(
            mode,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(CryptoConstants.AEAD_TAG_BITS, nonce),
        )
        updateAAD(aad)
    }
}
