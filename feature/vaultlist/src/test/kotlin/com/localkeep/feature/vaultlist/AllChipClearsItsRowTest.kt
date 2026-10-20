package com.localkeep.feature.vaultlist

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "All" chip owns both axes in its row.
 *
 * Found on device 2026-08-10: with Favourites on and no favourites in the vault, the list showed
 * "No matching items" while the chip labelled "All" rendered as selected — because selection tracked
 * the type axis only, and the type axis was already at its off position. Tapping it three times
 * changed nothing; only the empty state's "Clear filters" recovered.
 *
 * Same shape as [ClearTransientFiltersTest]: the ViewModel needs a repository and an unlocked vault,
 * but the transition is a pure function of state, so that is what gets pinned.
 *
 * The chip itself went on 2026-09-02, when the chip row became the filter sheet and the
 * active-filter strip. The transition survives as the sheet's "Clear all" — same function,
 * same two axes plus the browse path — so every assertion here still describes a live control.
 */
class AllChipClearsItsRowTest {

    @Test
    fun `All is not selected while favourites narrows the list`() {
        // The reported state exactly: type axis already off, favourites on. The old predicate was
        // `typeFilter == null`, which is true here — so the chip lit while the list stayed narrowed.
        val favouritesOnly = VaultListUiState(showFavoritesOnly = true)
        assertFalse(favouritesOnly.showsEveryType())
    }

    @Test
    fun `All is selected on a list with nothing applied`() {
        assertTrue(VaultListUiState().showsEveryType())
    }

    @Test
    fun `tapping All drops favourites as well as the type`() {
        val narrowed = VaultListUiState(showFavoritesOnly = true, typeFilter = ItemTypeFilter.NOTE)
        val shown = narrowed.withEveryTypeShown()
        assertFalse(shown.showFavoritesOnly)
        assertNull(shown.typeFilter)
        assertTrue(shown.showsEveryType())
    }

    @Test
    fun `tapping All also resets the browse path to root`() {
        // Reversed 2026-08-21: browsing a folder while filtered to a type and tapping All used to
        // clear only the type/Favourites axes, leaving the user inside the folder with a chip that
        // now claimed to show "everything". "All" means everything from the top, so the folder path
        // goes with the other two axes in one state change — see [VaultListViewModel.showEveryType]
        // for why the click guard checks the folder path too, not just [showsEveryType].
        val folder = UUID.randomUUID()
        val narrowed = VaultListUiState(showFavoritesOnly = true, folderStack = listOf(folder))
        assertEquals(emptyList(), narrowed.withEveryTypeShown().folderStack)
    }

    @Test
    fun `search is left alone too`() {
        // Same reason: the search field is visible, holds its own text, and has its own clear
        // affordance. A chip that emptied it would be undoing something the user can see they typed.
        val narrowed = VaultListUiState(showFavoritesOnly = true, searchQuery = "zzz")
        assertEquals("zzz", narrowed.withEveryTypeShown().searchQuery)
    }
}
