package com.localkeep.feature.importwizard

import com.localkeep.core.import.BitwardenJsonImporter
import com.localkeep.core.import.CsvImporter
import com.localkeep.core.import.KeeperImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A chosen tile pins which importers may claim the file.
 *
 * `importerFor` used to prefer the tile's importer and then fall back to
 * `ALL_IMPORTERS.firstOrNull { detect }`, which handed a file the chosen source had *refused* to
 * whichever other format claimed it first. `KeeperImporter.detect` reads a header-less positional
 * CSV, so a truncated Bitwarden JSON previewed as `1 item found · Keeper` with a raw JSON fragment
 * offered as a vault item (1A-F3, 2026-09-20).
 *
 * The tile's own importer plus [CsvImporter], and nothing else — the CSV entry because every vendor
 * here also exports a plain CSV, which is how picking Dashlane and handing it a `credentials.csv`
 * has always worked. Only **Other / CSV** still sniffs every format.
 */
class ImporterTilePinningTest {

    /**
     * The finding's own repro — a Bitwarden export cut off where the fragment reads, to a
     * positional CSV parser, as one row of seven cells titled `folders:[{id:f1`.
     */
    private val truncatedBitwarden = "{\"encrypted\":false,\"folders\":[{\"id\":\"f1\",\"name\":\"Work\"}],\"items\":[{\"type\":1,\"name\":\"Gmail\",\"notes\":null,"

    private val bitwardenJson =
        """{"encrypted":false,"items":[{"type":1,"name":"x","login":{"username":"a","password":"b"}}]}"""

    private val plainCsv = "name,url,username,password\nExample,https://example.com,ada,hunter2"

    @Test
    fun `a damaged file under a tile is refused rather than handed to another brand`() {
        assertNull(
            "the Bitwarden tile must not import a truncated export as something else",
            ImportWizardViewModel.importerFor(ImportSource.BITWARDEN, truncatedBitwarden.toByteArray()),
        )
    }

    @Test
    fun `a tile never reaches another vendor's importer`() {
        ImportSource.entries
            .filter { it != ImportSource.CSV }
            .forEach { source ->
                val claimants = ImportWizardViewModel.claimantsFor(source)
                assertTrue(
                    "$source offers more than its own importer plus the generic CSV reader: $claimants",
                    claimants.size <= 2,
                )
                assertSame("$source must keep the generic CSV fallback last", CsvImporter, claimants.last())
            }
    }

    /** The one tile where "I don't know what this is" is what the user said. */
    @Test
    fun `the generic tile still sniffs every format`() {
        assertSame(
            KeeperImporter,
            ImportWizardViewModel.claimantsFor(ImportSource.CSV).first { it == KeeperImporter },
        )
        assertEquals(
            ImportWizardViewModel.ALL_IMPORTERS,
            ImportWizardViewModel.claimantsFor(ImportSource.CSV),
        )
    }

    /** The control: the paths that worked keep working, tile by tile. */
    @Test
    fun `a tile still reads its own export and a plain CSV`() {
        assertSame(
            BitwardenJsonImporter,
            ImportWizardViewModel.importerFor(ImportSource.BITWARDEN, bitwardenJson.toByteArray()),
        )
        assertSame(
            CsvImporter,
            ImportWizardViewModel.importerFor(ImportSource.DASHLANE, plainCsv.toByteArray()),
        )
        assertSame(
            CsvImporter,
            ImportWizardViewModel.importerFor(ImportSource.CHROME, plainCsv.toByteArray()),
        )
    }
}
