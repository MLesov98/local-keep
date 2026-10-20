package com.localkeep.core.vault

import cash.z.ecc.android.bip39.Mnemonics.MnemonicCode
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.Hkdf
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.WrappedDek
import com.localkeep.core.crypto.Zeroizer
import java.text.Normalizer
import java.util.Locale

data class RecoveryKitData(
    val mnemonic: String,
    val wrappedDek: WrappedDek,
)

/**
 * BIP-39 recovery material.
 *
 * The two halves have opposite rules and it matters which is which. [RecoveryKitData.mnemonic] is
 * the secret: it never touches the device, only the user's paper. [RecoveryKitData.wrappedDek] is
 * the DEK sealed under a key derived from those words, and it *is* stored, in the vault container
 * — it is useless without them, and keeping it anywhere else is what left the printed kit unable
 * to open anything. See [VaultRecoveryBlock].
 */
object RecoveryKit {
    private const val RECOVERY_LABEL = "localkeep/v1/recovery-kek"
    private const val QR_HEADER = "LOCALKEEP-RECOVERY"
    private const val ENTROPY_BYTES = 32

    fun generate(dek: ByteArray): RecoveryKitData {
        require(dek.size == CryptoConstants.KEY_LEN) { "DEK must be 256 bits" }
        val entropy = SecureRandomProvider.nextBytes(ENTROPY_BYTES)
        val mnemonicCode = MnemonicCode(entropy)
        var recoveryKek: ByteArray? = null
        return try {
            val mnemonic = mnemonicCode.chars.concatToString()
            recoveryKek = deriveRecoveryKekFromEntropy(entropy)
            RecoveryKitData(
                mnemonic = mnemonic,
                wrappedDek = EnvelopeCrypto.wrapDek(recoveryKek, dek),
            )
        } finally {
            mnemonicCode.clear()
            Zeroizer.wipe(entropy)
            Zeroizer.wipe(recoveryKek)
        }
    }

    /** Validates the words and checksum before deriving the recovery KEK. */
    fun deriveRecoveryKek(mnemonic: String): ByteArray {
        val canonical = canonicalMnemonic(mnemonic)
        val mnemonicCode = MnemonicCode(canonical)
        var entropy: ByteArray? = null
        return try {
            entropy = mnemonicCode.toEntropy()
            require(entropy.size == ENTROPY_BYTES) { "recovery mnemonic must contain 24 words" }
            deriveRecoveryKekFromEntropy(entropy)
        } finally {
            mnemonicCode.clear()
            Zeroizer.wipe(entropy)
        }
    }

    /**
     * The kit as QR payload: a header line and the words.
     *
     * It used to append the wrapped DEK, because unlocking needed both and the vault file held
     * neither. The vault file now carries the wrapped key (see [VaultRecoveryBlock]), so including
     * it here would be a second copy of key material on a screen the user is about to photograph
     * with another device — for no gain. The words are the kit.
     */
    fun formatForQrCode(mnemonic: String): String = "$QR_HEADER\n${canonicalMnemonic(mnemonic)}"

    /** The mnemonic from a QR payload, or null if it isn't one or fails its checksum. */
    fun parseQrCode(qrData: String): String? {
        val lines = qrData.trim().lines()
        if (lines.size != 2 || lines[0] != QR_HEADER) return null
        val mnemonic = try {
            canonicalMnemonic(lines[1])
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try {
            val code = MnemonicCode(mnemonic)
            try {
                val entropy = code.toEntropy()
                try {
                    if (entropy.size != ENTROPY_BYTES) null else mnemonic
                } finally {
                    Zeroizer.wipe(entropy)
                }
            } finally {
                code.clear()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The unlock screen promises "Capitals don't matter", and this is what has to make that true.
     *
     * Whitespace was already collapsed; case was left to the BIP-39 library, which is not ours to
     * assume. Every word in the English wordlist is lowercase, so lowercasing here can only ever
     * turn input that would have failed into input that matches — it cannot change the meaning of a
     * mnemonic that was already correct. `Locale.ROOT` because a Turkish locale lowercases `I` to a
     * dotless `ı`, which is not in the wordlist and would fail exactly the users a locale-sensitive
     * conversion was meant to help.
     *
     * **NFKD first, and that is BIP-39 itself rather than a workaround** — the spec defines the
     * mnemonic sentence as NFKD-normalised. Without it the kit this app exports cannot be used the
     * way the app tells the user to use it: the Recovery Kit PDF's text layer carries typographic
     * ligatures, so copying the words out of it yields U+FB01..U+FB04 (`ﬁ ﬂ ﬀ ﬃ ﬄ`) where the page
     * visibly reads `fi`/`fl`/`ff`. Roughly one BIP-39 word in twenty-five contains such a pair
     * (`flag`, `office`, `profit`, `confirm`, `benefit`…), so a 24-word kit is better than even
     * money to contain one. The bundled wordlist holds only ASCII, so the paste missed, the user was
     * told to "check the order and the spelling" about a word that looks correct on screen, and each
     * retry cost them unlock backoff. Found by the pre-iOS gap sweep 2026-09-11 and proved against
     * the shipped `kotlin-bip39` jar, which performs no normalisation of its own.
     *
     * Normalising can only widen what is accepted: NFKD leaves an already-ASCII mnemonic byte-identical.
     */
    private fun canonicalMnemonic(mnemonic: String): String =
        Normalizer.normalize(mnemonic, Normalizer.Form.NFKD)
            .trim()
            .lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .joinToString(" ")

    private fun deriveRecoveryKekFromEntropy(entropy: ByteArray): ByteArray =
        Hkdf.derive(
            ikm = entropy,
            info = RECOVERY_LABEL,
            length = CryptoConstants.KEY_LEN,
            salt = ByteArray(0),
        )
}
