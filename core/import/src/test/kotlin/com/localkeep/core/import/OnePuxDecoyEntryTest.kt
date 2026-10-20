package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * An entry the importer does not want must cost nothing to recognise and a bounded amount to skip
 * (S2-06, 2026-10-02).
 *
 * `detect` walked a `ZipInputStream`, whose `nextEntry` inflates the whole current entry to reach the
 * next one — on the main thread, ahead of any parse — so a small archive whose first entry was a
 * highly compressible decoy inflated as much as it liked before a name was compared. The parse half
 * had the same hole on its worker: only the entries it *kept* were capped.
 */
class OnePuxDecoyEntryTest {

    private val exportData = """{ "accounts": [ { "vaults": [ { "items": [ {
        "categoryUuid": "003", "overview": { "title": "Note" }, "details": { "notesPlain": "hi" }
    } ] } ] } ] }"""

    private fun archive(decoy: ZipOutputStream.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            zip.putNextEntry(ZipEntry(DECOY))
            zip.decoy()
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("export.data"))
            zip.write(exportData.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `detect reads entry names without inflating any entry`() {
        val bytes = archive { write(ByteArray(64 * 1024) { 7 }) }
        // Damage the decoy's deflate stream, which sits right after its 30-byte local header and name.
        // Anything that inflates it fails; the names in the central directory are untouched.
        val dataStart = 30 + DECOY.length
        for (i in dataStart until dataStart + 16) bytes[i] = 0xFF.toByte()

        assertTrue(OnePuxImporter.detect(bytes), "the central directory names export.data")
    }

    @Test
    fun `a decoy entry that inflates past the cap is refused instead of inflated`() {
        // 300 MB of one byte deflates to a few hundred KB — well inside the wizard's 64 MB read cap.
        val chunk = ByteArray(1024 * 1024)
        val bytes = archive { repeat(300) { write(chunk) } }

        val outcome = OnePuxImporter.parse(bytes)

        val failure = assertIs<Outcome.Failure>(outcome)
        assertEquals(
            ImportFailureReason.ExportTooLarge(256),
            (failure.error.cause as? ImportRejection)?.reason,
        )
    }

    /** The control: an ordinary archive with a small entry ahead of export.data still imports. */
    @Test
    fun `a small unrelated entry ahead of export data is still skipped and imported`() {
        val bytes = archive { write(ByteArray(1024) { 1 }) }

        assertTrue(OnePuxImporter.detect(bytes))
        val parsed = assertIs<Outcome.Success<ParsedImport>>(OnePuxImporter.parse(bytes)).value
        assertEquals(1, parsed.items.size)
    }

    private companion object {
        const val DECOY = "export.attributes"
    }
}
