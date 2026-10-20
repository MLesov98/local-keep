package com.localkeep.feature.health

import com.localkeep.core.health.SecurityDashboard
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A finished scan lands in the screen state without overwriting what the user did meanwhile.
 *
 * The scan publishes from `Dispatchers.Default`; the main thread writes the same flow when the user
 * opens a category or types in its search box. Publishing was `state.value = state.value.copy(...)`
 * with the drill-down list built from `state.value.selectedCategory` inside the argument list, so
 * the receiver was read, then the whole-vault lookup ran, then the stale copy was written back over
 * anything the main thread had written in between.
 *
 * The interleaving is forced rather than raced: the "main thread" write happens inside the lookup
 * the publish performs, which is exactly where the old code held a stale receiver.
 */
class HealthPublishScanTest {

    private val t: Instant = Instant.parse("2026-10-02T00:00:00Z")

    private fun item() = VaultItem(
        id = UUID.randomUUID(),
        createdAt = t,
        updatedAt = t,
        content = Login(username = "u", password = "p", passwordChangedAt = t),
    )

    private fun summary() = SecurityDashboard.Summary(
        weak = SecurityDashboard.CategoryResult(SecurityDashboard.Category.WEAK, emptySet()),
        reused = SecurityDashboard.CategoryResult(SecurityDashboard.Category.REUSED, emptySet()),
        old = SecurityDashboard.CategoryResult(SecurityDashboard.Category.OLD, emptySet()),
        breached = SecurityDashboard.CategoryResult(SecurityDashboard.Category.BREACHED, emptySet()),
        duplicate = SecurityDashboard.CategoryResult(SecurityDashboard.Category.DUPLICATE, emptySet()),
        breachFilterVintage = "",
        breachCorpusEntries = 0L,
        breachDataAvailable = false,
        scannedCount = 3,
        passwordsChecked = 3,
    )

    private fun openDialog(category: SecurityDashboard.Category, items: List<VaultItem>) =
        HealthDashboardUiState(
            loading = false,
            selectedCategory = category,
            categoryItems = items,
            scannedCount = 1,
            passwordsChecked = 1,
        )

    @Test
    fun `a query typed while the scan publishes survives it`() {
        val state = MutableStateFlow(openDialog(SecurityDashboard.Category.WEAK, listOf(item())))
        var typed = false

        state.publishScan(summary(), excluded = 0) {
            if (!typed) {
                typed = true
                state.value = state.value.copy(categoryQuery = "ban")
            }
            listOf(item())
        }

        assertEquals("ban", state.value.categoryQuery, "the main thread's write was overwritten by the publish")
        assertEquals(3, state.value.scannedCount, "and the scan's own result still landed")
    }

    @Test
    fun `a category opened while the scan publishes is not closed by it`() {
        val state = MutableStateFlow(openDialog(SecurityDashboard.Category.WEAK, listOf(item())))
        val fixed = item()
        var switched = false

        state.publishScan(summary(), excluded = 0) { category ->
            if (!switched) {
                switched = true
                state.value = state.value.copy(
                    selectedCategory = SecurityDashboard.Category.BREACHED,
                    categoryItems = emptyList(),
                )
            }
            // The list is derived from whichever category is open *when it is published*.
            if (category == SecurityDashboard.Category.BREACHED) listOf(fixed) else emptyList()
        }

        assertEquals(SecurityDashboard.Category.BREACHED, state.value.selectedCategory)
        assertEquals(listOf(fixed), state.value.categoryItems, "the list must belong to the category that is open")
    }

    /** The control: with nothing interleaved, an open drill-down is still rebuilt from the new scan. */
    @Test
    fun `an open category is rebuilt from the new summary`() {
        val state = MutableStateFlow(openDialog(SecurityDashboard.Category.WEAK, listOf(item(), item())))
        val remaining = item()

        state.publishScan(summary(), excluded = 2) { listOf(remaining) }

        assertEquals(listOf(remaining), state.value.categoryItems)
        assertEquals(2, state.value.excludedCount)
        assertEquals(false, state.value.loading)
    }

    @Test
    fun `with no category open nothing is looked up`() {
        val state = MutableStateFlow(HealthDashboardUiState())

        state.publishScan(summary(), excluded = 0) { error("no category is open, so there is nothing to rebuild") }

        assertEquals(emptyList(), state.value.categoryItems)
    }
}
