package com.localkeep.core.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getBoundsInRoot
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
 * An empty-state title that wraps is centred line by line, like the body under it (SCL-032).
 *
 * The Column centred a one-line title by its width, but a title that wraps ("Identität / bestätigen"
 * at 308dp and font scale 2.0) fills the column and was start-aligned inside it, drifting left of its
 * centred body (`hidden-items__P1-de`).
 *
 * FAILS WITHOUT THE FIX: the second line starts at the left edge rather than centred.
 * CONTROL: a one-line title is centred either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LkEmptyStateTitleCenteredTest {

    @get:Rule
    val compose = createComposeRule()

    private fun layoutOf(title: String): TextLayoutResult {
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.width(260.dp)) {
                    LkEmptyState(icon = LkIcons.Lock, title = title, body = "Body text.", compact = true)
                }
            }
        }
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(title).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first()
    }

    @Test
    fun `a wrapped title is centred on every line`() {
        val layout = layoutOf("Identitaet bestaetigen jetzt")
        assertTrue(layout.lineCount > 1, "the title must really wrap; it took ${layout.lineCount} line")
        val width = layout.size.width.toFloat()
        for (line in 0 until layout.lineCount) {
            val mid = (layout.getLineLeft(line) + layout.getLineRight(line)) / 2
            assertTrue(
                kotlin.math.abs(mid - width / 2) < 2f,
                "line $line is centred at $mid of $width: left ${layout.getLineLeft(line)}, right ${layout.getLineRight(line)}",
            )
        }
    }

    @Test
    fun `a one-line title stays centred`() {
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.width(260.dp)) {
                    LkEmptyState(icon = LkIcons.Lock, title = "Empty", body = "Body text.", compact = true)
                }
            }
        }
        val bounds = compose.onNodeWithText("Empty").getBoundsInRoot()
        val mid = (bounds.left.value + bounds.right.value) / 2
        assertTrue(kotlin.math.abs(mid - 130f) < 2f, "the title is centred at $mid of a 260dp column: $bounds")
    }
}
