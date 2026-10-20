package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Note
import com.localkeep.core.model.VaultItem
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Finding 3-2 (2026-09-07 sweep, verdicts/3-2.json): a selection made during a reveal session
 * survived the conceal that ends it, because the only prune (`withSelectionPrunedTo`) checks
 * against every id still in the vault — concealed or not — and never against concealment itself.
 * The header then counted and announced the concealed id, and [VaultListViewModel.bulkEdit] —
 * which reads `selectedIds` straight off state — would still reach it.
 *
 * `withConcealedPruned` is the fix: [VaultListUiState.items] already carries a revealed row while
 * the session is open (`VaultVisibility.project` with `conceal = false`), so
 * `concealedItemIds - items` is empty during a reveal and becomes exactly the concealed set once
 * `items` drops those rows. These pin the pure state transition the same way
 * [SelectionSurvivesFilterTest] pins `withSelectionPrunedTo` — no repository or coroutine needed.
 */
class ConcealedSelectionPrunedTest {

    private fun item(title: String) = VaultItem(
        id = UUID.randomUUID(),
        title = title,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = Note("x"),
    )

    @Test
    fun `a selection made during an open reveal session is untouched`() {
        val visible = item("Login")
        val revealedHidden = item("2FA code")
        // Still concealed at the vault level, but the reveal session keeps it in `items` (conceal = false).
        val state = VaultListUiState(
            items = listOf(visible, revealedHidden),
            selectedIds = setOf(visible.id, revealedHidden.id),
        )

        val pruned = state.withConcealedPruned(concealedItemIds = setOf(revealedHidden.id))

        assertEquals(setOf(visible.id, revealedHidden.id), pruned.selectedIds)
    }

    @Test
    fun `a selection concealed by the reveal session ending is dropped, not just hidden`() {
        val visible = item("Login")
        val nowConcealed = item("2FA code")
        // The conceal transition has already run: `items` (the fresh projection) no longer carries
        // `nowConcealed`, but `concealedItemIds` still names it — same as VaultVisibility always does.
        val state = VaultListUiState(
            items = listOf(visible),
            selectedIds = setOf(visible.id, nowConcealed.id),
        )

        val pruned = state.withConcealedPruned(concealedItemIds = setOf(nowConcealed.id))

        // FAILS WITHOUT THE FIX: reverting the body of `withConcealedPruned` to `this` leaves
        // `nowConcealed.id` selected here, which is exactly what let the header announce a
        // concealed record and let bulkEdit still mutate it. What this does *not* pin is the
        // production call site — a hand-built state never reaches it, so deleting
        // `.withConcealedPruned(concealedItemIds)` from the projection scan would leave this green.
        // [theProjectionScanStillPrunesConcealedSelections] is what pins that.
        assertEquals(setOf(visible.id), pruned.selectedIds)
        assertTrue(nowConcealed.id !in pruned.selectedIds)
    }

    @Test
    fun `concealment prunes even a selection a filter is also hiding`() {
        // Both prunes applied, as the projection scan applies them. The order carries no meaning:
        // neither writes `items`, so `(selected intersect existing) - concealed` and
        // `(selected - concealed) intersect existing` are the same set. What is pinned here is that
        // concealment prunes a row a filter is also hiding — not a sequence.
        val onScreen = item("Login")
        val concealedAndFiltered = item("2FA code")
        val state = VaultListUiState(
            items = listOf(onScreen),
            selectedIds = setOf(onScreen.id, concealedAndFiltered.id),
        )

        val result = state
            .withSelectionPrunedTo(existingIds = setOf(onScreen.id, concealedAndFiltered.id))
            .withConcealedPruned(concealedItemIds = setOf(concealedAndFiltered.id))

        assertEquals(setOf(onScreen.id), result.selectedIds)
        assertEquals(0, result.hiddenSelectedCount)
    }

    /**
     * And the scan that republishes the list still calls it. The three tests above are facts about
     * a pure function; what the defect actually was is that nothing subtracted the concealed set
     * on the way to `_state`, and a state built by hand cannot reach that seam.
     *
     * Read from the source because `VaultListViewModel` needs a `VaultRepository` and an unlocked
     * vault to construct — the same reason [SelectionSurvivesFilterTest] does not drive it either,
     * and the same technique [TrashNoMatchesPanelTest] uses to prove a panel is on the route.
     *
     * Comments are stripped first: the scan body explains the exception in prose that names
     * `withConcealedPruned`, so a bare search for the name survives deleting the call.
     */
    @Test
    fun theProjectionScanStillPrunesConcealedSelections() {
        val source =
            File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultListViewModel.kt").readText()
        val scan = codeOnly(source)
            .substringAfter("private fun scanAndPublish()")
            .substringBefore("private data class Scan(")

        // Control: the region located. Held before the call site existed, so a rename that moves
        // the publish out of `scanAndPublish` fails here rather than passing on an empty string.
        assertTrue(
            ".withSelectionPrunedTo(existing)" in scan,
            "the region between `private fun scanAndPublish()` and `private data class Scan(` " +
                "no longer contains the selection prune, so this scan is reading the wrong code",
        )
        assertTrue(
            ".withConcealedPruned(concealedItemIds)" in scan,
            "the scan publishes a selection it never pruned against concealment, which is " +
                "finding 3-2: the header counts a concealed record and bulkEdit still reaches it",
        )
    }

    /** Comment bodies name the call; only the call itself counts. */
    private fun codeOnly(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
}
