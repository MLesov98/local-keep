package com.localkeep.feature.vaultlist

import androidx.compose.ui.test.junit4.createComposeRule
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Under the Favorites filter the screen said it three times: the chip, the FAVORITES section
 * heading, and "filter: favorites" at the end of the count line. The heading is the one that cannot
 * be missed, so the count line drops its clause while that heading is on screen — and keeps it
 * whenever the heading is not (no favourite to head, or a search is running, which suppresses it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h1600dp-xhdpi")
class FavouritesCountLineTest {

    @get:Rule
    val compose = createComposeRule()

    private fun item(favorite: Boolean) = VaultItem(
        id = UUID.randomUUID(),
        title = "item",
        favorite = favorite,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = Login(username = "u", password = "p"),
    )

    private fun label(state: VaultListUiState): String {
        var out = ""
        compose.setContent { LocalKeepTheme { out = state.countLabel() } }
        compose.waitForIdle()
        return out
    }

    private fun state(
        items: List<VaultItem>,
        showFavoritesOnly: Boolean = true,
        searchQuery: String = "",
        tagFilter: String? = null,
    ) = VaultListUiState(
        items = items,
        showFavoritesOnly = showFavoritesOnly,
        searchQuery = searchQuery,
        tagFilter = tagFilter,
        projectionVersion = 1L,
        scopeItemCount = items.size,
    )

    @Test
    fun theCountLineDoesNotRepeatFavoritesWhenTheHeadingNamesIt() {
        val text = label(state(listOf(item(true), item(true))))
        assertFalse("favorites" in text, "heading already says it, count line repeated it: $text")
        assertFalse("filter" in text, "nothing else is narrowing, so no filter clause either: $text")
    }

    @Test
    fun otherNarrowingsStillNameThemselvesBesideTheHeading() {
        val text = label(state(listOf(item(true)), tagFilter = "work"))
        assertTrue("tag work" in text, "control: the tag is not named by any heading: $text")
        assertFalse("favorites" in text, text)
    }

    @Test
    fun aSearchHidesTheHeadingSoTheCountLineKeepsTheClause() {
        val text = label(state(listOf(item(true)), searchQuery = "x"))
        assertTrue("favorites" in text, "control: no FAVORITES heading while searching: $text")
    }

    @Test
    fun noFavouriteMeansNoHeadingSoTheCountLineKeepsTheClause() {
        val text = label(state(emptyList()))
        assertTrue("favorites" in text, "control: an empty favourites view has no heading: $text")
        assertEquals(text, text.trim())
    }
}
