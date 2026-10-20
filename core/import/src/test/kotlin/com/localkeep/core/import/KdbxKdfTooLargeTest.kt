package com.localkeep.core.import

import com.localkeep.core.common.AppError
import com.localkeep.core.common.Outcome
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.linguafranca.pwdb.kdbx.KdbxCreds
import org.linguafranca.pwdb.kdbx.dom.DomDatabaseWrapper

/**
 * A KDBX database whose key derivation will not fit this device must not be called damaged.
 *
 * KeePassXC's default is 64 MiB Argon2d. Android gives an ordinary app 48 MiB on API 28/29, so the
 * derivation dies with an `OutOfMemoryError` before any credential check runs. That error carries a
 * message about an allocation, which matches none of the patterns [KdbxImporter.describeFailure]
 * looks for, so it fell to the catch-all: "the file looks damaged". Measured on a 48 MiB device, the
 * **right** password, the **wrong** password, a key-file database and a genuinely truncated file all
 * produced that one sentence — four situations, one message, and it was false in three of them. The
 * same bytes and the same password imported cleanly on a 192 MiB device seconds later, so the heap
 * was the variable and the file was never at fault. Found by the pre-iOS gap sweep 2026-09-11 (G13).
 */
class KdbxKdfTooLargeTest {

    private fun reasonFor(t: Throwable): ImportFailureReason {
        val error = KdbxImporter.describeFailure(t)
        val validation = assertIs<AppError.Validation>(error)
        val rejection = assertIs<ImportRejection>(validation.cause)
        return rejection.reason
    }

    @Test
    fun `an out-of-memory during the kdf is not a damaged file`() {
        assertEquals(
            ImportFailureReason.KdbxKdfTooLarge,
            reasonFor(OutOfMemoryError("Failed to allocate a 1040 byte allocation")),
        )
    }

    /** The KDBX library wraps what the KDF throws, so the whole cause chain has to be checked. */
    @Test
    fun `an out-of-memory wrapped by the library is still recognised`() {
        val wrapped = IllegalStateException("could not load db", OutOfMemoryError("Java heap space"))
        assertEquals(ImportFailureReason.KdbxKdfTooLarge, reasonFor(wrapped))
    }

    /** The credential split this sits in front of must be untouched. */
    @Test
    fun `a header hmac mismatch is still a wrong password`() {
        assertEquals(
            ImportFailureReason.WrongPassword(keyFileMayBeNeeded = true),
            reasonFor(IllegalStateException("Header HMAC does not match")),
        )
    }

    /** And the catch-all still exists for files that really are damaged. */
    @Test
    fun `an unrecognised failure is still a damaged file`() {
        assertEquals(
            ImportFailureReason.DamagedKdbxFile,
            reasonFor(IllegalStateException("block 3 hmac mismatch")),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // An algorithm this build cannot run is not a damaged file either (fixed
    // 2026-09-13).
    //
    // KeePassJava2 2.2.4 registers exactly one Argon2 variant — Argon2d,
    // EF636DDF-8C29-444B-91F7-A9A403E30A0C — and refuses any other KDF, and any cipher that is not
    // AES or ChaCha20, with a message beginning "Unknown Cipher UUID". That matched none of the
    // patterns above, so an intact Argon2id KDBX4 (what KeePassXC 2.7+ can write) was reported as
    // "the file looks damaged" and the user was steered toward re-exporting a file that is fine.
    // ---------------------------------------------------------------------------------------------

    /** The unsupported-algorithm branch returns [com.localkeep.core.common.AppError.Unknown], not
     *  Validation, so it needs its own reader — [reasonFor] asserts the Validation shape. */
    private fun unknownReasonFor(t: Throwable): Pair<ImportFailureReason, String> {
        val error = KdbxImporter.describeFailure(t)
        val unknown = assertIs<AppError.Unknown>(error)
        val rejection = assertIs<ImportRejection>(unknown.cause)
        return rejection.reason to unknown.message
    }

    @Test
    fun `an unsupported key derivation is not a damaged file`() {
        // Encryption$KeyDerivationFunction.getKdf's own throw, verbatim and uncaused.
        assertEquals(
            ImportFailureReason.KdbxSupportBroken,
            unknownReasonFor(IllegalArgumentException("Unknown Cipher UUID")).first,
        )
    }

    @Test
    fun `an unsupported algorithm wrapped by the library is still recognised`() {
        val wrapped = java.io.IOException(
            "could not load db",
            IllegalArgumentException("Unknown Cipher UUID"),
        )
        assertEquals(ImportFailureReason.KdbxSupportBroken, unknownReasonFor(wrapped).first)
    }

    @Test
    fun `an unsupported cipher is recognised without echoing the uuid out of the file`() {
        // KdbxHeader.setCipherUuid builds its message by appending the UUID it read out of the
        // untrusted file, so the match is on the prefix and the message must never be surfaced —
        // file-derived bytes are never echoed into anything a user sees. ImportRejection
        // carries a typed reason precisely so the UI never reads a library's text, but the
        // AppError.message this branch authors is checked too, because that is the field findings
        // F2/F22 saw reaching the screen unresolved.
        val fileUuid = "9e298b19-56db-4773-b23d-fc3ec6f0a1e6"
        val (reason, message) = unknownReasonFor(IllegalStateException("Unknown Cipher UUID$fileUuid"))
        assertEquals(ImportFailureReason.KdbxSupportBroken, reason)
        assertFalse(fileUuid in message, "the file's own UUID must not reach the message; got: $message")
        assertFalse("Unknown Cipher UUID" in message, "the library's text must not be echoed; got: $message")
    }

    @Test
    fun `a database naming a KDF this build has no implementation for is not a damaged file`() {
        // This test used to assert that an *Argon2id* database was refused, and pinned the exact
        // behaviour the sweep filed as F4: KeePassJava2 2.2.4 knows only Argon2d, so the default
        // modern KeePassXC export was unopenable and the app said so honestly and uselessly.
        // Argon2id is implemented now (`KdbxArgon2idTest`), so the fixture here names a KDF UUID
        // nobody has written at all — which is what this arm is still for.
        //
        // End to end, no new binary fixture: the library's own writer produces a KDBX4 whose header
        // names Argon2d, and overwriting those 16 bytes is length-preserving. The KDF id is read in
        // getOuterHeaderFields before readOuterHeaderVerification runs, so the header HMAC is never
        // reached and the file needs no re-signing.
        val password = "correct horse battery staple"
        val creds = KdbxCreds(password.toByteArray(Charsets.UTF_8))
        val db = DomDatabaseWrapper().apply { rootGroup.addEntry(newEntry().apply { title = "x" }) }
        val bytes = ByteArrayOutputStream().use { out -> db.save(creds, out); out.toByteArray() }

        // Control 1: the fixture really is a KDBX4 and really does open, so a failure below is
        // about the KDF and not about a broken fixture.
        assertEquals(4, bytes[10].toInt(), "fixture must be KDBX major version 4")
        assertTrue(
            KdbxImporter.parse(bytes, password.toCharArray()) is Outcome.Success,
            "the unpatched Argon2d fixture must import with the correct password",
        )

        // Anchor, asserted rather than assumed: exactly one Argon2d UUID in the container.
        val argon2d = hexBytes("ef636ddf8c29444b91f7a9a403e30a0c")
        val unimplemented = hexBytes("00112233445566778899aabbccddeeff")
        val at = indexOfBytes(bytes, argon2d, 0)
        assertTrue(at >= 0, "the Argon2d KDF UUID must be present in the header to patch it")
        assertEquals(-1, indexOfBytes(bytes, argon2d, at + 1), "the KDF UUID must occur exactly once")

        val patched = bytes.copyOf().also { unimplemented.copyInto(it, at) }
        val result = KdbxImporter.parse(patched, password.toCharArray())
        assertTrue(result is Outcome.Failure, "a KDF this build has no code for cannot be opened")
        assertEquals(
            ImportFailureReason.KdbxSupportBroken,
            (result.error.cause as ImportRejection).reason,
            "an intact database using an unimplemented KDF must not be called damaged",
        )
    }

    /** The control that says the *Argon2id* half of this arm really did retire — see F4. */
    @Test
    fun `an argon2id database is no longer refused`() {
        val password = "correct horse battery staple"
        val creds = KdbxCreds(password.toByteArray(Charsets.UTF_8))
        val db = DomDatabaseWrapper().apply { rootGroup.addEntry(newEntry().apply { title = "x" }) }
        val header = Argon2idKdbxHeader(4).apply {
            setKdfParameters(
                Argon2idKdf.createKdfParameters().apply {
                    putULong("M", 16L * 1024 * 1024)
                    putULong("I", 2L)
                    putUInt("P", 2)
                },
            )
        }
        val bytes = ByteArrayOutputStream().use { out ->
            db.save(org.linguafranca.pwdb.kdbx.KdbxStreamFormat(header), creds, out)
            out.toByteArray()
        }
        assertTrue(
            indexOfBytes(bytes, hexBytes("9e298b1956db4773b23dfc3ec6f0a1e6"), 0) >= 0,
            "the fixture must actually name Argon2id",
        )
        assertTrue(
            KdbxImporter.parse(bytes, password.toCharArray()) is Outcome.Success,
            "Argon2id must import rather than reach the unsupported-algorithm arm",
        )
    }

    private fun hexBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        for (i in from..haystack.size - needle.size) {
            if ((needle.indices).all { haystack[i + it] == needle[it] }) return i
        }
        return -1
    }
}
