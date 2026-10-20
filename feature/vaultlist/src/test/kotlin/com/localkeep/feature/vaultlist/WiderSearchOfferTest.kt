package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Note
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Searching from a type chip used to dead-end: on the 2FA tab, a query matching a login showed
 * "No matching items" and stopped, while the item sat in the vault a chip away. Device-confirmed
 * 2026-08-10 — query "Recov" on the 2FA tab, against a vault holding "Recovery codes".
 *
 * The fix deliberately does *not* let the query escape the chip. A chip that stays lit while the
 * list shows items it excludes is a control lying about what is on screen, and the design mock
 * filters by type before it ever looks at the query. Instead the empty state offers the way out
 * explicitly, and these pin when that offer may appear.
 */
class WiderSearchOfferTest {

    private fun state(
        query: String = "recov",
        items: List<VaultItem> = emptyList(),
        outside: Int = 1,
    ) = VaultListUiState(items = items, searchQuery = query, matchesOutsideFilter = outside)

    private fun item(title: String) = VaultItem(
        id = UUID.randomUUID(),
        title = title,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = Note("x"),
    )

    @Test
    fun `an empty scoped search with matches elsewhere offers to widen`() {
        assertTrue(state().offersWiderSearch())
    }

    /** Nothing to widen: the chips are hiding nothing that matches these words. */
    @Test
    fun `no matches anywhere is still an honest dead end`() {
        assertFalse(state(outside = 0).offersWiderSearch())
    }

    /**
     * The offer is for an *empty* screen. With rows showing, the user is already looking at results
     * and a button re-running their search elsewhere would be noise, not a way out.
     */
    @Test
    fun `a list with rows on it never offers to widen`() {
        assertFalse(state(items = listOf(item("Recovery codes"))).offersWiderSearch())
    }

    /** A bare chip with no query has no words to re-run; "Clear filters" is the right offer there. */
    @Test
    fun `filtering without searching does not offer to widen`() {
        assertFalse(state(query = "").offersWiderSearch())
        assertFalse(state(query = "   ").offersWiderSearch())
    }
}
