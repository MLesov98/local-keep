package com.localkeep.core.import

import com.localkeep.core.common.AppError
import com.localkeep.core.common.Outcome
import com.localkeep.core.model.Login
import com.localkeep.core.model.TotpAlgorithm
import org.linguafranca.pwdb.kdbx.KdbxCreds
import org.linguafranca.pwdb.kdbx.KdbxStreamFormat
import org.linguafranca.pwdb.kdbx.dom.DomDatabaseWrapper
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dependency-safety regression test (roadmap §I) — NOT importer-logic coverage.
 *
 * It proves that excluding `org.apache.httpcomponents:httpcore` from the KeePassJava2 dependency
 * (see build.gradle.kts) does not break the KDBX *load* path: it creates a real, password-encrypted
 * `.kdbx` in memory and re-imports it through [KdbxImporter] with httpcore absent from the test
 * classpath. If httpcore were actually needed by the KDBX codec, this would fail with
 * NoClassDefFoundError instead of green.
 *
 * READ THIS BEFORE TRUSTING IT. A green run here says **nothing** about whether KDBX import works
 * on a device. That is not a hypothetical: the `simple` backend this module used to depend on
 * needed the StAX API, which the JVM ships and Android does not, so this test passed for months
 * while every real import failed. Deciding a backend is safe is the job of `checkNoJvmOnlyXmlApis`
 * (build-graph) and of actually running an import on a device — not of this file.
 *
 * It does deliberately build the fixture with [DomDatabaseWrapper], the same backend the app ships,
 * so the writer cannot silently drift away from the reader under test.
 */
class KdbxRoundTripTest {

    @Test
    fun `a kdbx created and re-imported round-trips with httpcore excluded`() {
        val password = "correct horse battery staple"
        val creds = KdbxCreds(password.toByteArray(Charsets.UTF_8))

        // Build a fresh database with one entry, entirely in memory.
        val db = DomDatabaseWrapper()
        val entry = db.newEntry().apply {
            title = "Example"
            username = "alice@example.com"
            setPassword("s3cr3t-p@ss")
            url = "https://example.com/login"
            notes = "imported from keepass"
        }
        db.rootGroup.addEntry(entry)

        val encrypted = ByteArrayOutputStream().use { out ->
            db.save(creds, out)
            out.toByteArray()
        }

        // Sanity: the bytes are a real KDBX container (magic detected) and it decrypts + imports.
        assertTrue(KdbxImporter.detect(encrypted), "output must be a valid KDBX container")

        val result = KdbxImporter.parse(encrypted, password.toCharArray())
        assertTrue(result is Outcome.Success, "KDBX import must succeed with httpcore excluded")

        val items = result.value.items
        assertEquals(1, items.size)
        val login = items.single().content as Login
        assertEquals("Example", items.single().title)
        assertEquals("alice@example.com", login.username)
        assertEquals("s3cr3t-p@ss", login.password)
        assertEquals("https://example.com/login", login.uris.single().uri)
        assertEquals("imported from keepass", login.notes)
    }

    @Test
    fun `a TOTP seed stored the KeePassXC way survives the round trip`() {
        // getProperty/setProperty on a custom field is the one thing that differs structurally
        // between the backends (DOM element vs. mapped object), and TOTP import depends on it.
        val password = "correct horse battery staple"
        val creds = KdbxCreds(password.toByteArray(Charsets.UTF_8))
        val db = DomDatabaseWrapper()
        db.rootGroup.addEntry(
            db.newEntry().apply {
                title = "With TOTP"
                setProperty("TOTP Seed", "JBSWY3DPEHPK3PXP")
            },
        )
        val encrypted = ByteArrayOutputStream().use { out -> db.save(creds, out); out.toByteArray() }

        val result = KdbxImporter.parse(encrypted, password.toCharArray())
        assertTrue(result is Outcome.Success, "import must succeed")
        val totp = (result.value.items.single().content as Login).totp
        assertEquals("JBSWY3DPEHPK3PXP", totp?.secretBase32)
    }

    @Test
    fun `KeePassXC TOTP settings apply the legacy step and size encoding`() {
        val password = "correct horse battery staple"
        val creds = KdbxCreds(password.toByteArray(Charsets.UTF_8))
        val db = DomDatabaseWrapper()
        db.rootGroup.addEntry(
            db.newEntry().apply {
                title = "KeePassXC settings"
                setProperty("TOTP Seed", "JBSWY3DPEHPK3PXP")
                setProperty("TOTP Settings", "30;8;SHA1")
            },
        )
        db.rootGroup.addEntry(
            db.newEntry().apply {
                title = "KeePassXC six digit settings"
                setProperty("TOTP Seed", "JBSWY3DPEHPK3PXP")
                setProperty("TOTP Settings", "60;6;SHA1")
            },
        )
        val encrypted = ByteArrayOutputStream().use { out -> db.save(creds, out); out.toByteArray() }

        val result = KdbxImporter.parse(encrypted, password.toCharArray())
        assertTrue(result is Outcome.Success, "import must succeed")
        val totps = result.value.items.map { (it.content as Login).totp }
        assertEquals(listOf(8, 6), totps.map { it?.digits })
        assertEquals(listOf(30, 60), totps.map { it?.periodSec })
    }

    @Test
    fun `malformed KeePassXC TOTP settings remain a custom field`() {
        val password = "correct horse battery staple"
        val creds = KdbxCreds(password.toByteArray(Charsets.UTF_8))
        val db = DomDatabaseWrapper()
        db.rootGroup.addEntry(
            db.newEntry().apply {
                title = "Malformed settings"
                setProperty("TOTP Seed", "JBSWY3DPEHPK3PXP")
                setProperty("TOTP Settings", "not;settings")
            },
        )
        val encrypted = ByteArrayOutputStream().use { out -> db.save(creds, out); out.toByteArray() }

        val result = KdbxImporter.parse(encrypted, password.toCharArray())
        assertTrue(result is Outcome.Success, "import must succeed")
        val item = result.value.items.single()
        assertEquals("not;settings", item.customFields.single { it.name == "TOTP Settings" }.value)
    }

    @Test
    fun `a KeeOtp field carries its own step and size`() {
        val password = "correct horse battery staple"
        val creds = KdbxCreds(password.toByteArray(Charsets.UTF_8))
        val db = DomDatabaseWrapper()
        db.rootGroup.addEntry(
            db.newEntry().apply {
                title = "KeeOtp"
                setProperty("otp", "key=JBSWY3DPEHPK3PXP&step=60&size=8")
            },
        )
        val encrypted = ByteArrayOutputStream().use { out -> db.save(creds, out); out.toByteArray() }

        val result = KdbxImporter.parse(encrypted, password.toCharArray())
        assertTrue(result is Outcome.Success, "import must succeed")
        val totp = (result.value.items.single().content as Login).totp
        assertEquals("JBSWY3DPEHPK3PXP", totp?.secretBase32)
        assertEquals(8, totp?.digits)
        assertEquals(60, totp?.periodSec)
    }

    @Test
    fun `a KeeOtp field with an out-of-range size or step is dropped, not built invalid`() {
        // TotpGenerator requires digits in {6, 8} and periodSec > 0 (require() calls guarded by
        // runCatching at every call site), so size=7 or step=0 must never reach a Totp — that
        // would ship a 2FA entry that silently never renders a code. The raw field must survive
        // as a custom field instead of being reported consumed and lost.
        val password = "correct horse battery staple"
        val creds = KdbxCreds(password.toByteArray(Charsets.UTF_8))
        val db = DomDatabaseWrapper()
        db.rootGroup.addEntry(
            db.newEntry().apply {
                title = "Out-of-range size"
                setProperty("otp", "key=JBSWY3DPEHPK3PXP&size=7&step=30")
            },
        )
        db.rootGroup.addEntry(
            db.newEntry().apply {
                title = "Zero step"
                setProperty("otp", "key=JBSWY3DPEHPK3PXP&size=6&step=0")
            },
        )
        val encrypted = ByteArrayOutputStream().use { out -> db.save(creds, out); out.toByteArray() }

        val result = KdbxImporter.parse(encrypted, password.toCharArray())
        assertTrue(result is Outcome.Success, "import must succeed")
        val items = result.value.items
        assertEquals(2, items.size)
        items.forEach { item ->
            assertNull((item.content as Login).totp, "an out-of-range size/step must not become a TOTP")
            assertEquals(
                "key=JBSWY3DPEHPK3PXP&size=7&step=30".takeIf { item.title == "Out-of-range size" }
                    ?: "key=JBSWY3DPEHPK3PXP&size=6&step=0",
                item.customFields.single { it.name == "otp" }.value,
                "the raw otp field must survive as a custom field, not be silently dropped",
            )
        }
    }

    @Test
    fun `a KeeOtp field with no secret imports the entry without a TOTP`() {
        // "key=" satisfies the null check but yields an empty secret, which Base32.decode rejects
        // with IllegalArgumentException at code-generation time. An unusable TOTP must be dropped
        // at import, exactly as TotpFieldParser drops an empty or non-Base32 seed.
        val password = "correct horse battery staple"
        val creds = KdbxCreds(password.toByteArray(Charsets.UTF_8))
        val db = DomDatabaseWrapper()
        db.rootGroup.addEntry(
            db.newEntry().apply {
                title = "Empty KeeOtp"
                setProperty("otp", "key=&step=30&size=6")
            },
        )
        db.rootGroup.addEntry(
            db.newEntry().apply {
                title = "Garbage KeeOtp"
                setProperty("otp", "key=not!base32&step=30&size=6")
            },
        )
        val encrypted = ByteArrayOutputStream().use { out -> db.save(creds, out); out.toByteArray() }

        val result = KdbxImporter.parse(encrypted, password.toCharArray())
        assertTrue(result is Outcome.Success, "a useless TOTP field must not fail the import")
        val logins = result.value.items.map { it.content as Login }
        assertEquals(2, logins.size)
        logins.forEach { assertNull(it.totp, "an undecodable KeeOtp secret must not become a TOTP") }
    }

    @Test
    fun `a TOTP seed stored the KeePass 2_47 way becomes a live code`() {
        // The third convention: KeePass 2.47's own fields, also written by KeeTrayTOTP and KeeWeb.
        // The seed and its parameters live in *separate* TimeOtp-* strings, so findTotp's two
        // single-field lookups matched none of it and the seed arrived as a HIDDEN custom field
        // under its raw source name — an entry with no live code, while the wizard's KeePass caveat
        // tells the user 2FA seeds come across (fixed 2026-09-13).
        val password = "correct horse battery staple"
        val creds = KdbxCreds(password.toByteArray(Charsets.UTF_8))
        val db = DomDatabaseWrapper()
        db.rootGroup.addEntry(
            db.newEntry().apply {
                title = "KeePass 2.47 TOTP"
                setProperty("TimeOtp-Secret-Base32", "JBSWY3DPEHPK3PXP")
                setProperty("TimeOtp-Length", "8")
                setProperty("TimeOtp-Period", "60")
                setProperty("TimeOtp-Algorithm", "HMAC-SHA-256")
            },
        )
        db.rootGroup.addEntry(
            db.newEntry().apply {
                title = "Seed only"
                setProperty("TimeOtp-Secret-Base32", "JBSWY3DPEHPK3PXP")
            },
        )
        val encrypted = ByteArrayOutputStream().use { out -> db.save(creds, out); out.toByteArray() }

        val result = KdbxImporter.parse(encrypted, password.toCharArray())
        assertTrue(result is Outcome.Success, "import must succeed")
        val byTitle = result.value.items.associateBy { it.title }

        val full = byTitle.getValue("KeePass 2.47 TOTP")
        val fullTotp = (full.content as Login).totp
        assertEquals("JBSWY3DPEHPK3PXP", fullTotp?.secretBase32)
        assertEquals(8, fullTotp?.digits)
        assertEquals(60, fullTotp?.periodSec)
        assertEquals(TotpAlgorithm.SHA256, fullTotp?.algorithm)
        // Consumed, so the same seed is not also carried as a raw-named custom field beside it.
        assertTrue(
            full.customFields.none { it.name.startsWith("TimeOtp-") },
            "the fields the TOTP was built from must not be duplicated as custom fields",
        )

        // The siblings are optional: a bare seed takes the format's own defaults.
        val bare = (byTitle.getValue("Seed only").content as Login).totp
        assertEquals("JBSWY3DPEHPK3PXP", bare?.secretBase32)
        assertEquals(6, bare?.digits)
        assertEquals(30, bare?.periodSec)
        assertEquals(TotpAlgorithm.SHA1, bare?.algorithm)
    }

    @Test
    fun `an unreadable TimeOtp sibling leaves every field as a custom field`() {
        // Refusing beats defaulting. An algorithm this app cannot compute, folded to SHA-1, renders
        // six digits that are confidently wrong — worse than no code — and a non-numeric length is
        // not a length. Both must leave all four fields unconsumed so customFields carries them.
        //
        // Green before the fix as well as after, and deliberately so: it guards the fix against
        // over-reaching, not the defect. Measured 2026-09-13 — with findTimeOtp removed this test
        // still passes while the one above fails, which is the whole point of keeping them apart.
        val password = "correct horse battery staple"
        val creds = KdbxCreds(password.toByteArray(Charsets.UTF_8))
        val db = DomDatabaseWrapper()
        db.rootGroup.addEntry(
            db.newEntry().apply {
                title = "Uncomputable algorithm"
                setProperty("TimeOtp-Secret-Base32", "JBSWY3DPEHPK3PXP")
                setProperty("TimeOtp-Algorithm", "HMAC-SHA-384")
            },
        )
        db.rootGroup.addEntry(
            db.newEntry().apply {
                title = "Nonsense length"
                setProperty("TimeOtp-Secret-Base32", "JBSWY3DPEHPK3PXP")
                setProperty("TimeOtp-Length", "seven")
            },
        )
        db.rootGroup.addEntry(
            db.newEntry().apply {
                title = "Out-of-range length"
                setProperty("TimeOtp-Secret-Base32", "JBSWY3DPEHPK3PXP")
                setProperty("TimeOtp-Length", "7")
            },
        )
        val encrypted = ByteArrayOutputStream().use { out -> db.save(creds, out); out.toByteArray() }

        val result = KdbxImporter.parse(encrypted, password.toCharArray())
        assertTrue(result is Outcome.Success, "an unusable TimeOtp field must not fail the import")
        val items = result.value.items
        assertEquals(3, items.size)
        items.forEach { item ->
            assertNull((item.content as Login).totp, "an unreadable sibling must not become a TOTP")
            assertEquals(
                "JBSWY3DPEHPK3PXP",
                item.customFields.single { it.name == "TimeOtp-Secret-Base32" }.value,
                "the raw seed must survive as a custom field, not be silently dropped",
            )
        }
    }

    @Test
    fun `a custom field the user left empty keeps its label`() {
        // The label is itself information the user put in KeePass — "Security answer" with nothing
        // in it yet is a placeholder they made on purpose. Dropping the field on a blank value took
        // the name with it, and a field-level loss has nowhere to be reported (`skipped` is
        // item-level), so the entry read as fully imported with the label simply gone.
        val password = "correct horse battery staple"
        val creds = KdbxCreds(password.toByteArray(Charsets.UTF_8))
        val db = DomDatabaseWrapper()
        db.rootGroup.addEntry(
            db.newEntry().apply {
                title = "Placeholder fields"
                setProperty("Security answer", "")
                setProperty("Recovery code", "abc-123")
            },
        )
        val encrypted = ByteArrayOutputStream().use { out -> db.save(creds, out); out.toByteArray() }

        val result = KdbxImporter.parse(encrypted, password.toCharArray())
        assertTrue(result is Outcome.Success, "import must succeed")
        val fields = result.value.items.single().customFields

        // Control — a field with a value was always carried; this held before the fix too.
        assertEquals("abc-123", fields.single { it.name == "Recovery code" }.value)

        assertEquals("", fields.single { it.name == "Security answer" }.value)
    }

    @Test
    fun `the caller's password array is zeroized, not blanked`() {
        // A CharArray is taken instead of a String so the plaintext can actually be scrubbed;
        // filling it with spaces would leave the length recoverable and is not a wipe.
        val password = "correct horse battery staple".toCharArray()
        KdbxImporter.parse("this is not a kdbx file at all".toByteArray(), password)
        assertTrue(
            password.all { it == '\u0000' },
            "password must be wiped with NUL characters after parse",
        )
    }

    @Test
    fun `a wrong password is reported as a wrong password, not as a damaged file`() {
        // The B4a lesson in test form: every failure used to collapse into one message that blamed
        // the password. Now the message has to be *earned* — this asserts the credential case is
        // still distinguished from the ones below.
        val creds = KdbxCreds("right".toByteArray(Charsets.UTF_8))
        val db = DomDatabaseWrapper().apply { rootGroup.addEntry(newEntry().apply { title = "x" }) }
        val encrypted = ByteArrayOutputStream().use { out -> db.save(creds, out); out.toByteArray() }

        val result = KdbxImporter.parse(encrypted, "wrong".toCharArray())
        assertTrue(result is Outcome.Failure, "wrong password must surface as Outcome.Failure")
        val error = result.error
        assertTrue(error is AppError.Validation, "a wrong password is a validation problem")
        assertTrue(
            "wrong password" in error.message,
            "expected the message to name the password; got: ${error.message}",
        )
        // F22: the wizard resolves ImportFailureReason from AppError.cause, not AppError.message —
        // see ImportWizardViewModel.ImportError.FromImporter. This is the value it actually reads.
        assertEquals(
            ImportFailureReason.WrongPassword(keyFileMayBeNeeded = true),
            (error.cause as ImportRejection).reason,
        )
    }

    @Test
    fun `a wrong password on a KDBX 3 database is not reported as a damaged file`() {
        // KDBX 3.1 has no pre-decryption credential check: the library decrypts first and then
        // compares the stream start bytes, so a wrong password surfaces as
        // "Inconsistent stream start bytes..." rather than either header-verification message.
        // Left unclassified it lands in describeFailure's else arm and tells the user, during a
        // migration, that their intact database looks damaged.
        //
        // The default `save(creds, out)` overload hardcodes a KDBX4 header, so the fixture must be
        // written through the explicit stream-format overload to be version 3 at all.
        val creds = KdbxCreds("right".toByteArray(Charsets.UTF_8))
        val db = DomDatabaseWrapper().apply { rootGroup.addEntry(newEntry().apply { title = "x" }) }
        val encrypted = ByteArrayOutputStream().use { out ->
            db.save(KdbxStreamFormat(KdbxStreamFormat.Version.KDBX31), creds, out)
            out.toByteArray()
        }
        // Control: the fixture really is a KDBX 3.x container and really does open with the right
        // password, so a failure below is about classification and not about a broken fixture.
        assertEquals(3, encrypted[10].toInt(), "fixture must be KDBX major version 3")
        assertTrue(
            KdbxImporter.parse(encrypted, "right".toCharArray()) is Outcome.Success,
            "the KDBX3 fixture must import with the correct password",
        )

        val result = KdbxImporter.parse(encrypted, "wrong".toCharArray())
        assertTrue(result is Outcome.Failure, "wrong password must surface as Outcome.Failure")
        assertEquals(
            ImportFailureReason.WrongPassword(keyFileMayBeNeeded = true),
            (result.error.cause as ImportRejection).reason,
            "expected a KDBX3 credential failure to be classified as a wrong password",
        )
    }

    @Test
    fun `a file that is not a KDBX is not blamed on the password`() {
        val result = KdbxImporter.parse("this is not a kdbx file at all".toByteArray(), "pw".toCharArray())
        assertTrue(result is Outcome.Failure)
        assertTrue(
            "not a KeePass database" in result.error.message,
            "expected the message to name the file; got: ${result.error.message}",
        )
        assertEquals(ImportFailureReason.NotAKdbxFile, (result.error.cause as ImportRejection).reason)
    }
}
