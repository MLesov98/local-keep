package com.localkeep.feature.backup

import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for 1C-F1 (release sweep 2026-09-20): the `.vault` Merge path's entire result copy was
 * the two words "Import complete". That path has no preview, no item list and no per-item toggles —
 * only whole-file Merge / Replace / Cancel — so a user who merged a backup had no way to learn
 * whether it had brought anything in, changed anything, or done nothing at all. The merge itself was
 * always correct; it simply never said what it had done.
 */
class MergeCountsTest {

    private val early: Instant = Instant.parse("2026-09-01T12:00:00Z")
    private val late: Instant = Instant.parse("2026-09-20T12:00:00Z")

    private fun item(id: String, username: String, updatedAt: Instant) = VaultItem(
        id = UUID.fromString(id),
        title = "Account $id",
        createdAt = early,
        updatedAt = updatedAt,
        content = Login(username = username, password = "p"),
    )

    private val a = "00000000-0000-4000-8000-00000000000a"
    private val b = "00000000-0000-4000-8000-00000000000b"
    private val c = "00000000-0000-4000-8000-00000000000c"

    @Test
    fun `the three outcomes are counted apart`() {
        val existing = listOf(
            item(a, "mine", early),
            item(b, "mine-newer", late),
        )
        val imported = listOf(
            // a: the file has a newer copy -> updated
            item(a, "theirs", late),
            // b: this vault already has the newer copy -> nothing taken
            item(b, "theirs", early),
            // c: only in the file -> added
            item(c, "theirs", late),
        )

        val counts = mergeItems(existing = existing, imported = imported).counts

        assertEquals(MergeCounts(added = 1, updated = 1, unchanged = 1), counts)
    }

    @Test
    fun `the counts describe the imported file, not the vault`() {
        val existing = listOf(item(a, "mine", early), item(b, "mine", early))
        val imported = listOf(item(c, "theirs", late))

        val counts = mergeItems(existing = existing, imported = imported).counts

        assertEquals(1, counts.added + counts.updated + counts.unchanged, "one item came in the file")
        assertEquals(3, mergeItems(existing, imported).items.size, "and the vault now holds three")
    }

    @Test
    fun `merging a file that changes nothing says so rather than saying nothing`() {
        val existing = listOf(item(a, "mine", late))
        val imported = listOf(item(a, "theirs", early))

        val counts = mergeItems(existing = existing, imported = imported).counts

        assertEquals(MergeCounts(added = 0, updated = 0, unchanged = 1), counts)
    }

    /** Replace has nothing to count; merge does, and the copy has to differ accordingly. */
    @Test
    fun `the result copy carries the numbers for a merge and the plain line for a replace`() {
        assertEquals(
            BackupMessage.Res(R.string.backup_status_merge_complete, listOf(4, 2, 7)),
            importCompleteMessage(MergeCounts(added = 4, updated = 2, unchanged = 7)),
        )
        assertEquals(
            BackupMessage.Res(R.string.backup_status_import_complete),
            importCompleteMessage(null),
        )
    }
}
