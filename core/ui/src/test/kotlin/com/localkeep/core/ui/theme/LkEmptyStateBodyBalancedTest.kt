package com.localkeep.core.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A centred empty-state body never ends on a single word (D3-F-04).
 *
 * The Recovery Kit gate's body is centred prose in this component, and "continue." sat alone on its
 * last line: the Paragraph break the body style carries is tuned for left-aligned reading, where a
 * short last line is invisible; centred, it is a lone word hanging under the block. Balanced
 * breaking ([androidx.compose.ui.text.style.LineBreak.Heading]) spreads the words over the lines
 * instead. Swept over every width a phone can give it, because an orphan depends on where the lines
 * happen to fall.
 *
 * FAILS WITHOUT THE FIX: at some width the last line is one word.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LkEmptyStateBodyBalancedTest {

    @get:Rule
    val compose = createComposeRule()

    private val body = "A Recovery Kit opens this vault without the master password, and making one " +
        "replaces any kit you already have. Confirm with your vault password to continue."

    @Test
    fun noWidthLeavesTheLastLineAsOneWord() {
        var widthDp by mutableStateOf(300)
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.width(widthDp.dp)) {
                    LkEmptyState(icon = LkIcons.Lock, title = "Confirm it's you", body = body, compact = true)
                }
            }
        }
        val orphans = mutableListOf<String>()
        for (w in 240..400 step 3) {
            widthDp = w
            compose.waitForIdle()
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("A Recovery Kit", substring = true).fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            val layout = results.first()
            val last = layout.layoutInput.text.text
                .substring(layout.getLineStart(layout.lineCount - 1), layout.getLineEnd(layout.lineCount - 1))
                .trim()
            if (layout.lineCount > 1 && ' ' !in last) orphans += "${w}dp: \"$last\""
        }
        assertTrue(orphans.isEmpty(), "the last line is a lone word at ${orphans.size} widths: ${orphans.take(4)}")
    }
}
