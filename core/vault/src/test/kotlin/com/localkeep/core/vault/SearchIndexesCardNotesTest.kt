package com.localkeep.core.vault

import com.localkeep.core.model.Card
import com.localkeep.core.model.VaultItem
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A Card's `notes` field must be searchable, like `Login.notes` and `Note.body`.
 *
 * It was the one notes/body carrier left out of the haystack: `searchableText` for a Card yielded
 * only the holder and the last four PAN digits, so a card was unfindable by a phrase the user
 * typed into its notes — while the search box advertises "notes". Not a masked value: CVV, PIN and
 * the full PAN stay excluded (covered by [SearchExcludesHiddenFieldsTest] and the PAN branch).
 *
 * Calls `matchesSearchQuery`, the same predicate `VaultRepository.search` filters on, with the
 * needle pre-normalised exactly as `search` normalises it.
 */
class SearchIndexesCardNotesTest {

    private fun cardWith(notes: String): VaultItem = VaultItem(
        id = java.util.UUID.randomUUID(),
        title = "Travel card",
        content = Card(holder = "Jane Doe", number = "4111111111111111", notes = notes),
        createdAt = java.time.Instant.EPOCH,
        updatedAt = java.time.Instant.EPOCH,
    )

    private fun VaultItem.matches(query: String): Boolean =
        matchesSearchQuery(
            query.trim().lowercase(java.util.Locale.ROOT).foldDiacritics(),
            folderPath = emptyList(),
        )

    @Test
    fun `card notes are searchable`() {
        val item = cardWith("frequent flyer BA-4471, expires London office")
        assertTrue(
            item.matches("frequent flyer"),
            "a Card's notes must enter the search haystack, same as Login.notes and Note.body",
        )
        assertTrue(item.matches("london"), "any word in the notes must match, not just a prefix")
    }
}
