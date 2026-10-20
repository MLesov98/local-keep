package com.localkeep.feature.vaultlist


import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The count in the Empty-trash confirmation has to be the count that gets destroyed.
 *
 * `purgeTrash()` deletes everything in the trash; the list behind the dialog is search-filtered.
 * The dialog used to read its number off the filtered list, so searching for one item and tapping
 * Empty said "removes all 1 items" and then permanently removed all forty. Irreversible, and the
 * sentence the user agreed to was false.
 */
class EmptyTrashBodyTest {

    // Stand-ins for the resolved strings a composable would pass in (see
    // `vaultlist_trash_empty_removes_phrase` / `_hiding_clause` / `_no_undo_clause` in
    // res/values/strings.xml) — kept here as literals so this test needs no Android context.
    private fun removesPhrase(n: Int) =
        if (n == 1) "This permanently removes $n item" else "This permanently removes all $n items"
    private val hidingClause = ", including the ones your search is hiding"
    private val noUndoClause = ". There is no undo here, though an earlier snapshot or backup may still hold a copy."


    private fun body(totalCount: Int, shownCount: Int) = emptyTrashBody(
        totalCount = totalCount,
        shownCount = shownCount,
        removesPhrase = removesPhrase(totalCount),
        hidingClause = hidingClause,
        noUndoClause = noUndoClause,
    )

    // The two tests that used to sit here exercised `purgeItemCount(flatCount, folderRows)`, which
    // is gone. Summing the screen's rows was itself the defect: a folder trashed inside an
    // already-trashed parent gets no row, so its items were counted nowhere while the purge still
    // took them, and the confirmation read "removes all 0 items" over five. The claim they made —
    // that the number includes what is inside trashed folders, and that it stays conceal-filtered —
    // now belongs to `core/vault`'s `PurgeableTrashCountTest`, against the projection that counts
    // it with the purge's own predicate.

    @Test
    fun countsEverythingInTheTrashNotJustTheSearchMatches() {
        val body = body(totalCount = 40, shownCount = 1)

        assertTrue("removes all 40 items" in body, "said: $body")
        assertTrue("1 items" !in body, "the filtered count leaked into the sentence: $body")
    }

    @Test
    fun saysWhenASearchIsHidingSomeOfWhatWillGo() {
        assertTrue("hiding" in body(totalCount = 40, shownCount = 1))
    }

    /** No search running: the extra clause would be noise, and slightly ominous noise at that. */
    @Test
    fun staysQuietAboutTheSearchWhenNothingIsFiltered() {
        val result = body(totalCount = 40, shownCount = 40)

        assertTrue("hiding" !in result, "said: $result")
        assertEquals(
            "This permanently removes all 40 items. There is no undo here, though an earlier " +
                "snapshot or backup may still hold a copy.",
            result,
        )
    }

    /**
     * The dialog used to end "no copy anywhere else — the vault only exists on this device", and
     * this test asserted it, which is how a false claim survives a sweep: it stops looking like an
     * oversight and starts looking like a requirement.
     *
     * It was false in both directions. A rolling snapshot taken before the purge still holds the
     * items, and `vault.enc` reaches Android Auto Backup unless the user turned cloud backup off —
     * so the sentence overstated the destruction to anyone relying on it and understated the
     * recovery to anyone who wanted the item back.
     */
    @Test
    fun doesNotClaimThereIsNoCopyAnywhereElse() {
        val result = body(totalCount = 40, shownCount = 40)

        assertTrue("no copy anywhere else" !in result, "said: $result")
        assertTrue("only exists on this device" !in result, "said: $result")
    }
}
