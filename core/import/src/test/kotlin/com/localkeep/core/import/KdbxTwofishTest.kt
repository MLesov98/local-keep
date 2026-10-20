package com.localkeep.core.import

import com.localkeep.core.import.RealWorldCorpus.failureReason
import com.localkeep.core.import.RealWorldCorpus.orFail
import org.bouncycastle.crypto.engines.TwofishEngine
import org.bouncycastle.crypto.params.KeyParameter
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Twofish-256-CBC, the third KDBX cipher, which the pinned KeePassJava2 cannot build a stream for.
 *
 * Two kinds of evidence, and the first is the one that makes the second mean anything:
 *
 * - **Known-answer vectors.** A round trip proves only that this file agrees with itself; it would
 *   pass just as happily if [TwofishCipher] were wired to the wrong engine, the wrong mode or the
 *   wrong key size, as long as both directions were wrong together. The vectors below are the
 *   published Twofish ones from the cipher's own submission package (`ecb_ival.txt`, I=1 for each
 *   key length: the all-zero key over the all-zero block), so what they pin is agreement with
 *   Twofish as specified rather than with this code.
 * - **Real containers.** The two Twofish databases in the pykeepass corpus, which KeePassXC and
 *   pykeepass wrote, not this project — the end-to-end check that the KDBX wiring around the
 *   cipher is right too.
 */
class KdbxTwofishTest {

    /**
     * The published Twofish vectors, one per key length. The 256-bit line is the one on this app's
     * path — KDBX always hands the cipher a 256-bit key — and the other two are here because a
     * substitution that kept 256-bit Twofish correct while breaking the key schedule for shorter
     * keys would not be Twofish, and the three together are what the published file pins.
     */
    @Test
    fun `the engine behind TwofishCipher answers the published Twofish vectors`() {
        mapOf(
            16 to "9F589F5CF6122C32B6BFEC2F2AE8C35A",
            24 to "EFA71F788965BD4453F860178FC19101",
            32 to "57FF739D4DC92C1BD7FC01700CC8216F",
        ).forEach { (keyBytes, expected) ->
            val engine = TwofishEngine().apply { init(true, KeyParameter(ByteArray(keyBytes))) }
            val out = ByteArray(16)
            engine.processBlock(ByteArray(16), 0, out, 0)
            assertEquals(expected, out.hex(), "Twofish-${keyBytes * 8} of the all-zero block")
        }
    }

    /**
     * The mode, the padding and the IV, anchored to the vector above rather than to a round trip.
     *
     * CBC's first ciphertext block is `E(P1 xor IV)`, so with an all-zero key, IV and plaintext it
     * must be *exactly* the published 256-bit vector — which is what makes this a known-answer test
     * of [TwofishCipher]'s own wiring and not of its self-consistency. The second block is the
     * PKCS#7 padding block; it is here so that a change of padding scheme, which would leave the
     * first block untouched, still fails.
     */
    @Test
    fun `TwofishCipher streams CBC with PKCS7 padding and a 16-byte IV`() {
        val key = ByteArray(32)
        val iv = ByteArray(16)
        val plaintext = ByteArray(16)
        val ciphertext = ByteArrayOutputStream().use { sink ->
            TwofishCipher.getEncryptedOutputStream(sink, key, iv).use { it.write(plaintext) }
            sink.toByteArray()
        }
        assertEquals(
            "57FF739D4DC92C1BD7FC01700CC8216F" + "B07FB634F1E56D6D4C97AA3A791B3498",
            ciphertext.hex(),
            "first block is E(0) — the published vector — and the second is the PKCS#7 pad block",
        )
        assertEquals(
            plaintext.hex(),
            TwofishCipher.getDecryptedInputStream(ciphertext.inputStream(), key, iv).use { it.readBytes() }.hex(),
        )
    }

    /**
     * The uncompressed Twofish container, which needs no key file — the same file, password and
     * assertion as its AES and ChaCha20 siblings in [RealWorldKeePassImportTest], so the cipher is
     * demonstrably the only variable.
     */
    @Test
    fun `a real Twofish container opens with its password alone`() {
        val path = "keepass/pykeepass/test4_twofish_uncompressed.kdbx"
        val parsed = KdbxImporter.parse(RealWorldCorpus.bytes(path), "password".toCharArray()).orFail(path)
        assertEquals(1, parsed.items.size)
        assertEquals("foo", parsed.items.single().title)
    }

    /** The compressed one, whose credential is a password *and* `test4.key`. */
    @Test
    fun `a real Twofish container opens with its password and key file`() {
        val path = "keepass/pykeepass/test4_twofish.kdbx"
        val parsed = KdbxImporter.parse(
            RealWorldCorpus.bytes(path),
            "password".toCharArray(),
            keyFile = RealWorldCorpus.bytes("keepass/pykeepass/test4.key"),
        ).orFail(path)
        assertEquals(
            RealWorldKdbxOracle.contents(
                RealWorldCorpus.bytes(path),
                "password",
                RealWorldCorpus.bytes("keepass/pykeepass/test4.key"),
            ).entries,
            parsed.items.size + parsed.skipped.size,
        )
    }

    /**
     * Control, and the reason this cipher is worth implementing rather than naming in a refusal: a
     * *wrong* password on a Twofish database must now fail the way a wrong password fails on any
     * other database. While the cipher was unsupported every Twofish file answered
     * [ImportFailureReason.KdbxSupportBroken] whatever was typed, so this assertion could not
     * distinguish a working cipher from a broken one until it did.
     */
    @Test
    fun `a wrong password on a Twofish container is reported as a wrong password`() {
        val path = "keepass/pykeepass/test4_twofish_uncompressed.kdbx"
        assertEquals(
            ImportFailureReason.WrongPassword(keyFileMayBeNeeded = true),
            KdbxImporter.parse(RealWorldCorpus.bytes(path), "not the password".toCharArray()).failureReason(path),
        )
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02X".format(it) }
}
