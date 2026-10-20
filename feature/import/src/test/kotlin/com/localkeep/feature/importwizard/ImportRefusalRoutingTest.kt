package com.localkeep.feature.importwizard

import com.localkeep.core.import.ImportFailureReason
import com.localkeep.core.import.SourceFormat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every unreadable file used to get the same sentence — *"That file isn't in a format LocalKeep
 * recognizes. Check you exported the unencrypted format, and that the file didn't get truncated."*
 * — for a 0-byte file, a binary file, an unbindable header and a non-KDBX file under the KeePass
 * tile alike. KeePass has no "unencrypted format" to export, the wizard had just asked for a
 * password, and `core_import_error_not_kdbx_file` existed but was unreachable because
 * `importerFor` returns null before any importer authors a typed reason (1B-F3 / 1A-F5,
 * 2026-09-20).
 */
class ImportRefusalRoutingTest {

    private fun refusal(source: ImportSource?, body: String) =
        ImportWizardViewModel.refusalFor(source, body.toByteArray())

    /** A zip/binary header with NUL bytes — the shape that must not be called a header problem. */
    private val binary = ByteArray(16).also {
        it[8] = 'P'.code.toByte()
        it[9] = 'K'.code.toByte()
    }

    @Test
    fun `a zero-byte file is named as empty, not as an unknown format`() {
        assertEquals(
            ImportError.Simple(R.string.import_error_source_file_empty),
            ImportWizardViewModel.refusalFor(ImportSource.CSV, ByteArray(0)),
        )
    }

    @Test
    fun `a non-KDBX file under the KeePass tile gets the KeePass sentence`() {
        assertEquals(
            ImportError.FromImporter(ImportFailureReason.NotAKdbxFile),
            refusal(ImportSource.KEEPASS, "<?xml version=\"1.0\"?><KeePassFile/>"),
        )
    }

    @Test
    fun `a delimited file whose header binds nothing is told about its header`() {
        assertEquals(
            ImportError.Simple(R.string.import_error_csv_header_unrecognised),
            refusal(ImportSource.CSV, "alpha,beta,gamma\n1,2,3"),
        )
        assertEquals(
            "a semicolon file with no password column is still a header problem",
            ImportError.Simple(R.string.import_error_csv_header_unrecognised),
            refusal(ImportSource.CHROME, "alpha;beta;gamma\n1;2;3"),
        )
    }

    /**
     * Keeper's CSV export has no header row (the corpus's `keeper/export.csv`), so the header sentence
     * — "export again with the column headings included" — is a remedy a Keeper user cannot follow.
     * A comma file the Keeper tile cannot read was answered with it anyway (device report D1
     * finding 2, 2026-10-04: eight cells, one past Keeper's seven fixed columns, so its custom-field
     * pairs do not pair up). The tile's own caveat already says what to do instead: export JSON.
     */
    @Test
    fun `a CSV the Keeper tile cannot read is named as a Keeper export, not sent to add headings`() {
        assertEquals(
            ImportError.FromImporter(ImportFailureReason.MalformedExport(SourceFormat.KEEPER)),
            refusal(ImportSource.KEEPER, "Work,K3Eight,k3.user,K3Pass!1,https://k3.example,note three,,\n"),
        )
    }

    @Test
    fun `binary bytes under a branded tile name that brand`() {
        assertEquals(
            ImportError.FromImporter(ImportFailureReason.MalformedExport(SourceFormat.BITWARDEN)),
            ImportWizardViewModel.refusalFor(ImportSource.BITWARDEN, binary),
        )
    }

    /** Only the tile that says "I don't know what this is" keeps the generic sentence. */
    @Test
    fun `binary bytes under the generic tile keep the generic sentence`() {
        assertEquals(
            ImportError.Simple(R.string.import_error_unrecognised_format),
            ImportWizardViewModel.refusalFor(ImportSource.CSV, binary),
        )
    }

    /**
     * The device case that survived the first fix: a Bitwarden export cut off mid-file.
     *
     * Bitwarden's `.json` is minified — one long line, dozens of commas — so `looksLikeDelimitedText`
     * read it as a wide CSV header and the tile answered with advice about column headings, for a
     * file that has no header row (1A-F3 re-verify, 2026-09-20). The first 103 bytes of the fixture
     * on emulator-5556, verbatim.
     */
    @Test
    fun `a truncated Bitwarden JSON is named as a Bitwarden export, not as a CSV header`() {
        val truncated =
            """{"encrypted":false,"folders":[{"id":"f1","name":"Work"}],"items":[{"id":"i1","type":1,"name":"Example","""
        assertEquals(
            ImportError.FromImporter(ImportFailureReason.MalformedExport(SourceFormat.BITWARDEN)),
            refusal(ImportSource.BITWARDEN, truncated),
        )
    }

    /** A JSON array, and a JSON behind a BOM, are the same answer — and under Other/CSV, generic. */
    @Test
    fun `JSON is never described as a header problem`() {
        assertEquals(
            ImportError.FromImporter(ImportFailureReason.MalformedExport(SourceFormat.PROTONPASS)),
            refusal(ImportSource.PROTONPASS, "\n  [{\"a\":1,\"b\":2},{\"c\":3}"),
        )
        assertEquals(
            "a BOM must not hide the opening brace",
            ImportError.FromImporter(ImportFailureReason.MalformedExport(SourceFormat.BITWARDEN)),
            ImportWizardViewModel.refusalFor(
                ImportSource.BITWARDEN,
                byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                    """{"items":[{"id":"i1","name":"x",""".toByteArray(),
            ),
        )
        assertEquals(
            "under Other/CSV the user has said they do not know what the file is",
            ImportError.Simple(R.string.import_error_unrecognised_format),
            refusal(ImportSource.CSV, """{"items":[{"id":"i1","name":"x","""),
        )
    }
}
