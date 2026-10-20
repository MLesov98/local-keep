package com.localkeep.core.import

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The four shapes a KeePass key file comes in, each read from a real file in the corpus where one
 * exists. [KdbxKeyFile] is the half of the composite key that is not the password, so a format it
 * reads wrongly is indistinguishable from a mistyped password — which is exactly how the corpus
 * found the gap in the first place.
 */
class KdbxKeyFileTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `an XML v1 key file is its base64 Data element`() {
        // `test3.key`'s own <Data>, decoded independently of the parser under test.
        val expected = java.util.Base64.getDecoder().decode("Qp9MrFM1RpSLO8iHZHGAiPbr8Z+hDFpp0cgtH+RM0hw=")
        assertContentEquals(expected, KdbxKeyFile.keyBytes(RealWorldCorpus.bytes("keepass/pykeepass/test3.key")))
    }

    @Test
    fun `an XML v2 keyx is its whitespace-separated hex, and its Hash attribute is checked`() {
        assertEquals(
            "30d73184fbe1c7c4b07ee4d6bc4f118b87577cab5cb8846f5fd286fff98bf9a9",
            hex(KdbxKeyFile.keyBytes(RealWorldCorpus.bytes("keepass/pykeepass/test4_keyx.keyx"))),
        )
        // The same file with one hex digit changed: the Hash attribute no longer describes the data,
        // and a key file the app cannot trust must be named as one rather than derived from.
        val corrupted = RealWorldCorpus.text("keepass/pykeepass/test4_keyx.keyx")
            .replace("30D73184", "30D73185")
        assertFailsWith<KdbxKeyFile.Unreadable> { KdbxKeyFile.keyBytes(corrupted.toByteArray()) }
    }

    @Test
    fun `a legacy 64-hex key file is decoded, not hashed`() {
        val bytes = RealWorldCorpus.bytes("keepass/pykeepass/test4_hex.key")
        assertEquals(64, bytes.size, "the fixture is the legacy hex form")
        assertEquals("ff".repeat(32), hex(KdbxKeyFile.keyBytes(bytes)))
    }

    @Test
    fun `a 32-byte binary key file is the key itself`() {
        val raw = ByteArray(32) { it.toByte() }
        assertContentEquals(raw, KdbxKeyFile.keyBytes(raw))
    }

    @Test
    fun `any other file is hashed, which is how KeePass lets an arbitrary file be a key file`() {
        val arbitrary = "a photo, a text file, anything at all".toByteArray()
        assertContentEquals(MessageDigest.getInstance("SHA-256").digest(arbitrary), KdbxKeyFile.keyBytes(arbitrary))
    }

    /**
     * Control: a 33-byte file is *not* the 32-byte case, and a 64-byte file that is not hex is not
     * the legacy case. Both fall to the hash, which is the only thing that keeps an arbitrary file
     * usable — the length rules are exact, not approximate.
     */
    @Test
    fun `the length rules are exact`() {
        val sha = MessageDigest.getInstance("SHA-256")
        val thirtyThree = ByteArray(33) { it.toByte() }
        assertContentEquals(sha.digest(thirtyThree), KdbxKeyFile.keyBytes(thirtyThree))
        val sixtyFourNotHex = ByteArray(64) { 'z'.code.toByte() }
        assertContentEquals(sha.digest(sixtyFourNotHex), KdbxKeyFile.keyBytes(sixtyFourNotHex))
    }
}
