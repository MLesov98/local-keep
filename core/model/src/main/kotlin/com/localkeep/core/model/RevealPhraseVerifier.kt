package com.localkeep.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * The stored proof that a typed reveal phrase is correct, without the phrase itself ever being
 * written to disk. It carries the Argon2id parameters, the salt, and the derived hash; verifying a
 * candidate re-derives with the same parameters and compares the hash in constant time (see
 * `:core:vault` `RevealPhraseGate`). Field 6 of [Vault], added in schema 5.
 *
 * This type is inert data — it holds no key and performs no derivation — so it lives in `:core:model`
 * with the rest of the schema, keeping the KDF and the module's Bouncy Castle dependency out of the
 * pure-JVM model layer. It is a hand-written class rather than a `data class` because its two byte
 * fields need content equality; see [equals]/[hashCode].
 */
@Serializable
class RevealPhraseVerifier(
    @ProtoNumber(1) val salt: ByteArray,
    @ProtoNumber(2) val memoryKib: Int,
    @ProtoNumber(3) val iterations: Int,
    @ProtoNumber(4) val parallelism: Int,
    @ProtoNumber(5) val hash: ByteArray,
) {
    /**
     * Whether this verifier is safe to feed to the KDF. Checked *before* any Argon2 allocation so a
     * malformed or hostile imported verifier is rejected as data rather than used as parameters: a
     * lie about [memoryKib] is an out-of-memory request, a wrong-size [salt] a cross-vault rainbow
     * target, a wrong-size [hash] a comparison that can never match.
     *
     * The parameters are bounded on both sides. The lower bounds mirror `KdfParams.FLOOR` (19,456
     * KiB, 2, 1) so a downgraded verifier is refused; the upper bounds keep an attacker-supplied
     * verifier from turning one reveal attempt into an OOM or a multi-minute stall.
     */
    val isValid: Boolean
        get() = salt.size == SALT_SIZE &&
            hash.size == HASH_SIZE &&
            memoryKib in MIN_MEMORY_KIB..MAX_MEMORY_KIB &&
            iterations in MIN_ITERATIONS..MAX_ITERATIONS &&
            parallelism in MIN_PARALLELISM..MAX_PARALLELISM

    // Content equality over the byte arrays. A data class would compare them by identity, so two
    // verifiers decoded from the same bytes would test unequal — which the round-trip and merge
    // tests, and any future de-dup of a vault against itself, rely on being false.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RevealPhraseVerifier) return false
        return memoryKib == other.memoryKib &&
            iterations == other.iterations &&
            parallelism == other.parallelism &&
            salt.contentEquals(other.salt) &&
            hash.contentEquals(other.hash)
    }

    override fun hashCode(): Int {
        var result = salt.contentHashCode()
        result = 31 * result + memoryKib
        result = 31 * result + iterations
        result = 31 * result + parallelism
        result = 31 * result + hash.contentHashCode()
        return result
    }

    companion object {
        const val SALT_SIZE = 16
        const val HASH_SIZE = 32
        const val MIN_MEMORY_KIB = 19_456
        const val MAX_MEMORY_KIB = 262_144
        const val MIN_ITERATIONS = 2
        const val MAX_ITERATIONS = 16
        const val MIN_PARALLELISM = 1
        const val MAX_PARALLELISM = 16
    }
}
