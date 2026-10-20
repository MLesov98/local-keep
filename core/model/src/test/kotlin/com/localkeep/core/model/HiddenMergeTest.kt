package com.localkeep.core.model

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The hidden (lease, stamp) pair merges on its own stamp, exactly as a folder's rename, move and
 * trash each merge on theirs: a null stamp never wins, a known time beats an unknown one, a tie keeps
 * the existing side. Hide and unhide are both real edits, so the newer stamp decides regardless of
 * which direction it points.
 */
class HiddenMergeTest {
    private val t0 = Instant.ofEpochMilli(1_000_000L)
    private val t1 = Instant.ofEpochMilli(2_000_000L)
    private val t2 = Instant.ofEpochMilli(3_000_000L)
    private val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val folderId = UUID.fromString("00000000-0000-0000-0000-0000000000f0")

    private fun item(until: Instant?, stamp: Instant?, updatedAt: Instant = t0): VaultItem = VaultItem(
        id = id,
        createdAt = t0,
        updatedAt = updatedAt,
        content = Note("n"),
        hiddenUntil = until,
        hiddenUpdatedAt = stamp,
    )

    private fun folder(until: Instant?, stamp: Instant?): Folder =
        Folder(folderId, "F", hiddenUntil = until, hiddenUpdatedAt = stamp)

    @Test
    fun `a newer hide wins over an older visible state`() {
        val existing = item(until = null, stamp = t0)
        val incoming = item(until = t2, stamp = t1)
        val (until, stamp) = ItemMerge.mergeHiddenPair(existing = existing, incoming = incoming)
        assertEquals(t2, until)
        assertEquals(t1, stamp)
    }

    @Test
    fun `a newer unhide wins over an older hide`() {
        val existing = item(until = t2, stamp = t0)
        val incoming = item(until = null, stamp = t1)
        val (until, stamp) = ItemMerge.mergeHiddenPair(existing = existing, incoming = incoming)
        assertNull(until)
        assertEquals(t1, stamp)
    }

    @Test
    fun `a null incoming stamp never wins`() {
        val existing = item(until = t2, stamp = t1)
        val incoming = item(until = null, stamp = null)
        val (until, stamp) = ItemMerge.mergeHiddenPair(existing = existing, incoming = incoming)
        assertEquals(t2, until)
        assertEquals(t1, stamp)
    }

    @Test
    fun `a known incoming stamp beats an unknown existing one`() {
        val existing = item(until = null, stamp = null)
        val incoming = item(until = t2, stamp = t1)
        val (until, stamp) = ItemMerge.mergeHiddenPair(existing = existing, incoming = incoming)
        assertEquals(t2, until)
        assertEquals(t1, stamp)
    }

    @Test
    fun `an equal stamp keeps the existing side`() {
        val existing = item(until = t2, stamp = t1)
        val incoming = item(until = null, stamp = t1)
        val (until, stamp) = ItemMerge.mergeHiddenPair(existing = existing, incoming = incoming)
        assertEquals(t2, until)
        assertEquals(t1, stamp)
    }

    @Test
    fun `a folder merge orders hidden state independently of rename and trash`() {
        // The name is older on the incoming side, the hide is newer: mergeFolder must take the name
        // from existing and the hide from incoming, the same field-by-field independence the three
        // prior stamps already have.
        val existing = folder(until = null, stamp = t0).copy(name = "Kept", updatedAt = t2)
        val incoming = folder(until = t2, stamp = t1).copy(name = "Stale", updatedAt = t0)

        val merged = FolderTree.mergeFolder(incoming = incoming, existing = existing)

        assertEquals("Kept", merged.name)
        assertEquals(t2, merged.hiddenUntil)
        assertEquals(t1, merged.hiddenUpdatedAt)
    }

    @Test
    fun `a folder's null incoming hide stamp never overwrites an existing hide`() {
        val existing = folder(until = t2, stamp = t1)
        val incoming = folder(until = null, stamp = null)
        val merged = FolderTree.mergeFolder(incoming = incoming, existing = existing)
        assertEquals(t2, merged.hiddenUntil)
        assertEquals(t1, merged.hiddenUpdatedAt)
    }

    @Test
    fun `preserveStructure keeps the vault item's hide when the import carries none`() {
        // An importer builds items with null hidden fields (winner). The existing vault item (loser)
        // may be hidden. Letting the winner's null overwrite would silently reveal a hidden item on
        // every re-import.
        val importItem = item(until = null, stamp = null).copy(title = "Imported")
        val vaultItem = item(until = t2, stamp = t1)
        val merged = ItemMerge.preserveStructure(winner = importItem, loser = vaultItem)
        assertEquals(t2, merged.hiddenUntil)
        assertEquals(t1, merged.hiddenUpdatedAt)
    }
}
