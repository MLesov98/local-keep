package com.localkeep.feature.vaultlist

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The active-filter strip names exactly the narrowings the user cannot otherwise see.
 *
 * The strip replaced the per-type chip row on 2026-09-02. Its contract is small and worth pinning:
 * nothing at rest, one token per axis that is on, type before Favourites so the order never
 * shuffles under a finger — and never the search or the browsed folder, which have the search field
 * and the breadcrumb to name them. Same shape as [ClearTransientFiltersTest]: a pure function of
 * state, so no repository is needed.
 */
class ActiveFilterTokensTest {

    @Test
    fun `an unfiltered list shows no tokens`() {
        assertEquals(emptyList(), VaultListUiState().activeFilters())
    }

    @Test
    fun `favourites alone is one token`() {
        assertEquals(
            listOf(ActiveFilter.Favourites),
            VaultListUiState(showFavoritesOnly = true).activeFilters(),
        )
    }

    @Test
    fun `a type alone is one token`() {
        assertEquals(
            listOf(ActiveFilter.Type(ItemTypeFilter.NOTE)),
            VaultListUiState(typeFilter = ItemTypeFilter.NOTE).activeFilters(),
        )
    }

    @Test
    fun `both axes are two tokens, type first`() {
        // The state that used to read as "No matching items" with nothing on screen saying two
        // filters were on. Now both are named, and in a fixed order.
        val both = VaultListUiState(showFavoritesOnly = true, typeFilter = ItemTypeFilter.LOGIN)
        assertEquals(
            listOf(ActiveFilter.Type(ItemTypeFilter.LOGIN), ActiveFilter.Favourites),
            both.activeFilters(),
        )
    }

    @Test
    fun `a tag is one token, carrying the tag as authored`() {
        // User data, not a resource: the token shows exactly the string on the item.
        assertEquals(
            listOf(ActiveFilter.Tag("Work stuff")),
            VaultListUiState(tagFilter = "Work stuff").activeFilters(),
        )
    }

    @Test
    fun `invisible-only is one token, after every other axis`() {
        val all = VaultListUiState(
            typeFilter = ItemTypeFilter.LOGIN,
            showFavoritesOnly = true,
            tagFilter = "work",
            showHiddenOnly = true,
            revealSessionActive = true,
        )
        assertEquals(
            listOf(
                ActiveFilter.Type(ItemTypeFilter.LOGIN),
                ActiveFilter.Favourites,
                ActiveFilter.Tag("work"),
                ActiveFilter.Hidden,
            ),
            all.activeFilters(),
        )
    }

    @Test
    fun `search and the browsed folder are not tokens`() {
        // Each has its own permanent surface — the field and the breadcrumb — and repeating the
        // folder as a filter was tried in the count line and read as calling navigation a filter.
        val narrowed = VaultListUiState(searchQuery = "zzz", folderStack = listOf(UUID.randomUUID()))
        assertEquals(emptyList(), narrowed.activeFilters())
    }
}
