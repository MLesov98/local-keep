package com.localkeep.core.model

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The predicate behind "show only the folders this item type actually lives in".
 *
 * The cases below are the principal's own example, written out: folders `A > B > C > D` with the
 * matching item somewhere inside, and the question of which folders survive. Both surfaces that ask
 * it — the vault list's folder strip and the item editor's picker — share this function precisely
 * so they cannot answer it two different ways.
 */
class FolderRelevanceTest {

    private val a = Folder(UUID.randomUUID(), "A")
    private val b = Folder(UUID.randomUUID(), "B", parentId = a.id)
    private val c = Folder(UUID.randomUUID(), "C", parentId = b.id)
    private val d = Folder(UUID.randomUUID(), "D", parentId = c.id)
    private val loose = Folder(UUID.randomUUID(), "Loose")
    private val tree = listOf(a, b, c, d, loose)

    private fun item(folder: Folder?, marker: String) = VaultItem(
        id = UUID.randomUUID(),
        title = marker,
        folderId = folder?.id,
        content = Note(body = marker),
        createdAt = EPOCH,
        updatedAt = EPOCH,
    )

    private companion object {
        private val EPOCH = java.time.Instant.EPOCH
    }

    private fun relevant(items: List<VaultItem>, marker: String) =
        FolderRelevance.relevantFolderIds(items, tree) { it.title == marker }

    @Test
    fun `an item in C makes A, B and C relevant and leaves D out`() {
        val ids = relevant(listOf(item(c, "match")), "match")
        assertEquals(setOf(a.id, b.id, c.id), ids)
    }

    @Test
    fun `the same item one level shallower drops C as well`() {
        val ids = relevant(listOf(item(b, "match")), "match")
        assertEquals(setOf(a.id, b.id), ids)
    }

    @Test
    fun `a folder holding only non-matching items is not relevant`() {
        val items = listOf(item(c, "match"), item(loose, "other"))
        assertEquals(setOf(a.id, b.id, c.id), relevant(items, "match"))
    }

    @Test
    fun `no matching item anywhere is an empty set, not every folder`() {
        // Empty is a real answer and is not the same as "no filter is on" — the caller distinguishes
        // the two with null. The vault list showed all four folders under a filter matching nothing,
        // which is the bug this separation exists to stop.
        assertEquals(emptySet(), relevant(listOf(item(c, "other")), "match"))
    }

    @Test
    fun `an unfiled matching item makes no folder relevant`() {
        assertEquals(emptySet(), relevant(listOf(item(null, "match")), "match"))
    }

    @Test
    fun `two matches sharing an ancestor chain still walk it once and agree`() {
        val items = listOf(item(d, "match"), item(c, "match"))
        // The walk short-circuits on the first id already in the set; the result must not depend on
        // which item was visited first.
        assertEquals(setOf(a.id, b.id, c.id, d.id), relevant(items, "match"))
        assertEquals(relevant(items.reversed(), "match"), relevant(items, "match"))
    }
}
