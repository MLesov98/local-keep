package com.localkeep.feature.vaultlist

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The count that decides whether a dead-end search offers a way out.
 *
 * `WiderSearchOfferTest` covers [VaultListUiState.offersWiderSearch] with a hand-supplied number,
 * which is why the defect this pins survived it: the number itself was the wrong one, and no test
 * computed it. Found 2026-08-25.
 */
class MatchesOutsideNarrowingTest {

    @Test
    fun `a type chip hides matches at the root just as a folder does`() {
        // Root, 2FA chip up, searching for a note. The note matched the query and the chip removed
        // it, so there is something to widen to — and the old gate returned 0 because there was no
        // folder open, leaving the user on "nothing matches" with the item one chip away.
        assertEquals(1, matchesOutsideNarrowing(searching = true, matchingCount = 1, scopedCount = 0))
    }

    @Test
    fun `nothing is offered when nothing is narrowed or nothing is searched`() {
        // The two controls. Without them, "always return matchingCount" would pass the test above
        // and offer to widen a search that is already as wide as it goes.
        assertEquals(0, matchesOutsideNarrowing(searching = true, matchingCount = 3, scopedCount = 3))
        assertEquals(0, matchesOutsideNarrowing(searching = false, matchingCount = 5, scopedCount = 0))
    }
}
