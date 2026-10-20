package com.localkeep.core.vault

import com.localkeep.core.model.CustomField
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Search must not index the value of a field the UI masks.
 *
 * `search()` matches on substring, so indexing a masked value turns the search box into an oracle:
 * a value can be recovered a character at a time by probing, without ever passing the re-auth
 * prompt that revealing it demands on a reprompt-flagged item. The card PAN was removed from the
 * index for the same reason — HIDDEN custom fields were the last masked class still fully indexed,
 * and KDBX imports produce them in bulk because KeePassJava2 exposes no per-property protection
 * flag, so every non-standard string property lands HIDDEN.
 *
 * These call `matchesSearchQuery`, the same predicate `VaultRepository.search` filters on, so
 * they exercise the real matcher rather than a copy of its logic. The needle is pre-normalised
 * here exactly as `search` normalises it.
 */
class SearchExcludesHiddenFieldsTest {

    private fun itemWith(vararg fields: CustomField): VaultItem = VaultItem(
        id = java.util.UUID.randomUUID(),
        title = "Bank",
        content = Login(username = "jane", notes = ""),
        customFields = fields.toList(),
        createdAt = java.time.Instant.EPOCH,
        updatedAt = java.time.Instant.EPOCH,
    )

    /** Mirrors [VaultRepository.search]'s normalisation, then defers to the real predicate. */
    private fun VaultItem.matches(query: String): Boolean =
        matchesSearchQuery(
            query.trim().lowercase(java.util.Locale.ROOT).foldDiacritics(),
            folderPath = emptyList(),
        )

    @Test
    fun `hidden field value is not searchable`() {
        val item = itemWith(
            CustomField(name = "Recovery code", value = "AB12-CD34-EF56", type = CustomFieldType.HIDDEN),
        )
        assertFalse(
            item.matches("ab12"),
            "a HIDDEN field's value must not be matchable — search is a substring oracle",
        )
    }

    @Test
    fun `hidden field name stays searchable`() {
        val item = itemWith(
            CustomField(name = "Recovery code", value = "AB12-CD34-EF56", type = CustomFieldType.HIDDEN),
        )
        assertTrue(
            item.matches("recovery"),
            "the field name is a label, not the secret — a hidden field must stay findable by name",
        )
    }

    @Test
    fun `visible field value is still searchable`() {
        val item = itemWith(
            CustomField(name = "Branch", value = "Plovdiv", type = CustomFieldType.TEXT),
        )
        assertTrue(
            item.matches("plovdiv"),
            "excluding HIDDEN must not cost recall on fields the user did not hide",
        )
    }

    @Test
    fun `a partial prefix of a hidden value does not match either`() {
        // The oracle is built out of prefixes, so the single-character case is the one that matters.
        val item = itemWith(
            CustomField(name = "Seed", value = "correct horse battery", type = CustomFieldType.HIDDEN),
        )
        assertFalse(item.matches("c"), "prefix probing must not confirm a first character")
        assertFalse(item.matches("correct hor"), "nor a longer prefix")
    }
}
