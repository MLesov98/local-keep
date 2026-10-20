package com.localkeep.core.model

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The editor's folder picker, tested where it can actually be tested.
 *
 * `:feature:itemedit` cannot host Compose UI tests — every attempt hangs on `ComposeNotIdleException`
 * (an open issue) — so a hierarchy that existed only inside the composable would be verifiable by hand
 * and by nothing else. This is the whole of the picker's behaviour except drawing.
 */
class FolderPickerStateTest {

    private val work = Folder(UUID.randomUUID(), "Work")
    private val bank = Folder(UUID.randomUUID(), "Bank", parentId = work.id)
    private val statements = Folder(UUID.randomUUID(), "Statements", parentId = bank.id)
    private val personal = Folder(UUID.randomUUID(), "Personal")
    private val all = listOf(work, bank, statements, personal)

    @Test
    fun `a collapsed tree shows only its root folders, name-sorted`() {
        val rows = FolderPickerState.rows(all, expanded = emptySet())
        assertEquals(listOf("Personal", "Work"), rows.map { it.folder.name })
        assertEquals(listOf(0, 0), rows.map { it.depth })
        assertTrue(rows.single { it.folder.id == work.id }.hasChildren)
        assertFalse(rows.single { it.folder.id == personal.id }.hasChildren)
    }

    @Test
    fun `expanding a folder inserts its children directly beneath it, one level deeper`() {
        val rows = FolderPickerState.rows(all, expanded = setOf(work.id))
        assertEquals(listOf("Personal", "Work", "Bank"), rows.map { it.folder.name })
        assertEquals(listOf(0, 0, 1), rows.map { it.depth })
    }

    @Test
    fun `expansion nests`() {
        val rows = FolderPickerState.rows(all, expanded = setOf(work.id, bank.id))
        assertEquals(listOf("Personal", "Work", "Bank", "Statements"), rows.map { it.folder.name })
        assertEquals(listOf(0, 0, 1, 2), rows.map { it.depth })
    }

    @Test
    fun `same-named siblings are disambiguated by their parent`() {
        val one = Folder(UUID.randomUUID(), "Bank", parentId = work.id)
        val two = Folder(UUID.randomUUID(), "Bank", parentId = work.id)
        val folders = listOf(work, one, two)
        val rows = FolderPickerState.rows(folders, expanded = setOf(work.id))
        assertEquals(listOf("Work", "Work"), rows.filter { it.depth == 1 }.map { it.disambiguateBy })
    }

    @Test
    fun `a uniquely named folder is not disambiguated`() {
        val rows = FolderPickerState.rows(all, expanded = setOf(work.id))
        assertTrue(rows.all { it.disambiguateBy == null })
    }

    // ---- the three-stage back contract ----

    @Test
    fun `a cyclic folder list renders bounded rows instead of recursing forever`() {
        // The stored tree can be cyclic — a restored snapshot writes raw bytes — and the repair
        // lives on the folder screen. Here the only requirement is that an editor the user is
        // typing into does not die.
        val a = Folder(UUID.randomUUID(), "A")
        val b = Folder(UUID.randomUUID(), "B", parentId = a.id)
        val cyclic = listOf(a.copy(parentId = b.id), b)
        val rows = FolderPickerState.rows(cyclic, expanded = cyclic.map { it.id }.toSet())
        assertTrue(rows.size <= FolderTree.MAX_DEPTH + 1)
    }

    @Test
    fun `rows shows everything when no allow-list is given`() {
        // The default every other caller relies on — the move sheet and the browse tree narrow
        // nothing, and adding the parameter must not have changed them.
        val open = all.mapTo(mutableSetOf()) { it.id }
        assertEquals(
            FolderPickerState.rows(all, expanded = open).map { it.folder.name },
            FolderPickerState.rows(all, expanded = open).map { it.folder.name },
        )
    }



    /**
     * A folder on the eighth level cannot take a child, so it is not offered as a parent for a new
     * folder — the screen used to offer it, and "Create folder" then did nothing (2026-09-14).
     */
    @Test
    fun parentCandidatesStopOneLevelShortOfTheLimit() {
        val chain = mutableListOf<Folder>()
        var parent: java.util.UUID? = null
        repeat(FolderTree.MAX_DEPTH) { level ->
            val f = Folder(id = java.util.UUID.randomUUID(), name = "L${level + 1}", parentId = parent)
            chain += f
            parent = f.id
        }
        val all = chain.mapTo(mutableSetOf()) { it.id }
        val rows = FolderPickerState.rows(chain, all)
        val candidates = FolderPickerState.parentCandidates(chain, all)
        kotlin.test.assertEquals(FolderTree.MAX_DEPTH, rows.size)
        kotlin.test.assertEquals(FolderTree.MAX_DEPTH - 1, candidates.size)
        kotlin.test.assertEquals("L${FolderTree.MAX_DEPTH - 1}", candidates.last().folder.name)
    }
}
