package com.localkeep.core.vault

import com.code_intelligence.jazzer.junit.FuzzTest
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.WrappedDek
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.io.File

/**
 * The one fuzz target that does not live in `:test:fuzz`.
 *
 * [VaultHeaderCodec.decode] is the purest parser in this app — fixed-size, **key-free**, and
 * dispatching on a version field — which is why the fuzzing plan ranked it first. The plan then
 * assumed a pure-JVM `:test:fuzz` module could depend on `:core:vault`. It cannot: `:core:vault` is
 * an Android library and a JVM module cannot consume its variants. So the target lives beside the
 * code it tests, and this module's unit tests run on the JUnit platform to make that possible.
 *
 * **Why it is worth fuzzing even though it needs no key.** `decode` is reached by restoring a backup
 * — a file a user can be talked into opening — and it is the first thing that touches those bytes,
 * before any password is involved. Everything downstream trusts that a `VaultHeader` came out of
 * here intact.
 *
 * ### The oracle
 *
 * Narrower than the importers', because `decode` does not have their swallow-everything contract.
 * It is documented to reject, and only two rejections are legitimate:
 *
 *  - `IllegalArgumentException` — a `require` failing on a malformed or truncated header.
 *  - [UnsupportedVaultFormatException] — a well-formed header from a newer LocalKeep. A subclass of
 *    the above, and deliberately distinct: "written by a newer build" and "damaged" are opposite
 *    situations, and the advice for the second destroys the vault in the first.
 *
 * Anything else is a finding. `IndexOutOfBoundsException`, `NegativeArraySizeException` and
 * `OutOfMemoryError` are named in the assertion because they are the three that a fixed-size binary
 * parser with `copyOfRange` calls would plausibly produce, and because naming them makes the report
 * legible instead of a bare stack trace.
 */
class VaultHeaderCodecFuzzTest {

    @FuzzTest(maxDuration = "10m")
    fun decode(bytes: ByteArray) {
        try {
            VaultHeaderCodec.decode(bytes)
        } catch (expected: IllegalArgumentException) {
            // Covers UnsupportedVaultFormatException too, which extends it.
        } catch (t: Throwable) {
            throw AssertionError(
                "VaultHeaderCodec.decode threw ${t::class.java.name} on ${bytes.size} bytes.\n\n" +
                    "Only IllegalArgumentException (a failed require) and " +
                    "UnsupportedVaultFormatException may escape decode. Anything else means a " +
                    "bounds check is missing or a length is being trusted: this parser is the " +
                    "first thing to touch the bytes of a restored backup, before any password is " +
                    "involved, and everything downstream assumes what it returns is intact.",
                t,
            )
        }
    }

    /**
     * Writes the seed: one real, well-formed encoded header.
     *
     * Without it this target is theatre. `decode` opens with
     * `require(bytes.size >= ENCODED_SIZE)` and then `VersionedHeader.decode`, so blind mutation
     * spends its whole budget on the truncation path and reports nothing — which is precisely what
     * happened to `BreachFilter.load` before it had a seed: sixty seconds, zero coverage of the
     * parser, BUILD SUCCESSFUL.
     *
     * Guarded, because it writes into the source tree. Regenerate when the header format changes:
     *
     * ```bash
     * ./gradlew :core:vault:testDebugUnitTest --tests '*VaultHeaderCodecFuzzTest*' -PwriteSeeds=true
     * ```
     */
    @Test
    @EnabledIfSystemProperty(named = "writeSeeds", matches = "true")
    fun writeSeedCorpus() {
        val kdfParams = KdfParams(memoryKib = 65_536, iterations = 3, parallelism = 4)
        val header = VaultHeaderCodec.create(
            salt = ByteArray(CryptoConstants.SALT_LEN) { it.toByte() },
            kdfParams = kdfParams,
            wrappedDek = WrappedDek(
                nonce = ByteArray(CryptoConstants.AEAD_NONCE_LEN) { it.toByte() },
                ciphertext = ByteArray(CryptoConstants.KEY_LEN + CryptoConstants.AEAD_TAG_LEN) { it.toByte() },
            ),
            commitment = ByteArray(UnlockSlot.COMMITMENT_SIZE) { it.toByte() },
        )
        // Jazzer's layout: src/test/resources/<package>/<ClassName>Inputs/<methodName>/.
        val dir = File("src/test/resources/com/localkeep/core/vault/VaultHeaderCodecFuzzTestInputs/decode")
        dir.mkdirs()
        val encoded = VaultHeaderCodec.encode(header)
        File(dir, "valid.header").writeBytes(encoded)
        println("seed: ${dir.path}/valid.header (${encoded.size} bytes)")
    }
}
