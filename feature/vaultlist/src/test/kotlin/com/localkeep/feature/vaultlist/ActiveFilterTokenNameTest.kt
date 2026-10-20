package com.localkeep.feature.vaultlist

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A token in the active-filter strip has to say what it is and what a tap does.
 *
 * Found on device 2026-09-02: the token's clickable node carried no `content-desc`, so its only
 * name was the child text "Logins" — TalkBack read a bare category name, with nothing saying it
 * was a filter or that activating it removes one. Its neighbours ("Favorites", "Filter") were
 * named; the token was not. The strip is the only surface that names the narrowings a screen
 * reader cannot otherwise see, so an unnamed token defeats its own purpose.
 *
 * The name is carried by the token's own node — the one that is clickable and toggleable — so a
 * screen reader hears it in one stop, and the tap still reaches `onRemove`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h800dp-xhdpi")
class ActiveFilterTokenNameTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(filters: List<ActiveFilter>, onRemove: (ActiveFilter) -> Unit = {}) {
        compose.setContent {
            LocalKeepTheme {
                ActiveFilterStrip(filters = filters, onRemove = onRemove)
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aTypeTokenIsNamedAsARemovableFilter() {
        show(listOf(ActiveFilter.Type(ItemTypeFilter.LOGIN)))
        compose.onNodeWithTag("filter-token-login")
            .assertContentDescriptionEquals("Remove filter: Logins")
            .assertHasClickAction()
            .assertIsOn()
    }

    @Test
    fun aTagTokenIsNamedWithTheTagAsAuthored() {
        show(listOf(ActiveFilter.Tag("Work stuff")))
        compose.onNodeWithTag("filter-token-tag")
            .assertContentDescriptionEquals("Remove filter: Work stuff")
    }

    @Test
    fun theFavouritesTokenIsNamedAsARemovableFilter() {
        show(listOf(ActiveFilter.Favourites))
        compose.onNodeWithTag("filter-token-favourites")
            .assertContentDescriptionEquals("Remove filter: Favorites")
    }

    @Test
    fun tappingANamedTokenStillRemovesIt() {
        // The name must not cost the tap: `labelled` merges descendants onto the same node the
        // toggleable action lives on, and this pins that the action is still reachable.
        val removed = mutableListOf<ActiveFilter>()
        show(listOf(ActiveFilter.Type(ItemTypeFilter.NOTE)), onRemove = { removed += it })
        compose.onNodeWithTag("filter-token-note").performClick()
        assertEquals(listOf<ActiveFilter>(ActiveFilter.Type(ItemTypeFilter.NOTE)), removed)
    }
}
