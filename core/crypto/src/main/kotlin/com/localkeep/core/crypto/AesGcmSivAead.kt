package com.localkeep.core.crypto

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMSIVBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/** AES-256-GCM-SIV via Bouncy Castle's low-level API. */
object AesGcmSivAead : Aead {
    override fun seal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        validateAeadInputs(key, nonce, plaintext.size, decrypting = false)
        return process(encrypting = true, key, nonce, plaintext, aad)
    }

    override fun open(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        validateAeadInputs(key, nonce, ciphertext.size, decrypting = true)
        return try {
            process(encrypting = false, key, nonce, ciphertext, aad)
        } catch (exception: InvalidCipherTextException) {
            throw AeadAuthException("GCM-SIV authentication failed", exception)
        }
    }

    private fun process(
        encrypting: Boolean,
        key: ByteArray,
        nonce: ByteArray,
        input: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val cipher = GCMSIVBlockCipher(AESEngine.newInstance())
        cipher.init(
            encrypting,
            AEADParameters(KeyParameter(key), CryptoConstants.AEAD_TAG_BITS, nonce, aad),
        )
        val output = ByteArray(cipher.getOutputSize(input.size))
        return try {
            var length = cipher.processBytes(input, 0, input.size, output, 0)
            length += cipher.doFinal(output, length)
            if (length == output.size) output else output.copyOf(length).also { Zeroizer.wipe(output) }
        } catch (exception: Throwable) {
            Zeroizer.wipe(output)
            throw exception
        }
    }
}
