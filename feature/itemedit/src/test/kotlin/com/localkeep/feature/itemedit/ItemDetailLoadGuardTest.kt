package com.localkeep.feature.itemedit

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The guard that decides whether the detail screen is looking at the item it was asked for.
 *
 * This is deliberately a test of the *predicate* and not of the paint. `loadItem` has no suspension
 * point between raising and clearing `isLoading`, and `StateFlow` conflates emissions nobody was
 * scheduled to collect — so a Compose test written as "assert frame one, advance, assert frame two"
 * races straight through the state it exists to catch and passes against the broken code. The same
 * problem produced `MonogramMorphTest`, whose comment records two defects that were "measured on a
 * device… neither visible in a build log, a screenshot of either end state, or a unit test of the
 * composable". Assert the invariant; leave the frames to the device pass.
 */
class ItemDetailLoadGuardTest {

    private val requested = UUID.randomUUID()

    @Test
    fun `a freshly constructed state is never mistaken for the requested item`() {
        // The frame-one case, and the whole reason identity beats the flag. A new state carries a
        // random id and `isLoading = false`, so reading the flag alone said "loaded" and the screen
        // rendered its default — a blank title, resolved to "Untitled", whose monogram colour is
        // derived from that word rather than from the item the user tapped.
        val fresh = ItemEditUiState()

        assertFalse(fresh.isLoading, "the flag itself starts false — that is the defect")
        assertTrue(isShowingWrongItem(fresh, requested.toString()))
    }

    @Test
    fun `the previous item still counts as the wrong one`() {
        // Item-to-item navigation never passes through a fresh state: the id in hand is the last
        // item's, which is a real, loaded, entirely wrong identity.
        val previous = ItemEditUiState(itemId = UUID.randomUUID(), title = "Acme Mail")

        assertTrue(isShowingWrongItem(previous, requested.toString()))
    }

    @Test
    fun `the requested item passes once it is actually loaded`() {
        val loaded = ItemEditUiState(itemId = requested, title = "Acme Mail", isLoading = false)

        assertFalse(isShowingWrongItem(loaded, requested.toString()))
    }

    @Test
    fun `the flag still holds the screen while the lookup runs`() {
        // Identity replaces nothing — it covers frame one, and the flag covers the window after it,
        // which on a large vault is where most of the wait is.
        val mid = ItemEditUiState(itemId = requested, isLoading = true)

        assertTrue(isShowingWrongItem(mid, requested.toString()))
    }

    // ── "not yet" versus "not ever" ──────────────────────────────────────

    /**
     * The screen used to hold the same skeleton for both, forever. `loadItem`'s not-found branch
     * does not adopt the requested id, so [isShowingWrongItem] stays true for good and the screen
     * never reached the block that renders `state.error` — a dead end with no way back and no
     * statement of what happened.
     */
    @Test
    fun `an item the vault does not have is missing, not loading`() {
        val looked = ItemEditUiState()

        assertTrue(isShowingWrongItem(looked, requested.toString()), "still held")
        assertTrue(isMissingItem(looked, requested.toString(), lookupRan = true))
    }

    @Test
    fun `before the lookup runs the screen is loading, not missing`() {
        // The whole distinction. Identical state, opposite answer, decided only by whether anything
        // has asked the vault yet.
        val fresh = ItemEditUiState()

        assertFalse(isMissingItem(fresh, requested.toString(), lookupRan = false))
    }

    @Test
    fun `an item that was found is not missing`() {
        val loaded = ItemEditUiState(itemId = requested, title = "Acme Mail")

        assertFalse(isMissingItem(loaded, requested.toString(), lookupRan = true))
    }

    @Test
    fun `a reload in flight is not missing`() {
        // `clearPasswordHistory` and the onSaved collector both re-read an item that is present.
        // Reading that window as "gone" would replace a loaded screen with an error mid-refresh.
        val reloading = ItemEditUiState(itemId = UUID.randomUUID(), isLoading = true)

        assertFalse(isMissingItem(reloading, requested.toString(), lookupRan = true))
    }

    @Test
    fun `a lookup for some other item says nothing about this one`() {
        // `lookupRan` is per requested id, not a global flag — item-to-item navigation reuses
        // nothing, but a stale true here would flash "gone" on a perfectly good item.
        val previous = ItemEditUiState(itemId = UUID.randomUUID(), title = "Acme Mail")

        assertFalse(isMissingItem(previous, requested.toString(), lookupRan = false))
    }

    @Test
    fun `a malformed route id never resolves to a loaded item`() {
        // `loadItem` treats an unparseable id as not found, so the screen must not decide it is
        // looking at something. A UUID's toString can never equal this.
        val loaded = ItemEditUiState(itemId = requested)

        assertTrue(isShowingWrongItem(loaded, "not-a-uuid"))
    }
}
