package com.localkeep.core.vault

import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Trash search must find what vault search would find.
 *
 * It matched **the title only**, while the vault list matched title, username, URLs, notes, tags and
 * custom fields. So an item you could find by its username a moment before deleting it became
 * unfindable the moment it was deleted — backwards for the one screen whose entire purpose is "I
 * deleted the wrong thing". Recorded as UX debt 8-3; fixed 2026-07-29 by
 * [VaultRepository.searchTrash].
 *
 * The fix had to live in `:core:vault` rather than at the call site: `search()` excludes deleted
 * items by definition, and `matchesSearchQuery` is `internal` to this module, so
 * `:feature:vaultlist` cannot reach it. These tests exercise **that same predicate**, not a copy —
 * which is the property that stops the two search paths drifting apart again.
 */
class TrashSearchTest {

    private fun login(title: String, username: String, notes: String = "") = VaultItem(
        id = UUID.randomUUID(),
        title = title,
        content = Login(username = username, notes = notes),
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    /**
     * Mirrors the normalisation both `search` and `searchTrash` apply, then the real predicate.
     *
     * `folderPath = emptyList()` because none of these fixtures is filed anywhere; folder matching has its
     * own coverage in `SearchMatchesFolderNameTest`.
     */
    private fun VaultItem.matches(query: String): Boolean =
        matchesSearchQuery(query.trim().lowercase(Locale.ROOT).foldDiacritics(), folderPath = emptyList())

    @Test
    fun `a deleted item is findable by username, not only by title`() {
        val item = login(title = "Bank", username = "jane@example.com")
        assertTrue(
            item.matches("jane"),
            "the matcher searchTrash now uses must match a username — matching titles only is the " +
                "defect this closed",
        )
    }

    @Test
    fun `a deleted item is findable by its notes`() {
        val item = login(title = "Bank", username = "jane", notes = "old current account")
        assertTrue(item.matches("current account"))
    }

    @Test
    fun `search still misses what it should miss`() {
        // The matcher's existing decisions carry over unchanged — this is the point of reusing it
        // rather than writing a second one. A term in no indexed field must not match.
        val item = login(title = "Bank", username = "jane")
        assertFalse(item.matches("zzz-not-present"))
    }

    @Test
    fun `matching is diacritic-folded and locale-stable, as the vault list is`() {
        val item = login(title = "Café", username = "jane")
        assertTrue(
            item.matches("cafe"),
            "trash search normalises through the same foldDiacritics/Locale.ROOT path as the vault " +
                "list, so the two cannot disagree about what matches",
        )
    }
}
