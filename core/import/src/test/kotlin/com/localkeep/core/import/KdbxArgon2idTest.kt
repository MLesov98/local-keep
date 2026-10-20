package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import com.localkeep.core.model.Login
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.linguafranca.pwdb.kdbx.KdbxCreds
import org.linguafranca.pwdb.kdbx.dom.DomDatabaseWrapper
import org.linguafranca.pwdb.security.ChaCha
import org.linguafranca.pwdb.security.Encryption
import org.linguafranca.pwdb.security.VariantDictionary

/**
 * Argon2id, which every KeePassXC database created since 2.6.6 uses by default and which this app
 * could not open at all.
 *
 * The pinned KeePassJava2 2.2.4 knows Argon2d and AES-KDF and refuses everything else — so the
 * single most common modern `.kdbx` was answered with *"KDBX support is broken in this build of
 * LocalKeep — please report it"*. That message was true and useless: nothing the user could do
 * fixed it. [Argon2idKdbxHeader] takes the derivation away from the library for that one KDF.
 *
 * Every fixture here is written by the same library the app reads with, so the writer cannot drift
 * away from the reader — and the one thing a round trip *cannot* prove, that our Argon2id really is
 * Argon2id rather than a self-consistent private function, is pinned by a known-answer vector taken
 * from the Argon2 reference implementation in [`argon2 reference vector`][argon2ReferenceVector].
 */
class KdbxArgon2idTest {

    private val password = "correct horse battery staple"
    private fun creds() = KdbxCreds(password.toByteArray(Charsets.UTF_8))

    /** A small, realistic Argon2id parameter block: 16 MiB, 2 passes, 2 lanes. */
    private fun argon2idParameters(): VariantDictionary =
        Argon2idKdf.createKdfParameters().apply {
            putULong("M", 16L * 1024 * 1024)
            putULong("I", 2L)
            putUInt("P", 2)
        }

    private fun database(build: DomDatabaseWrapper.() -> Unit): DomDatabaseWrapper =
        DomDatabaseWrapper().apply(build)

    private fun DomDatabaseWrapper.entry(title: String, secret: String) = newEntry().apply {
        this.title = title
        username = "alice@example.com"
        setPassword(secret)
        url = "https://example.com/login"
    }

    /** Writes a real Argon2id KDBX4 — [Argon2idKdbxHeader] in the writing direction. */
    private fun writeArgon2id(db: DomDatabaseWrapper): ByteArray {
        val header = Argon2idKdbxHeader(4).apply { setKdfParameters(argon2idParameters()) }
        return ByteArrayOutputStream().use { out ->
            db.save(org.linguafranca.pwdb.kdbx.KdbxStreamFormat(header), creds(), out)
            out.toByteArray()
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
        for (i in 0..haystack.size - needle.size) {
            if (needle.indices.all { haystack[i + it] == needle[it] }) return i
        }
        return -1
    }

    // -------------------------------------------------------------------------------------------
    // The claim that cannot be round-tripped: this is genuinely Argon2id, not a private function
    // that happens to agree with itself.
    // -------------------------------------------------------------------------------------------

    /**
     * The output of the Argon2 **reference implementation** (P-H-C `argon2` CLI, the same code
     * RFC 9106 is written against), run on this machine on 2026-09-13:
     *
     * ```
     * printf 'correct horse battery staple' | argon2 localkeepsalt123 -id -t 2 -m 16 -p 2 -l 32 -v 13 -r
     * 7056eb1ee5918d87d51cb1a8eaf2d9144437737173a80742409fd9e49874d658
     * ```
     *
     * The Argon2**d** answer for the same inputs (`-d`) is
     * `a8cdac1c81de1d36932fbca4a9dcfa53f4407b22a379e58c3f0aa8f9d2fa27d1`, and it is asserted below
     * too: a test that only pinned one hex string would pass just as happily if the variant byte
     * were wrong in both directions.
     */
    private val argon2ReferenceVector = "7056eb1ee5918d87d51cb1a8eaf2d9144437737173a80742409fd9e49874d658"
    private val argon2dReferenceVector = "a8cdac1c81de1d36932fbca4a9dcfa53f4407b22a379e58c3f0aa8f9d2fa27d1"

    private fun referenceParameters(): VariantDictionary =
        VariantDictionary(1).apply {
            putUInt("V", 19)
            putUInt("P", 2)
            putULong("I", 2L)
            putULong("M", 64L * 1024 * 1024)
            put("S", VariantDictionary.EntryType.ARRAY, "localkeepsalt123".toByteArray(Charsets.UTF_8))
        }

    @Test
    fun `the derivation matches the Argon2 reference implementation`() {
        val derived = Argon2idKdf.getTransformedKey(
            password.toByteArray(Charsets.UTF_8),
            referenceParameters(),
        )
        assertEquals(argon2ReferenceVector, hex(derived))
    }

    @Test
    fun `and it is not Argon2d, which the library already had`() {
        // The control that makes the vector above mean something: the two variants take the same
        // parameters and differ only in the addressing mode, so this is the assertion that fails
        // if `Argon2Parameters.ARGON2_id` is ever swapped for `ARGON2_d`.
        val theirs = Encryption.KeyDerivationFunction.ARGON2.getTransformedKey(
            password.toByteArray(Charsets.UTF_8),
            referenceParameters(),
        )
        assertEquals(argon2dReferenceVector, hex(theirs))
        assertTrue(
            !theirs.contentEquals(
                Argon2idKdf.getTransformedKey(password.toByteArray(Charsets.UTF_8), referenceParameters()),
            ),
            "Argon2id must not produce Argon2d's answer",
        )
    }

    // -------------------------------------------------------------------------------------------
    // End to end.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `an argon2id database imports`() {
        val bytes = writeArgon2id(database { rootGroup.addEntry(entry("Argon2id login", "s3cr3t-p@ss")) })

        // Anchors, asserted rather than assumed, so a failure below is about the reader and not
        // about a fixture that quietly wrote something else.
        assertEquals(4, bytes[10].toInt(), "fixture must be KDBX major version 4")
        assertTrue(
            indexOfBytes(bytes, bytes("9e298b1956db4773b23dfc3ec6f0a1e6")) >= 0,
            "the header must name Argon2id",
        )
        assertEquals(
            -1,
            indexOfBytes(bytes, bytes("ef636ddf8c29444b91f7a9a403e30a0c")),
            "and must not name Argon2d",
        )

        val result = KdbxImporter.parse(bytes, password.toCharArray())
        assertTrue(result is Outcome.Success, "an Argon2id database must import: $result")
        val item = result.value.items.single()
        assertEquals("Argon2id login", item.title)
        assertEquals("s3cr3t-p@ss", (item.content as Login).password)
    }

    @Test
    fun `the stock library still cannot open it, which is why this class exists`() {
        val bytes = writeArgon2id(database { rootGroup.addEntry(entry("Argon2id login", "s3cr3t-p@ss")) })
        val thrown = runCatching {
            DomDatabaseWrapper.load(creds(), java.io.ByteArrayInputStream(bytes))
        }.exceptionOrNull()
        assertTrue(thrown != null, "KeePassJava2 2.2.4 must still refuse Argon2id unaided")
        assertTrue(
            generateSequence(thrown) { it.cause }.any { it.message?.startsWith("Unknown Cipher UUID") == true },
            "the refusal must still be the unknown-KDF one, not something else: $thrown",
        )
    }

    @Test
    fun `a wrong password on an argon2id database is a wrong password, not a damaged file`() {
        val bytes = writeArgon2id(database { rootGroup.addEntry(entry("Argon2id login", "s3cr3t-p@ss")) })
        val result = KdbxImporter.parse(bytes, "not the password".toCharArray())
        assertTrue(result is Outcome.Failure)
        assertEquals(
            ImportFailureReason.WrongPassword(keyFileMayBeNeeded = true),
            (result.error.cause as ImportRejection).reason,
            "Argon2id derives a different key, so the header HMAC check is reached and is decisive",
        )
    }

    // -------------------------------------------------------------------------------------------
    // Controls — everything the library already did must keep working through the same header.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `argon2d still imports`() {
        val db = database { rootGroup.addEntry(entry("Argon2d login", "d-pass")) }
        val bytes = ByteArrayOutputStream().use { out -> db.save(creds(), out); out.toByteArray() }
        assertEquals(4, bytes[10].toInt())
        assertTrue(
            indexOfBytes(bytes, bytes("ef636ddf8c29444b91f7a9a403e30a0c")) >= 0,
            "the default writer must still produce Argon2d",
        )
        val result = KdbxImporter.parse(bytes, password.toCharArray())
        assertTrue(result is Outcome.Success, "$result")
        assertEquals("d-pass", (result.value.items.single().content as Login).password)
    }

    @Test
    fun `kdbx 3 1 with its AES key derivation still imports`() {
        val db = database { rootGroup.addEntry(entry("Legacy login", "aes-pass")) }
        @Suppress("DEPRECATION")
        val format = org.linguafranca.pwdb.kdbx.KdbxStreamFormat(
            org.linguafranca.pwdb.kdbx.KdbxStreamFormat.Version.KDBX31,
        )
        val bytes = ByteArrayOutputStream().use { out -> db.save(format, creds(), out); out.toByteArray() }
        assertEquals(3, bytes[10].toInt(), "fixture must be KDBX major version 3")
        val result = KdbxImporter.parse(bytes, password.toCharArray())
        assertTrue(result is Outcome.Success, "$result")
        assertEquals("aes-pass", (result.value.items.single().content as Login).password)
    }

    @Test
    fun `a ChaCha20 payload cipher still imports`() {
        // The cipher and the KDF are separate header fields, and only the KDF is intercepted. This
        // is the control that says so — Argon2id *and* ChaCha20 in one container.
        //
        // Two library quirks the fixture has to work around, both measured 2026-09-13 and neither
        // reachable from the reader: `setCipherUuid(UUID)` records the id but leaves
        // `cipherAlgorithm` on AES (only the `byte[]` overload sets both), and the default header
        // generates AES's 16-byte IV, which ChaCha7539 rejects. Writing ChaCha with this library
        // therefore needs both set by hand; reading one needs nothing.
        val db = database { rootGroup.addEntry(entry("ChaCha login", "cha-pass")) }
        val header = Argon2idKdbxHeader(4).apply {
            setKdfParameters(argon2idParameters())
            setCipherUuid(ChaCha.getInstance().cipherUuid)
            setCipherAlgorithm(ChaCha.getInstance())
            setEncryptionIv(ByteArray(12) { it.toByte() })
        }
        val bytes = ByteArrayOutputStream().use { out ->
            db.save(org.linguafranca.pwdb.kdbx.KdbxStreamFormat(header), creds(), out)
            out.toByteArray()
        }
        val result = KdbxImporter.parse(bytes, password.toCharArray())
        assertTrue(result is Outcome.Success, "$result")
        assertEquals("cha-pass", (result.value.items.single().content as Login).password)
    }

    @Test
    fun `an unimplemented algorithm is still reported as unsupported`() {
        // Argon2id is gone from this arm, but the arm is not: a KDF nobody has written yet must
        // still be named honestly rather than blamed on the file.
        val db = database { rootGroup.addEntry(entry("x", "y")) }
        val bytes = ByteArrayOutputStream().use { out -> db.save(creds(), out); out.toByteArray() }
        val argon2d = bytes("ef636ddf8c29444b91f7a9a403e30a0c")
        val invented = bytes("00112233445566778899aabbccddeeff")
        val at = indexOfBytes(bytes, argon2d)
        assertTrue(at >= 0)
        val patched = bytes.copyOf().also { invented.copyInto(it, at) }
        val result = KdbxImporter.parse(patched, password.toCharArray())
        assertTrue(result is Outcome.Failure)
        assertEquals(
            ImportFailureReason.KdbxSupportBroken,
            (result.error.cause as ImportRejection).reason,
        )
    }

    @Test
    fun `the argon2id parameter block round trips unchanged`() {
        // `getKdfParameters` has to answer with the dictionary `setKdfParameters` withheld from the
        // library, and that is what the writer serialises. If it did not, the container would be
        // written with whatever the superclass still held.
        val header = Argon2idKdbxHeader(4).apply { setKdfParameters(argon2idParameters()) }
        val written = header.kdfParameters!!
        assertEquals(Argon2idKdf.UUID, written.mustGet("\$UUID").asUuid())
        assertEquals(16L * 1024 * 1024, written.mustGet("M").asLong())
        assertContentEquals(
            Argon2idKdf.getTransformedKey(ByteArray(32), written),
            header.getTransformedKeyDigest(ByteArray(32)),
            "the header must derive through Argon2id, not through the library's enum",
        )
    }
}
