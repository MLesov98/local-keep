package com.localkeep.core.ui.theme

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A blank line in a masthead body is a paragraph break: one string key, several [Text]s.
 *
 * Reported 2026-10-14 on the "Delete the original from your Gallery?" dialog, whose three sentences
 * ran together as one grey block. Splitting in the component keeps one translatable key per body
 * and gives every dialog the same rhythm.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LkModalMastheadParagraphsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aBlankLineSplitsTheBodyIntoSeparateParagraphs() {
        compose.setContent { LocalKeepTheme { LkModalMasthead(title = "Title", body = "First.\n\nSecond.\n\nThird.") } }
        for (p in listOf("First.", "Second.", "Third.")) {
            assertEquals(1, compose.onAllNodesWithText(p).fetchSemanticsNodes().size, "no node for \"$p\"")
        }
    }

    /** Control: a body with no blank line stays one node. */
    @Test
    fun aBodyWithoutABlankLineStaysOneParagraph() {
        compose.setContent { LocalKeepTheme { LkModalMasthead(title = "Title", body = "One. Two.\nThree.") } }
        assertEquals(1, compose.onAllNodesWithText("One. Two.\nThree.").fetchSemanticsNodes().size)
    }
}
