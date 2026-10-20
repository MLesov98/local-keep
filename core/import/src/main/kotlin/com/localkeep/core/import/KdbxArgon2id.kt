package com.localkeep.core.import

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.linguafranca.pwdb.kdbx.KdbxHeader
import org.linguafranca.pwdb.security.Encryption
import org.linguafranca.pwdb.security.KeyDerivationFunction
import org.linguafranca.pwdb.security.VariantDictionary
import java.util.UUID

/**
 * Argon2id, the key derivation KeePassXC has written into every new database since 2.6.6.
 *
 * The pinned KeePassJava2 2.2.4 knows exactly two KDFs, and neither is this one:
 * `Encryption$KeyDerivationFunction` is an *enum* with `AES` and `ARGON2` (Argon2**d**,
 * `EF636DDF-8C29-444B-91F7-A9A403E30A0C`), and its `getKdf(UUID)` walks `values()` and throws
 * `IllegalArgumentException("Unknown Cipher UUID")` for anything else. There is no registry, no
 * service loader and no `Kdf` factory to register into — measured by disassembling the shipped
 * jars on 2026-09-13, not read off the project's docs. So a modern KeePassXC export reached
 * [KdbxImporter.describeFailure] as an unknown algorithm and the user was told this build cannot
 * open their file.
 *
 * What made a fix possible without forking the library is that `KdbxHeader` is neither final nor
 * sealed and reads its KDF through two public, overridable methods — [KdbxHeader.setKdfParameters]
 * (which eagerly resolves the enum and is where the throw happens) and
 * [KdbxHeader.getTransformedKeyDigest] (which re-resolves it from the `$UUID` entry of the
 * parameter dictionary rather than from the field the setter populated). Overriding those two is
 * enough to take the whole Argon2id case away from the library while every other KDF, cipher and
 * KDBX version keeps running through its own code untouched — see [Argon2idKdbxHeader].
 *
 * The primitive itself is BouncyCastle's, which the library already depends on for Argon2d: the
 * two variants differ only in the type byte handed to `Argon2Parameters.Builder`, and every
 * parameter is read from the same VariantDictionary keys (`V`, `S`, `P`, `M`, `I`) the library's
 * own `Argon2` reads. That is not an assumption about KeePass's format — the KDF parameters block
 * is KDF-agnostic and the variant lives only in the UUID.
 */
object Argon2idKdf : KeyDerivationFunction {

    /** KeePass's KDF id for Argon2id. Argon2d's is `EF636DDF-8C29-444B-91F7-A9A403E30A0C`. */
    @JvmField
    val UUID: UUID = java.util.UUID.fromString("9E298B19-56DB-4773-B23D-FC3EC6F0A1E6")

    override fun getKdfUuid(): UUID = UUID

    override fun getName(): String = "Argon2id"

    /**
     * The same five reads and the same 32-byte output as the library's Argon2d, with
     * [Argon2Parameters.ARGON2_id] in place of `ARGON2_d`.
     *
     * `K` (a secret key) and `A` (associated data) are deliberately not read, for symmetry with the
     * library's Argon2d, which ignores them too: neither KeePass nor KeePassXC can write them from
     * their UI, and honouring them here but not there would make Argon2id and Argon2d behave
     * differently on the same file shape.
     *
     * `M` is a byte count and is divided *before* narrowing (the library narrows first), then
     * clamped, so a file claiming terabytes of memory fails as the allocation it is — which
     * `describeFailure` already reports as "needs more memory than this device allows" — rather
     * than wrapping to a negative KiB count and being reported as a damaged file.
     */
    override fun getTransformedKey(key: ByteArray, params: VariantDictionary): ByteArray {
        val memoryKb = (params.mustGet("M").asLong() / 1024).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(params.mustGet("V").asInteger())
            .withIterations(params.mustGet("I").asLong().coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
            .withMemoryAsKB(memoryKb)
            .withParallelism(params.mustGet("P").asInteger())
            .withSalt(params.mustGet("S").asByteArray())
            .build()
        val generator = Argon2BytesGenerator().apply { init(parameters) }
        val out = ByteArray(32)
        generator.generateBytes(key, out, 0, out.size)
        return out
    }

    /**
     * The library's own Argon2d defaults with this variant's id — used only to *author* an Argon2id
     * container, which nothing in the shipping app does. It exists so the fixtures that prove the
     * read path are written by the same parameter vocabulary the read path parses, instead of being
     * hand-assembled bytes that could agree with a mistake on both sides.
     */
    override fun createKdfParameters(): VariantDictionary =
        Encryption.KeyDerivationFunction.ARGON2.createKdfParameters().apply { putUuid("\$UUID", UUID) }
}

/**
 * A [KdbxHeader] that can also open (and write) Argon2id databases.
 *
 * Three overrides, and each is load-bearing for a different reason:
 *
 * - [setKdfParameters] is where the library throws. `KdbxSerializer.getOuterHeaderFields` calls it
 *   while reading the header, and the stock implementation resolves the enum immediately. For
 *   Argon2id the dictionary is kept here instead and `super` is never called, so no resolution is
 *   attempted; for every other KDF the call is passed straight through and behaves exactly as
 *   before.
 * - [getKdfParameters] has to answer with the dictionary [setKdfParameters] withheld, because the
 *   library's private field was never populated for the Argon2id case. It is also what
 *   `KdbxSerializer.writeKdbxHeader` writes out, which is what lets this class author a fixture.
 * - [getTransformedKeyDigest] is the actual derivation. It decides from the *current* dictionary's
 *   `$UUID` rather than from a flag set during parsing, so the same instance behaves correctly
 *   whether the parameters arrived from a file or were set by a caller before a save.
 *
 * Constructed with the no-argument `super()` on the read path on purpose: `KdbxHeader(int)` pins
 * the allowed file version, and KDBX 3.1 imports must keep working.
 *
 * `open` for [TwofishKdbxHeader], which closes the same library gap for a *cipher* and layers on
 * top of this one because a database may use either, both or neither.
 */
open class Argon2idKdbxHeader : KdbxHeader {

    constructor() : super()

    /** Version-pinned, for writing a container rather than reading one. */
    constructor(version: Int) : super(version)

    private var argon2idParameters: VariantDictionary? = null

    override fun setKdfParameters(parameters: VariantDictionary) {
        if (isArgon2id(parameters)) argon2idParameters = parameters else super.setKdfParameters(parameters)
    }

    override fun getKdfParameters(): VariantDictionary? = argon2idParameters ?: super.getKdfParameters()

    override fun getKeyDerivationFunction(): KeyDerivationFunction? =
        if (argon2idParameters != null) Argon2idKdf else super.getKeyDerivationFunction()

    override fun getTransformedKeyDigest(key: ByteArray): ByteArray {
        val parameters = kdfParameters
        // A null dictionary is KDBX 3.1, whose transform is AES-KDF over the header's own seed and
        // round count — the library's business, not this class's.
        if (parameters == null || !isArgon2id(parameters)) return super.getTransformedKeyDigest(key)
        return Argon2idKdf.getTransformedKey(key, parameters)
    }

    private fun isArgon2id(parameters: VariantDictionary): Boolean =
        parameters.get("\$UUID")?.asUuid() == Argon2idKdf.UUID
}
