package com.localkeep.core.import

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The envelope Bitwarden writes around a *password-protected* export, and nothing else.
 *
 * Bitwarden has two encrypted export shapes and only one of them can be opened by anybody holding a
 * password:
 *
 *  - **Password-protected** — `passwordProtected: true`, with the KDF parameters written into the
 *    file beside the ciphertext. The user chose the password at export time and it is the whole
 *    credential. This is what this object opens.
 *  - **Account-encrypted** — `encrypted: true` and nothing else but `encKeyValidation_DO_NOT_EDIT`.
 *    The key is the account's own symmetric key, derived from a master password *and* the account's
 *    server-side KDF settings, neither of which travels in the file. No amount of typing can open it
 *    outside Bitwarden, so it is [ImportFailureReason.AccountEncrypted] — see
 *    [BitwardenJsonImporter].
 *
 * [passwordProtected] is therefore the only field that decides which case a file is, and it is read
 * rather than inferred from the presence of `salt`: `empty-account-encrypted.json` in the corpus
 * carries neither, and an inference would have to guess about a file that states the answer.
 */
@Serializable
internal data class BitwardenEncryptedEnvelope(
    val encrypted: Boolean = false,
    val passwordProtected: Boolean = false,
    /**
     * Used as a UTF-8 *string*, not decoded. It is base64 in the files because Bitwarden generates
     * it from 16 random bytes, but its own derivation passes the string through — the same slot the
     * account email occupies for an account key — so decoding it here would derive a different key
     * from the same file.
     */
    val salt: String = "",
    /** 0 = PBKDF2-SHA256, 1 = Argon2id. Bitwarden's `KdfType`. */
    val kdfType: Int = 0,
    /** PBKDF2 iteration count, or Argon2's time cost. */
    val kdfIterations: Int = 0,
    /** Argon2 memory, in **MiB**. Absent for PBKDF2. */
    val kdfMemory: Int? = null,
    /** Argon2 lanes. Absent for PBKDF2. */
    val kdfParallelism: Int? = null,
    @SerialName("encKeyValidation_DO_NOT_EDIT")
    val encKeyValidation: String = "",
    val data: String = "",
)

/**
 * Opens a password-protected Bitwarden export.
 *
 * Every step below is Bitwarden's own, and the two KeePassXC-sourced fixtures
 * (`account-encrypted-pbkdf2.json`, `account-encrypted-argon2id.json`, both password `a`) are what
 * pins them — a derivation that is wrong in any detail cannot produce a MAC that verifies, so
 * `RealWorldBitwardenImportTest` is a known-answer test for the whole chain rather than a smoke test.
 *
 * 1. **Derive** a 32-byte key from the password and the envelope's own `salt`, with the KDF the
 *    envelope names. Argon2id takes `SHA-256(salt)` where PBKDF2 takes the salt bytes directly;
 *    that asymmetry is Bitwarden's, not a guess (its `deriveKeyFromPassword` hashes the salt for
 *    Argon2 because Argon2 requires at least 8 salt bytes and an email address is not guaranteed
 *    to be one).
 * 2. **Stretch** it into a 64-byte key with two HKDF-Expand calls, `"enc"` and `"mac"`. HKDF-Expand
 *    only — there is no extract step, because the KDF output is already a uniformly random key.
 * 3. **Validate** against `encKeyValidation_DO_NOT_EDIT`, which is a random GUID encrypted under
 *    the same key. Its MAC failing is what a wrong password looks like, and is the *only* thing
 *    that may be reported as one: a MAC failure on `data` alone would mean the file is damaged.
 * 4. **Decrypt** `data`, MAC-checked first, then AES-256-CBC.
 *
 * The password is not wiped here. It belongs to the caller ([BitwardenJsonImporter.parse]), which
 * zeroes it in a `finally` covering this whole call — the same division KDBX uses. Everything this
 * object derives from it is wiped here, in `finally`, including on the failure paths: a wrong
 * password still leaves a derived key on the heap unless the wipe is unconditional.
 */
internal object BitwardenEncryptedExport {

    /** Bitwarden's `EncryptionType.AesCbc256_HmacSha256_B64`. The only one an export is written in. */
    private const val ENC_TYPE_AES_CBC_256_HMAC_SHA256 = "2"

    private const val KDF_PBKDF2_SHA256 = 0
    private const val KDF_ARGON2ID = 1

    /** The upper ends of Bitwarden's own KDF ranges (its `KdfConfig`); nothing it writes exceeds them. */
    private const val MAX_PBKDF2_ITERATIONS = 2_000_000
    private const val MAX_ARGON2_ITERATIONS = 10
    private const val MAX_ARGON2_MEMORY_MIB = 1_024
    private const val MAX_ARGON2_PARALLELISM = 16

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * The envelope, or null when [text] is not an encrypted Bitwarden export at all.
     *
     * Deliberately silent about malformed input: this is the question "does this file need a
     * password", asked before the user has been given anywhere to type one, and the answer for
     * anything unreadable is "no" — the ordinary parse path then reports what is actually wrong
     * with it.
     */
    fun envelopeOf(text: String): BitwardenEncryptedEnvelope? = runCatching {
        json.decodeFromString(BitwardenEncryptedEnvelope.serializer(), text).takeIf { it.encrypted }
    }.getOrNull()

    /**
     * The plaintext export JSON, as bytes the caller wipes once it has been parsed.
     *
     * Throws [ImportRejection] and nothing else: [ImportFailureReason.WrongExportPassword] when the
     * validation MAC does not verify, [ImportFailureReason.ExportNeedsMoreMemory] when the
     * envelope's own Argon2 parameters ask for more heap than this device allows an app, and
     * [ImportFailureReason.Unexpected] for a KDF or an EncString type this build does not know.
     */
    fun decrypt(envelope: BitwardenEncryptedEnvelope, password: CharArray): ByteArray {
        val passwordBytes = charArrayToUtf8Bytes(password)
        var key: ByteArray? = null
        var encKey: ByteArray? = null
        var macKey: ByteArray? = null
        try {
            key = deriveKey(envelope, passwordBytes)
            encKey = hkdfExpand(key, "enc")
            macKey = hkdfExpand(key, "mac")
            // Step 3. The validation blob exists precisely so a wrong password is named as one
            // rather than surfacing as a padding error thirty kilobytes later.
            val validation = parseEncString(envelope.encKeyValidation)
            if (!macVerifies(validation, macKey)) {
                throw ImportRejection(ImportFailureReason.WrongExportPassword(SourceFormat.BITWARDEN))
            }
            val payload = parseEncString(envelope.data)
            if (!macVerifies(payload, macKey)) {
                // The password already proved correct one step above, so this is not a credential
                // failure and must not be reported as one — the file itself is damaged.
                throw ImportRejection(ImportFailureReason.MalformedExport(SourceFormat.BITWARDEN))
            }
            return decryptAesCbc(payload, encKey)
        } finally {
            passwordBytes.fill(0)
            key?.fill(0)
            encKey?.fill(0)
            macKey?.fill(0)
        }
    }

    private fun deriveKey(envelope: BitwardenEncryptedEnvelope, passwordBytes: ByteArray): ByteArray {
        val saltBytes = envelope.salt.toByteArray(Charsets.UTF_8)
        // 2026-10-02: the cost is the file's, and it was used unbounded — `kdfIterations` near
        // Int.MAX_VALUE pinned a core for hours in a derivation Back cannot interrupt. Bitwarden's own
        // ranges cap what it will ever write, so anything past them is not one of its exports.
        val withinBitwardenRange = when (envelope.kdfType) {
            KDF_PBKDF2_SHA256 -> envelope.kdfIterations in 1..MAX_PBKDF2_ITERATIONS
            KDF_ARGON2ID ->
                envelope.kdfIterations in 1..MAX_ARGON2_ITERATIONS &&
                    (envelope.kdfMemory ?: 0) in 1..MAX_ARGON2_MEMORY_MIB &&
                    (envelope.kdfParallelism ?: 1) in 1..MAX_ARGON2_PARALLELISM
            else -> true
        }
        if (!withinBitwardenRange) {
            throw ImportRejection(ImportFailureReason.MalformedExport(SourceFormat.BITWARDEN))
        }
        return when (envelope.kdfType) {
            KDF_PBKDF2_SHA256 -> {
                // BouncyCastle rather than `SecretKeyFactory("PBKDF2WithHmacSHA256")`, and the
                // reason is the password encoding: the JCE spec takes a `char[]` and leaves the
                // char-to-byte conversion to the provider, which is UTF-8 on the JVM and has
                // historically been "low byte of each char" on Android. A non-ASCII export
                // password would then derive a different key on the device than in these tests —
                // exactly the class of defect `KdbxMasterPasswordEncodingTest` exists for. Feeding
                // bytes we encoded ourselves removes the provider from that decision entirely.
                val generator = PKCS5S2ParametersGenerator(SHA256Digest())
                generator.init(passwordBytes, saltBytes, envelope.kdfIterations)
                (generator.generateDerivedParameters(256) as KeyParameter).key
            }

            KDF_ARGON2ID -> {
                val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withIterations(envelope.kdfIterations)
                    // MiB in the file, KiB in the primitive.
                    .withMemoryAsKB((envelope.kdfMemory ?: 0) * 1024)
                    .withParallelism(envelope.kdfParallelism ?: 1)
                    .withSalt(MessageDigest.getInstance("SHA-256").digest(saltBytes))
                    .build()
                val out = ByteArray(32)
                try {
                    Argon2BytesGenerator().apply { init(parameters) }
                        .generateBytes(passwordBytes, out, 0, out.size)
                } catch (t: OutOfMemoryError) {
                    // The same case `KdbxKdfTooLarge` names for a KeePass database: the export's
                    // author chose the memory cost and nothing here can lower it. Reported as the
                    // allocation it is rather than as a damaged file or a wrong password, both of
                    // which would send the user to fix something that is not broken.
                    throw ImportRejection(
                        ImportFailureReason.ExportNeedsMoreMemory(SourceFormat.BITWARDEN),
                        t,
                    )
                }
                out
            }

            else -> throw ImportRejection(ImportFailureReason.Unexpected)
        }
    }

    /**
     * HKDF-Expand (RFC 5869 §2.3) with SHA-256, for a 32-byte output — so exactly one round, and
     * `T(1) = HMAC(prk, info || 0x01)`.
     */
    private fun hkdfExpand(prk: ByteArray, info: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info.toByteArray(Charsets.UTF_8))
        mac.update(1)
        return mac.doFinal()
    }

    /** One Bitwarden `EncString`: `2.<iv>|<ct>|<mac>`, each part base64. */
    private class EncString(val iv: ByteArray, val cipherText: ByteArray, val mac: ByteArray)

    private fun parseEncString(value: String): EncString {
        val dot = value.indexOf('.')
        if (dot <= 0 || value.substring(0, dot) != ENC_TYPE_AES_CBC_256_HMAC_SHA256) {
            throw ImportRejection(ImportFailureReason.Unexpected)
        }
        val parts = value.substring(dot + 1).split('|')
        if (parts.size != 3) throw ImportRejection(ImportFailureReason.Unexpected)
        return try {
            EncString(
                iv = Base64.getDecoder().decode(parts[0]),
                cipherText = Base64.getDecoder().decode(parts[1]),
                mac = Base64.getDecoder().decode(parts[2]),
            )
        } catch (t: IllegalArgumentException) {
            throw ImportRejection(ImportFailureReason.MalformedExport(SourceFormat.BITWARDEN), t)
        }
    }

    /**
     * `MessageDigest.isEqual` is the constant-time comparison on both the JVM and Android
     * (documented as such since Java 6 / API 1) — never `contentEquals`, which returns on the first
     * differing byte and leaks how much of a forged MAC was right.
     */
    private fun macVerifies(value: EncString, macKey: ByteArray): Boolean {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(macKey, "HmacSHA256"))
        mac.update(value.iv)
        mac.update(value.cipherText)
        return MessageDigest.isEqual(mac.doFinal(), value.mac)
    }

    private fun decryptAesCbc(value: EncString, encKey: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(value.iv))
        return cipher.doFinal(value.cipherText)
    }

    /**
     * UTF-8 bytes from a `CharArray` without an intermediate `String`: a String is immutable and
     * cannot be zeroized, so building one would leave an un-scrubbable copy of the export password
     * on the heap and defeat the point of taking a CharArray at all. Same shape and same reason as
     * `KdbxImporter.charArrayToUtf8Bytes`.
     */
    private fun charArrayToUtf8Bytes(chars: CharArray): ByteArray {
        val bb = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars))
        val out = ByteArray(bb.remaining())
        bb.get(out)
        if (bb.hasArray() && !bb.isReadOnly) bb.array().fill(0)
        return out
    }
}
