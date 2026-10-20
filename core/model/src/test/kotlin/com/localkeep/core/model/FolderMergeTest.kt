package com.localkeep.core.model

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Rules for resolving a folder rename during a merge.
 *
 * The null cases carry the risk here, not the ordinary comparison: every folder written before
 * schema 2 has a null `updatedAt`, so an undated folder is not an edge case — it is what an entire
 * generation of vaults and exports looks like.
 */
class FolderMergeTest {

    private val id = UUID.randomUUID()
    private val earlier = Instant.parse("2026-01-01T00:00:00Z")
    private val later = Instant.parse("2026-06-01T00:00:00Z")

    private fun folder(name: String, at: Instant?) = Folder(id, name, updatedAt = at)

    @Test
    fun `a more recent rename wins`() {
        assertTrue(ItemMerge.isNewerFolder(folder("Work", later), folder("Job", earlier)))
    }

    @Test
    fun `an older rename loses`() {
        assertFalse(ItemMerge.isNewerFolder(folder("Job", earlier), folder("Work", later)))
    }

    @Test
    fun `an identical timestamp does not churn`() {
        assertFalse(ItemMerge.isNewerFolder(folder("Job", later), folder("Work", later)))
    }

    @Test
    fun `an undated incoming folder never overwrites a dated one`() {
        // The dangerous direction: a pre-schema-2 export is arbitrarily old, and treating its
        // missing timestamp as "now" would let it silently undo a rename the user just made.
        assertFalse(ItemMerge.isNewerFolder(folder("Stale", null), folder("Work", later)))
    }

    @Test
    fun `a dated incoming folder replaces an undated one`() {
        // A known time beats an unknown one — the vault copy predates the feature, the import
        // demonstrably does not.
        assertTrue(ItemMerge.isNewerFolder(folder("Work", later), folder("Old", null)))
    }

    @Test
    fun `two undated folders keep the existing name, exactly as union-of-id did`() {
        // Guarantees merging two pre-schema-2 vaults behaves the way it always has.
        assertFalse(ItemMerge.isNewerFolder(folder("Imported", null), folder("Existing", null)))
    }

    @Test
    fun `a folder from a schema-1 vault decodes with a null timestamp, not a fabricated one`() {
        // Round-trips a Folder written the way schema 1 wrote it — field 4 simply absent — and
        // asserts the decoder does not invent a value. This is what makes the null rules above the
        // real-world case rather than a defensive branch.
        val schema1Folder = Folder(id, "Legacy")
        val vault = Vault(
            schemaVersion = 1,
            folders = listOf(schema1Folder),
            createdAt = earlier,
            modifiedAt = earlier,
        )
        val decoded = VaultSerializer.decode(VaultSerializer.encode(vault))
        assertEquals(null, decoded.folders.single().updatedAt)
        assertEquals("Legacy", decoded.folders.single().name)
    }
}
