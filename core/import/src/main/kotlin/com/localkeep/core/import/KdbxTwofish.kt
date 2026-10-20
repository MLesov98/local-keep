package com.localkeep.core.import

import org.bouncycastle.crypto.io.CipherInputStream
import org.bouncycastle.crypto.io.CipherOutputStream
import org.bouncycastle.crypto.engines.TwofishEngine
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.linguafranca.pwdb.security.CipherAlgorithm
import org.linguafranca.pwdb.security.Encryption
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Twofish-256 in CBC mode, the third cipher KeePass and KeePassXC offer in Database Settings.
 *
 * The pinned KeePassJava2 2.2.4 knows two: `Encryption$Cipher` is an *enum* with `AES` and
 * `CHA_CHA_20`, and its static `getCipherAlgorithm(UUID)` walks `values()` and throws
 * `IllegalArgumentException("Unknown Cipher UUID")` for anything else, while
 * `KdbxHeader.setCipherUuid(UUID)` compares against those same two instances and throws an
 * `IllegalStateException` with the same prefix. There is no registry and no service loader to
 * register into — the same shape of gap [Argon2idKdf] documents for the Argon2id **KDF**, measured
 * the same way, by disassembling the shipped jars. So a Twofish database reached
 * [KdbxImporter.describeFailure] as an unknown algorithm and its owner was told this build is
 * broken and asked to file a bug about a file that is perfectly well formed.
 *
 * The parameters are not a guess. KDBX names its cipher by UUID in the outer header and hands the
 * cipher a 256-bit key and the header's own `EncryptionIV`; for this one the IV is 16 bytes and the
 * mode is CBC with PKCS#7 padding, which is what KeePass's own Twofish cipher and KeePassXC's
 * `SymmetricCipher::Twofish256_CBC` both write. Everything else about the container — the HMAC
 * block stream, the master-seed hash that produces the final key, the inner stream cipher — is
 * cipher-agnostic and stays the library's business: [TwofishKdbxHeader] only swaps out the one
 * stream this class builds, exactly as [Argon2idKdbxHeader] swaps out the one derivation.
 *
 * The primitive is BouncyCastle's `TwofishEngine`, already on this module's classpath for the
 * Argon2 work above, and the wiring around it is copied from the library's own AES
 * implementation so the two ciphers cannot drift apart in how they are streamed.
 */
object TwofishCipher : CipherAlgorithm {

    /** KeePass's cipher id for Twofish-256-CBC. AES's is `31C1F2E6-BF71-4350-BE58-05216AFC5AFF`. */
    @JvmField
    val UUID: UUID = java.util.UUID.fromString("AD68F29F-576F-4BB9-A36A-D47AF965346C")

    override fun getCipherUuid(): UUID = UUID

    override fun getName(): String = "Twofish"

    override fun getDecryptedInputStream(inputStream: InputStream, key: ByteArray, iv: ByteArray): InputStream =
        CipherInputStream(inputStream, cipher(forEncryption = false, key = key, iv = iv))

    override fun getEncryptedOutputStream(outputStream: OutputStream, key: ByteArray, iv: ByteArray): OutputStream =
        CipherOutputStream(outputStream, cipher(forEncryption = true, key = key, iv = iv))

    private fun cipher(forEncryption: Boolean, key: ByteArray, iv: ByteArray) =
        PaddedBufferedBlockCipher(CBCBlockCipher.newInstance(TwofishEngine())).apply {
            init(forEncryption, ParametersWithIV(KeyParameter(key), iv))
        }
}

/**
 * The header this build reads every KDBX through: Argon2id from [Argon2idKdbxHeader], plus Twofish.
 *
 * Layered rather than merged because the two gaps are independent — a database may use either,
 * both or neither, and each override is only about its own — and because the KDF workaround came
 * first and is proved by its own tests.
 *
 * Four overrides, and the split between the two setters is the part that is easy to get wrong:
 *
 * - [setCipherUuid] (the `ByteArray` overload) is what `KdbxSerializer.getOuterHeaderFields` calls
 *   while reading a file. The stock implementation resolves the UUID *twice* — once through the
 *   `UUID` overload, which throws for an unregistered cipher, and again through the static
 *   `Encryption$Cipher.getCipherAlgorithm`, which throws for the same reason. Overriding only the
 *   `UUID` overload would therefore still throw on the second resolution, so both are taken; for
 *   every other cipher the bytes are passed straight to `super`, which re-enters the `UUID`
 *   overload virtually and populates the library's own fields exactly as before.
 * - [getCipherUuid] answers with the id the setter withheld, which is what
 *   `KdbxSerializer.writeKdbxHeader` writes out — what lets this class author a fixture rather than
 *   only read one.
 * - [createDecryptedStream] and [createEncryptedStream] are the derivation of the final key
 *   (SHA-256 over the master seed and the transformed key digest, the library's own two lines) plus
 *   the one stream the library cannot build. They read the *current* cipher rather than a flag set
 *   during parsing, so the same instance behaves correctly whether the header arrived from a file
 *   or was configured by a caller before a save.
 *
 * Constructed with the no-argument `super()` on the read path, for the reason [Argon2idKdbxHeader]
 * gives: `KdbxHeader(int)` pins the allowed file version, and KDBX 3.1 imports must keep working.
 */
class TwofishKdbxHeader : Argon2idKdbxHeader {

    constructor() : super()

    /** Version-pinned, for writing a container rather than reading one. */
    constructor(version: Int) : super(version)

    private var twofish = false

    override fun setCipherUuid(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        if (java.util.UUID(buffer.long, buffer.getLong(8)) == TwofishCipher.UUID) twofish = true
        else super.setCipherUuid(bytes)
    }

    override fun setCipherUuid(uuid: UUID) {
        if (uuid == TwofishCipher.UUID) twofish = true else super.setCipherUuid(uuid)
    }

    override fun getCipherUuid(): UUID = if (twofish) TwofishCipher.UUID else super.getCipherUuid()

    override fun getCipherAlgorithm(): CipherAlgorithm? = if (twofish) TwofishCipher else super.getCipherAlgorithm()

    override fun createDecryptedStream(digest: ByteArray, inputStream: InputStream): InputStream {
        if (!twofish) return super.createDecryptedStream(digest, inputStream)
        return TwofishCipher.getDecryptedInputStream(inputStream, finalKey(digest), encryptionIv)
    }

    override fun createEncryptedStream(digest: ByteArray, outputStream: OutputStream): OutputStream {
        if (!twofish) return super.createEncryptedStream(digest, outputStream)
        return TwofishCipher.getEncryptedOutputStream(outputStream, finalKey(digest), encryptionIv)
    }

    /**
     * SHA-256(masterSeed ‖ transformedKeyDigest) — the library's own final-key step, repeated here
     * because its two lines live inside the stream builders this class replaces. The digest is
     * taken through the overridable [getTransformedKeyDigest], so a Twofish database whose KDF is
     * Argon2id still derives through [Argon2idKdf].
     */
    private fun finalKey(digest: ByteArray): ByteArray {
        val messageDigest = Encryption.getSha256MessageDigestInstance()
        messageDigest.update(masterSeed)
        return messageDigest.digest(getTransformedKeyDigest(digest))
    }
}
