package com.localkeep.feature.vaultlist

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "show me everything" reset behind the drawer's "All items" (and the tab re-entry reset).
 *
 * The bug: "All items" navigates to HOME, which is a no-op when the list is already the plain HOME
 * destination, so a Favourites chip set on it survived the tap and the list stayed empty. The reset
 * that fixes it lives in the ViewModel, not the route — these pin the pure state transition it runs,
 * for the same reason as [FolderFilterStateTest]: the ViewModel needs a repository and an unlocked
 * vault, but the transition itself is a pure function of state.
 */
class ClearTransientFiltersTest {

    @Test
    fun `clearing drops favourites, the browse path and search together`() {
        // The exact reported state: Favourites active on the plain vault list. It also carries a
        // folder and a search here to prove none of the axes is left standing.
        val filtered = VaultListUiState(
            showFavoritesOnly = true,
            typeFilter = ItemTypeFilter.NOTE,
            tagFilter = "work",
            showHiddenOnly = true,
            revealSessionActive = true,
            searchQuery = "zzz",
            folderStack = listOf(UUID.randomUUID()),
        )
        val cleared = filtered.withTransientFiltersCleared(initialFilter = null)
        assertFalse(cleared.showFavoritesOnly)
        assertNull(cleared.typeFilter)
        assertNull(cleared.tagFilter)
        assertFalse(cleared.showHiddenOnly)
        assertEquals("", cleared.searchQuery)
        assertEquals(emptyList(), cleared.folderStack)
    }

    @Test
    fun `a tag alone, or invisible-only alone, counts as a narrowing worth resetting`() {
        // The two sheet-owned axes added 2026-09-02, each on its own, for the same reason as
        // favourites below: an axis this predicate cannot see is an axis the reset leaves standing.
        assertTrue(VaultListUiState(tagFilter = "work").hasTransientNarrowing(initialFilter = null))
        assertTrue(
            VaultListUiState(showHiddenOnly = true, revealSessionActive = true)
                .hasTransientNarrowing(initialFilter = null),
        )
    }

    @Test
    fun `clearing returns a per-type destination to its own filter, not to all items`() {
        // A per-type drawer destination ("Logins") is meant to stay on its type. A re-entry reset
        // there returns to that type, not to everything — clearing it would strand the user on a
        // "Logins" screen showing the whole vault.
        val filtered = VaultListUiState(
            typeFilter = ItemTypeFilter.LOGIN,
            showFavoritesOnly = true,
        )
        val cleared = filtered.withTransientFiltersCleared(initialFilter = ItemTypeFilter.LOGIN)
        assertEquals(ItemTypeFilter.LOGIN, cleared.typeFilter)
        assertFalse(cleared.showFavoritesOnly)
    }

    @Test
    fun `favourites alone counts as a narrowing worth resetting`() {
        // The precise no-op the drawer fix targets: on plain HOME the type is already null, so only
        // favourites is set. If this did not read as "narrowed", the reset would early-out and the
        // chip would survive — which is the original bug.
        val favouritesOnly = VaultListUiState(showFavoritesOnly = true)
        assertTrue(favouritesOnly.hasTransientNarrowing(initialFilter = null))
    }

    @Test
    fun `an already-unfiltered list is not considered narrowed`() {
        // Guards the reset from re-reading the vault for nothing when there is nothing to clear.
        assertFalse(VaultListUiState().hasTransientNarrowing(initialFilter = null))
    }

    @Test
    fun `a per-type destination on its own type is not considered narrowed`() {
        val onType = VaultListUiState(typeFilter = ItemTypeFilter.TOTP)
        assertFalse(onType.hasTransientNarrowing(initialFilter = ItemTypeFilter.TOTP))
    }
}
