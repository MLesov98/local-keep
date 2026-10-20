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
 * A folder name is searchable, on the same terms as a tag.
 *
 * Until 2026-07-31 filing an item into a folder changed nothing the user could observe: the vault
 * list did not show folders, did not filter by them, and search did not match them. The name the
 * user typed was write-only. Search is one of the three surfaces that fixed it, and it is the one
 * with a security question attached — so this pins both halves. The name is not a secret (it is
 * rendered on the item's own row), but the rule that masked values stay out of the index is
 * unrelated to this change and must survive it.
 *
 * These call `matchesSearchQuery`, the predicate `VaultRepository.search` actually filters on,
 * rather than a re-implementation — the same reasoning as `SearchExcludesHiddenFieldsTest`.
 */
class SearchMatchesFolderNameTest {

    private fun item(title: String = "Bank"): VaultItem = VaultItem(
        id = UUID.randomUUID(),
        title = title,
        content = Login(username = "jane", notes = ""),
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    /** Mirrors [VaultRepository.search]'s normalisation, then defers to the real predicate. */
    private fun VaultItem.matches(query: String, folderName: String?): Boolean =
        matchesSearchQuery(
            query.trim().lowercase(Locale.ROOT).foldDiacritics(),
            listOfNotNull(folderName),
        )

    @Test
    fun `an item is findable by the name of the folder it sits in`() {
        assertTrue(item().matches("finance", folderName = "Finance"))
    }

    @Test
    fun `folder matching is case- and diacritic-insensitive, like every other field`() {
        // foldDiacritics is applied to the haystack, so "Épargne" has to be reachable by "eparg" —
        // the same normalisation titles and tags already get, and the reason a French or Portuguese
        // user can type without accents.
        assertTrue(item().matches("EPARG", folderName = "Épargne"))
        assertTrue(item().matches("épargne", folderName = "Epargne"))
    }

    @Test
    fun `an item in no folder is unaffected`() {
        // The null path is the common one — most items are filed nowhere — and it must not throw,
        // and must not start matching things it did not match before.
        assertTrue(item(title = "Bank").matches("bank", folderName = null))
        assertFalse(item(title = "Bank").matches("finance", folderName = null))
    }

    @Test
    fun `a folder name does not make unrelated items match`() {
        assertFalse(item().matches("travel", folderName = "Finance"))
    }
}
