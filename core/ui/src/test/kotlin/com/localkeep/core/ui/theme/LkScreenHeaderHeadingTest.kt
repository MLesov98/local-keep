package com.localkeep.core.ui.theme

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Screen titles are headings to a screen reader, not just large text.
 *
 * TalkBack's navigate-by-heading gesture is the standard way a blind user orients on a screen they
 * have not seen before — jump between headings, then explore the one you want. It only stops on
 * nodes carrying `SemanticsProperties.Heading`. This app had that property on nothing, anywhere, so
 * the gesture found no stops on Vault, Generator, Health, Settings or any item screen, and the only
 * route through an unfamiliar screen was to swipe element by element from the top.
 *
 * Styling a title `LkText.screenTitle` says nothing to the accessibility tree — font size is not a
 * semantic. That is why this asserts the property on a composed node rather than checking the
 * source for a `heading()` call: the point is what the tree carries, not what the file says.
 *
 * `LkScreenHeader` is the one composable behind all twelve screen titles, which is why the fix and
 * this test both live here rather than being repeated per screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LkScreenHeaderHeadingTest {

    @get:Rule
    val compose = createComposeRule()

    private val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test
    fun `the screen title is a heading`() {
        compose.setContent {
            LocalKeepTheme {
                LkScreenHeader(title = "Security", subtitle = "Password health and protections")
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Security").assert(isHeading)
    }

    @Test
    fun `the subtitle is not a heading`() {
        // The control. Marking every text node a heading would satisfy the case above while making
        // navigate-by-heading stop on everything — which is the same uselessness as stopping on
        // nothing, and is what a single positive assertion would miss.
        compose.setContent {
            LocalKeepTheme {
                LkScreenHeader(title = "Security", subtitle = "Password health and protections")
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Password health and protections")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Heading))
    }
}
