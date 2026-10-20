package com.localkeep.feature.health

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.localkeep.core.health.SecurityDashboard
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A Health category row's title is never broken inside a word at the narrowest window (S-L4).
 *
 * At 308dp and 2.0 text the count badge is 76dp and sits beside the label, leaving the title about
 * 110dp: "Potentially compromised" broke as "Potential / ly / compro / mised". Past
 * [com.localkeep.core.ui.theme.LkText.stackAboveFontScale] the badge now sits above the text, which
 * gives the title the whole row, and the title style hyphenates for the width that is still short.
 *
 * Measured on the line breaks themselves: every line of the laid-out title must end where a word
 * ends, so no word was split.
 *
 * FAILS WITHOUT THE FIX: the title wraps on three or more lines and a line ends mid-word.
 * CONTROL: at 411dp and 1.0 the badge stays beside the label and the title is one line.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CategoryRowKeepsWordsWholeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show() {
        compose.setContent {
            LocalKeepTheme {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    CategoryRow(
                        category = HealthCategory(
                            SecurityDashboard.Category.BREACHED,
                            LABEL,
                            3,
                            "Matched the offline breach list.",
                            Color.Red,
                        ),
                        onClick = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun titleLayout(): TextLayoutResult {
        val node = compose.onNodeWithText(LABEL, useUnmergedTree = true).fetchSemanticsNode()
        val out = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.first()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `no word of the title is split at the narrowest window and largest text`() {
        show()
        val layout = titleLayout()
        val text = layout.layoutInput.text.text
        for (line in 0 until layout.lineCount) {
            val end = layout.getLineEnd(line, visibleEnd = true)
            val atWordEnd = end == text.length || text[end] == ' ' || text[end - 1] == ' '
            assertTrue(
                atWordEnd,
                "line $line of '$text' ends at $end, inside a word: '${text.substring(layout.getLineStart(line), end)}'",
            )
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", fontScale = 1.0f)
    fun `at ordinary size the title is one line`() {
        show()
        assertEquals(1, titleLayout().lineCount)
    }

    private companion object {
        const val LABEL = "Potentially compromised"
    }
}
