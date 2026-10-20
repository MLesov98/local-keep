package com.localkeep.core.vault

import com.localkeep.core.model.Coordinate
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A saved location is findable by its name and by nothing else.
 *
 * Same reasoning as `SearchExcludesHiddenFieldsTest`, with a stronger conclusion. `search()` matches
 * on substring, so anything in the index is recoverable a character at a time by probing — and for a
 * coordinate that walks straight past the re-auth prompt the detail screen raises before it will
 * show the value. Unlike a card, there is no "last four" here that a person would plausibly search
 * by, so the position is excluded whole.
 *
 * The capture time is excluded on the same grounds: "was this person at some place at 09:14" is the
 * question the item exists to answer, and a search box that answered it would be the leak.
 */
class SearchExcludesCoordinatesTest {

    private val fix = Coordinate(
        latitude = 42.135_098,
        longitude = 24.745_129,
        capturedAt = Instant.parse("2026-08-19T09:14:58Z"),
        accuracyMeters = 8.5f,
    )

    private fun item(title: String = "Where I parked", tags: List<String> = emptyList()) = VaultItem(
        id = UUID.randomUUID(),
        title = title,
        tags = tags,
        content = fix,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    /** Mirrors [VaultRepository.search]'s normalisation, then defers to the real predicate. */
    private fun VaultItem.matches(query: String, folderPath: List<String> = emptyList()): Boolean =
        matchesSearchQuery(
            query.trim().lowercase(Locale.ROOT).foldDiacritics(),
            folderPath = folderPath,
        )

    @Test
    fun `the position is not searchable`() {
        val subject = item()
        listOf("42.135098", "42.135", "24.745129", "24.74", "42", "8.5", "2026-08-19", "09:14")
            .forEach { needle ->
                assertFalse(
                    subject.matches(needle),
                    "“$needle” matched — search is a substring oracle, and this one would answer " +
                        "“where was this person” without ever raising the reveal prompt",
                )
            }
    }

    @Test
    fun `the name, tags and folder still find it`() {
        assertTrue(item().matches("parked"), "the title is how a saved location is meant to be found")
        assertTrue(item(tags = listOf("Trip")).matches("trip"))
        assertTrue(item().matches("holiday", folderPath = listOf("Holiday")))
    }
}
