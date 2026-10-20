package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Note
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Device-confirmed 2026-08-10: with one item selected, switching the type filter chip to a
 * category the selected item does not match silently exited multi-select entirely — the selection
 * was gone with no prompt, and returning to "All" did not restore it. With several items selected
 * across categories that discarded real work with no notice.
 *
 * Decided fix: the selection survives every filter/search/sort change. It is pruned only against
 * ids that no longer exist anywhere in the vault (see [VaultListViewModel.refresh]), never against
 * what the current filter happens to show. These pin the pure state transition — see
 * [FolderFilterStateTest] and [WiderSearchOfferTest] for why the ViewModel itself, which needs a
 * repository and an unlocked vault, is not exercised here.
 */
class SelectionSurvivesFilterTest {

    private fun item(title: String) = VaultItem(
        id = UUID.randomUUID(),
        title = title,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = Note("x"),
    )

    // --- withSelectionPrunedTo: what refresh() runs on every filter/search/sort pass ------------

    @Test
    fun `a selection hidden by a filter change survives, it is not cleared`() {
        val visible = item("Login A")
        val hidden = item("2FA code")
        // Both still exist in the vault (the pruning set), but the current filter only shows `visible`.
        val state = VaultListUiState(items = listOf(visible), selectedIds = setOf(visible.id, hidden.id))
        val pruned = state.withSelectionPrunedTo(existingIds = setOf(visible.id, hidden.id))

        // FAILS WITHOUT THE FIX: the old code intersected against `items` (only what's on screen),
        // which is exactly `setOf(visible.id)` here — `hidden.id` would be dropped and `selecting`
        // would still read true only because `visible` stayed selected. Assert the hidden id by name.
        assertEquals(setOf(visible.id, hidden.id), pruned.selectedIds)
        assertTrue(pruned.selecting)
    }

    @Test
    fun `a filter hiding every selected item still leaves the selection alive`() {
        val onScreen = item("Note")
        val allHidden = item("2FA code")
        val state = VaultListUiState(items = listOf(onScreen), selectedIds = setOf(allHidden.id))
        // allHidden still exists in the vault, just not under this filter.
        val pruned = state.withSelectionPrunedTo(existingIds = setOf(onScreen.id, allHidden.id))

        assertTrue(pruned.selecting)
        assertEquals(setOf(allHidden.id), pruned.selectedIds)
    }

    @Test
    fun `an id genuinely gone from the vault is dropped, not just hidden`() {
        val gone = item("Deleted individually")
        val state = VaultListUiState(items = emptyList(), selectedIds = setOf(gone.id))
        // `gone` is NOT in existingIds — it was trashed, unlike a merely-filtered row.
        val pruned = state.withSelectionPrunedTo(existingIds = emptySet())

        assertEquals(emptySet(), pruned.selectedIds)
        assertTrue(!pruned.selecting)
    }

    // --- hiddenSelectedCount: the header's "N hidden by filter" ------------------------------------

    @Test
    fun `hiddenSelectedCount counts selected ids the current filter is not showing`() {
        val shown = item("Login")
        val hiddenOne = item("2FA one")
        val hiddenTwo = item("2FA two")
        val state = VaultListUiState(
            items = listOf(shown),
            selectedIds = setOf(shown.id, hiddenOne.id, hiddenTwo.id),
        )
        assertEquals(2, state.hiddenSelectedCount)
    }

    @Test
    fun `hiddenSelectedCount is zero when every selected row is on screen`() {
        val a = item("A")
        val b = item("B")
        val state = VaultListUiState(items = listOf(a, b), selectedIds = setOf(a.id, b.id))
        assertEquals(0, state.hiddenSelectedCount)
    }

    // --- bulk actions target the whole selection, hidden rows included -----------------------------

    @Test
    fun `the whole selection, hidden ids included, is what a bulk action would read`() {
        // Bulk actions (bulkMoveToFolder, bulkAddTag, bulkTrash) read `selectedIds` straight off
        // state, unfiltered by `items` — see VaultListViewModel.bulkEdit. This proves the set that
        // survives a filter change is the full one they would act on, hidden ids included, which is
        // also what the header's count and the bulk-action button labels (`state.selectedIds.size`)
        // read from.
        val shown = item("Login")
        val hidden = item("2FA code")
        val afterFilterChange = VaultListUiState(
            items = listOf(shown),
            selectedIds = setOf(shown.id, hidden.id),
        ).withSelectionPrunedTo(existingIds = setOf(shown.id, hidden.id))

        assertEquals(2, afterFilterChange.selectedIds.size)
        assertTrue(hidden.id in afterFilterChange.selectedIds)
    }

    // --- select-all-visible must not discard a hidden selection -----------------------------------

    @Test
    fun `selecting all visible adds to, never replaces, a selection with hidden members`() {
        val visibleA = item("Login A")
        val visibleB = item("Login B")
        val hidden = item("2FA code")
        val state = VaultListUiState(items = listOf(visibleA, visibleB), selectedIds = setOf(hidden.id))

        // FAILS WITHOUT THE FIX: the old selectAllVisible replaced selectedIds outright with
        // `items.map { it.id }.toSet()`, dropping `hidden.id` on the floor.
        val toggled = state.withVisibleSelectionToggled()
        assertEquals(setOf(hidden.id, visibleA.id, visibleB.id), toggled.selectedIds)
    }

    @Test
    fun `deselecting all visible only removes what is on screen, hidden selection stays`() {
        val visibleA = item("Login A")
        val hidden = item("2FA code")
        val state = VaultListUiState(items = listOf(visibleA), selectedIds = setOf(visibleA.id, hidden.id))

        val toggled = state.withVisibleSelectionToggled()
        assertEquals(setOf(hidden.id), toggled.selectedIds)
    }
}
