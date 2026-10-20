package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Folder
import com.localkeep.core.model.FolderTree
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The new-folder screen opens with a parent the list below it actually shows.
 *
 * It was seeded from the folder being browsed unconditionally. Browsing a folder on the deepest
 * level seeded a row the picker does not list (a child of it would be a ninth level), so the screen
 * opened with nothing marked and the create went to — and was refused under — a destination the user
 * could not see.
 */
class NewFolderParentSeedTest {

    private fun chain(length: Int): List<Folder> {
        val out = mutableListOf<Folder>()
        var parent: UUID? = null
        repeat(length) { level ->
            val f = Folder(id = UUID.randomUUID(), name = "L${level + 1}", parentId = parent)
            out += f
            parent = f.id
        }
        return out
    }

    @Test
    fun aFolderTheListOffersIsKept() {
        val tree = chain(3)
        assertEquals(tree[2].id, newFolderParentSeed(tree, tree[2].id))
    }

    @Test
    fun theDeepestFolderFallsBackToItsParent() {
        val tree = chain(FolderTree.MAX_DEPTH)
        val deepest = tree.last()
        assertEquals(
            tree[tree.size - 2].id,
            newFolderParentSeed(tree, deepest.id),
            "the deepest folder is not offered as a parent, so the nearest one that is must be marked",
        )
    }

    @Test
    fun noInitialParentStaysAtTheTopLevel() {
        assertNull(newFolderParentSeed(chain(2), null))
    }

    @Test
    fun anIdThatIsNoLongerInTheTreeFallsBackToTheTopLevel() {
        assertNull(newFolderParentSeed(chain(2), UUID.randomUUID()))
    }
}
