package com.localkeep.feature.health

import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The health drill-down search folds accents, like every other search in the app.
 *
 * It matched with a bare `contains(ignoreCase = true)` while the vault list and the trash both run
 * their needle and their haystack through `foldDiacritics` — so a "Café Wifi", exactly the sort of
 * title a KDBX import brings in, was unreachable by "cafe" here alone. A search that silently
 * returns nothing reads as the item not existing, and this is the one screen a user opens *because*
 * something is already wrong with that item. Found 2026-08-30.
 */
class HealthCategorySearchFoldingTest {

    private val now: Instant = Instant.parse("2026-08-30T00:00:00Z")

    private fun item(title: String): VaultItem = VaultItem(
        id = UUID.randomUUID(),
        title = title,
        createdAt = now,
        updatedAt = now,
        content = Login(username = "ada@example.com", password = "hunter2", passwordChangedAt = now),
    )

    private fun filtered(query: String, vararg titles: String): List<String> =
        HealthDashboardUiState(categoryItems = titles.map(::item), categoryQuery = query)
            .filteredItems
            .map { it.title }

    @Test
    fun `an unaccented query finds an accented title`() {
        assertEquals(listOf("Café Wifi"), filtered("cafe", "Café Wifi", "Router"))
    }

    /** The other direction too: the accents someone's keyboard produces must find the plain title. */
    @Test
    fun `an accented query finds an unaccented title`() {
        assertEquals(listOf("Cafe Wifi"), filtered("café", "Cafe Wifi", "Router"))
    }

    @Test
    fun `case is still ignored, and so is surrounding space`() {
        assertEquals(listOf("Café Wifi"), filtered("  CAFÉ  ", "Café Wifi", "Router"))
    }

    /**
     * The controls. Folding must not turn the search into a match-anything: an unrelated query still
     * finds nothing, and a blank one still means "no filter" rather than "no results".
     */
    @Test
    fun `an unrelated query still matches nothing`() {
        assertTrue(filtered("zzz", "Café Wifi", "Router").isEmpty())
    }

    @Test
    fun `a blank query does not filter`() {
        assertEquals(listOf("Café Wifi", "Router"), filtered("   ", "Café Wifi", "Router"))
    }
}
