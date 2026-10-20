package com.localkeep.feature.importwizard

import com.localkeep.core.import.ImportMerger
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for a merge-count defect: in [ImportMerger.Mode.ADD_ALL] the Done step's headline
 * count must be every selected row, not just the rows the preview called NEW.
 *
 * [ImportMerger.apply] appends every selected item verbatim in ADD_ALL mode — it does not consult
 * [ImportMerger.Disposition] at all — so [importedCount] counting only NEW rows there understated
 * (or, if every row collided, zeroed) the actual vault gain. MERGE keeps the NEW-only count, because
 * only NEW rows are additive there; anything else updates an existing item or is discarded.
 */
class ImportedCountTest {

    private fun row(disposition: ImportMerger.Disposition) = PreviewRow(
        id = UUID.randomUUID(),
        title = "row",
        subtitle = "",
        disposition = disposition,
        collidesWith = null,
        selected = true,
        item = VaultItem(
            id = UUID.randomUUID(),
            title = "row",
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            content = Login(username = "u", password = "p"),
        ),
    )

    @Test
    fun `ADD_ALL counts every selected row, colliding or not`() {
        val rows = listOf(
            row(ImportMerger.Disposition.NEW),
            row(ImportMerger.Disposition.UPDATES_EXISTING),
            row(ImportMerger.Disposition.ALREADY_CURRENT),
        )

        assertEquals(3, importedCount(ImportMerger.Mode.ADD_ALL, rows))
    }

    @Test
    fun `ADD_ALL where every row collides still reports the true gain, not zero`() {
        // The worst case from the finding: re-importing the same export with ADD_ALL makes every
        // row non-NEW, so a NEW-only count would read "Imported 0 items" over a vault that just
        // doubled.
        val rows = listOf(
            row(ImportMerger.Disposition.ALREADY_CURRENT),
            row(ImportMerger.Disposition.ALREADY_CURRENT),
        )

        assertEquals(2, importedCount(ImportMerger.Mode.ADD_ALL, rows))
    }

    @Test
    fun `MERGE still counts only the NEW rows`() {
        val rows = listOf(
            row(ImportMerger.Disposition.NEW),
            row(ImportMerger.Disposition.UPDATES_EXISTING),
            row(ImportMerger.Disposition.ALREADY_CURRENT),
        )

        assertEquals(1, importedCount(ImportMerger.Mode.MERGE, rows))
    }
}
