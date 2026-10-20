package com.localkeep.feature.vaultlist

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the filter sheet offers is a function of the list's state, and two of its sections are
 * conditional in ways that matter beyond layout.
 *
 * The invisible-only switch is the one with a security argument behind it. Hidden entries sit
 * behind a reveal phrase, and the feature's value is that nothing on screen says they exist — so
 * the switch is composed only inside a reveal session, when the entries are already showing under
 * the amber banner. A switch that was merely disabled outside a session would still tell whoever
 * opened the sheet that there is something to show. [theInvisibleOnlyControlIsAbsentWithoutARevealSession]
 * was watched failing against an ungated sheet before the gate went in (2026-09-02).
 *
 * The tags section is the other: it lists what the vault has, verbatim, and is left out entirely
 * when the vault has nothing — an empty heading is a control that does nothing.
 *
 * Rendered through [FilterSheetContent] rather than [FilterSheet], so no modal window is involved:
 * the modal only hosts the content, and the content is where every decision under test is made.
 */
@RunWith(RobolectricTestRunner::class)
// Tall enough for every section to be on screen at once. Robolectric's default window is 320x470px,
// and ten type rows at the 48dp touch floor already overrun it — the sections under test rendered
// at t=470 and `assertIsDisplayed` read the sheet as broken when it was only scrolled away.
@Config(sdk = [34], qualifiers = "w411dp-h1600dp-xhdpi")
class FilterSheetContentTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(state: VaultListUiState, onPickTag: (String?) -> Unit = {}) {
        compose.setContent {
            LocalKeepTheme {
                Column {
                    FilterSheetContent(
                        state = state,
                        onPickType = {},
                        onPickTag = onPickTag,
                        onToggleHiddenOnly = {},
                        onClearAll = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theInvisibleOnlyControlIsAbsentWithoutARevealSession() {
        // Not merely off, not disabled: absent from the tree, so nothing — TalkBack included — can
        // find it. `showHiddenOnly` is set here too, deliberately: even a stale flag must not be
        // enough to compose the control.
        show(VaultListUiState(revealSessionActive = false, showHiddenOnly = true))
        compose.onNodeWithTag("filter-hidden-only").assertDoesNotExist()
        compose.onNodeWithTag("filter-type-all").assertIsDisplayed() // the rest of the sheet is there
    }

    @Test
    fun theInvisibleOnlyControlIsPresentInsideARevealSession() {
        show(VaultListUiState(revealSessionActive = true))
        compose.onNodeWithTag("filter-hidden-only").assertIsDisplayed()
    }

    @Test
    fun theTagsSectionIsOmittedWhenTheVaultHasNoTags() {
        show(VaultListUiState(availableTags = emptyList()))
        compose.onNodeWithTag("filter-tags").assertDoesNotExist()
    }

    @Test
    fun theTagsSectionListsEveryTagAsAuthored() {
        // Verbatim: a tag is user data, and the sheet must show the string on the item, not a
        // normalised or translated version of it.
        show(VaultListUiState(availableTags = listOf("Bank stuff", "work")))
        compose.onNodeWithTag("filter-tags").assertIsDisplayed()
        compose.onNodeWithText("Bank stuff").assertIsDisplayed()
        compose.onNodeWithText("work").assertIsDisplayed()
    }

    @Test
    fun tappingATagSelectsItAndTappingTheSelectedTagClearsIt() {
        val picks = mutableListOf<String?>()
        show(VaultListUiState(availableTags = listOf("work"), tagFilter = "work"), onPickTag = { picks += it })
        compose.onNodeWithTag("filter-tag-work").performClick()
        assertEquals(listOf<String?>(null), picks)
    }

    @Test
    fun tappingAnUnselectedTagPicksIt() {
        val picks = mutableListOf<String?>()
        show(VaultListUiState(availableTags = listOf("work")), onPickTag = { picks += it })
        compose.onNodeWithTag("filter-tag-work").performClick()
        assertEquals(listOf<String?>("work"), picks)
    }
}
