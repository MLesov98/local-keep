package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An importer's failure must reach the caller as a typed [ImportFailureReason], never as English
 * text a UI layer might echo verbatim.
 *
 * Importers parse untrusted files with third-party libraries, and those libraries put whatever they
 * like in an exception message — including a quoted fragment of the file being parsed, a path, or
 * an internal class name. None of that may reach the UI.
 *
 * This test used to assert that `AppError.message` itself was safe text, because
 * `ImportWizardViewModel.ImportError.FromImporter` rendered that field verbatim — which was exactly
 * the leak: `AppError.message` is documented as
 * internal-only, but this one code path showed it to the user anyway, forever, in English.
 *
 * **RED without the fix, GREEN after it.** Before this migration, `FromImporter` held a `String`
 * (`AppError.message`), and the first two tests below — asserting a specific [ImportFailureReason]
 * value came back — would not even compile against that shape, let alone pass: there was no typed
 * reason to assert on. The fix is `ImportWizardViewModel.ImportError.FromImporter` now holding an
 * [ImportFailureReason] read off `AppError.cause` (an [ImportRejection]), never `AppError.message`.
 */
class ImportErrorHygieneTest {

    private fun reasonOf(outcome: Outcome<*>): ImportFailureReason {
        val failure = outcome as? Outcome.Failure ?: error("expected a failure, got $outcome")
        val rejection = failure.error.cause as? ImportRejection
            ?: error("AppError.cause was not an ImportRejection carrying a typed reason: ${failure.error.cause}")
        return rejection.reason
    }

    /** A byte sequence no importer can make sense of, so every one of them takes its failure path. */
    private val garbage = "not json, not a zip, not a database".toByteArray()

    @Test
    fun `bitwarden rejects a password-protected export with a typed reason`() {
        // `encrypted` with no `passwordProtected` is Bitwarden's account-restricted shape, so this
        // is the reason that names the account key rather than the one that names a password
        // (V3-02, 2026-09-17). The password-protected case is asserted right below it.
        val json = """{"encrypted":true,"items":[],"folders":[]}"""
        assertEquals(
            ImportFailureReason.AccountEncrypted(SourceFormat.BITWARDEN),
            reasonOf(BitwardenJsonImporter.parse(json.toByteArray())),
        )
        val passwordProtected =
            """{"encrypted":true,"passwordProtected":true,"items":[],"folders":[]}"""
        assertEquals(
            ImportFailureReason.ContainerEncrypted(SourceFormat.BITWARDEN),
            reasonOf(BitwardenJsonImporter.parse(passwordProtected.toByteArray())),
        )
    }

    /**
     * `detect` has to recognise the files `parse` has a reason for, or the reason cannot happen.
     *
     * The test above calls `parse` directly, which is how a typed reason could be asserted, pass,
     * and still be unreachable in the app: the wizard picks an importer with `detect` first, and
     * these three inputs were all rejected there. A password-protected Bitwarden export has an
     * empty `items`/`folders`, and both 1Password cases were decided inside `detect`'s own capped
     * read. All three reached the user as "unrecognised format" — the wizard declining to name a
     * problem it had a specific reason for.
     */
    @Test
    fun `detect recognises the files parse has a typed reason for`() {
        assertTrue(
            BitwardenJsonImporter.detect("""{"encrypted":true,"items":[],"folders":[]}""".toByteArray()),
            "a password-protected Bitwarden export is still a Bitwarden export",
        )
        assertTrue(
            OnePuxImporter.detect(onePuxWith("export.data", "not json at all")),
            "a .1pux whose export.data is unreadable is still a .1pux",
        )
        assertFalse(
            OnePuxImporter.detect(onePuxWith("attachments/x.bin", "irrelevant")),
            "control: a zip with no export.data entry is not a 1Password export",
        )
    }

    /** A minimal `.1pux`-shaped zip holding one named entry. */
    private fun onePuxWith(entryName: String, content: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry(entryName))
            zip.write(content.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `aegis rejects an encrypted export with a typed reason`() {
        val json = """{"version":1,"header":{},"db":"BASE64CIPHERTEXT"}"""
        assertEquals(
            ImportFailureReason.ContainerEncrypted(SourceFormat.AEGIS),
            reasonOf(AegisJsonImporter.parse(json.toByteArray())),
        )
    }

    @Test
    fun `garbage input never yields a reason the importer did not author`() {
        // Every importer's failure path is exercised by unparseable bytes, and every one lands on
        // MalformedExport — a member of the closed set ImportFailureReason declares — never
        // something derived from the library's own exception. An allow-list rather than a pattern:
        // the set of reasons a user can see is closed and reviewable, so a new one has to be added
        // to ImportFailureReason deliberately, which is the moment to ask whether it leaks anything.
        val cases: List<Triple<String, ImportFailureReason, Importer>> = listOf(
            Triple("Bitwarden", ImportFailureReason.MalformedExport(SourceFormat.BITWARDEN), BitwardenJsonImporter),
            Triple("Aegis", ImportFailureReason.MalformedExport(SourceFormat.AEGIS), AegisJsonImporter),
            // Garbage bytes aren't a zip at all, so readExportDataEntry finds no "export.data"
            // entry — OnePuxMissingExportData, the more specific of the two reasons this importer
            // can report, rather than MalformedExport (which is for a real zip with malformed JSON
            // inside it).
            Triple("1pux", ImportFailureReason.OnePuxMissingExportData, OnePuxImporter),
        )
        for ((name, expected, importer) in cases) {
            assertEquals(expected, reasonOf(importer.parse(garbage)), "$name importer produced the wrong reason")
        }
    }

    /**
     * What these tests do and do not prove, restated for the typed-reason world: they pass with or
     * without library text ever reaching a catch site, because [ImportFailureReason] has no field a
     * library's own message could travel through even if one did. What they enforce going forward is
     * that a new catch site cannot reintroduce the leak by construction — the only way to produce an
     * [ImportError.FromImporter] is a value from this closed set.
     *
     * This final check is defense in depth on the field that is no longer read: `AppError.message`
     * is internal-only by convention, but a message still built from `t.message` (rather than a
     * fixed string) would be a hygiene regression even before it reached anything, so it stays
     * checked here rather than being deleted along with the assertions on its exact wording.
     */
    @Test
    fun `AppError message still carries no library internals, even though nothing reads it anymore`() {
        for (outcome in listOf(BitwardenJsonImporter.parse(garbage), AegisJsonImporter.parse(garbage), OnePuxImporter.parse(garbage))) {
            val failure = outcome as? Outcome.Failure ?: error("expected a failure, got $outcome")
            val message = failure.error.message
            assertFalse(message.contains("Exception"), "leaked an exception class name: $message")
            assertFalse(message.contains("kotlinx"), "leaked a library package name: $message")
            assertFalse(message.contains("/"), "leaked something path-shaped: $message")
            assertFalse(message.contains("JSONObject"), "leaked a parser internal: $message")
        }
    }

    @Test
    fun `a successful parse carries no ImportRejection to misread as a failure`() {
        // Not a redundant check: reasonOf's cast would silently accept a Success wrapped wrong. This
        // pins the other direction so the helper itself cannot rot into a false positive.
        val json = """{"items":[{"type":1,"name":"ok","login":{"username":"me","password":"pw"}}]}"""
        assertTrue(BitwardenJsonImporter.parse(json.toByteArray()) is Outcome.Success)
    }
}
