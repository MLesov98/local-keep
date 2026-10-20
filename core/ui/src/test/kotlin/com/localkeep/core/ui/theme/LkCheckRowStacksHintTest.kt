package com.localkeep.core.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [LkCheckRow] puts its hint under the label when the row is squeezed (SCL-011).
 *
 * The generator's "Avoid ambiguous" row at P1 (308dp, font scale 2.0) left the label whatever the
 * hint "I l 1 O 0" did not take, and the label broke mid-word. Real strings are used so the test
 * measures the same widths the screen does.
 *
 * FAILS WITHOUT THE FIX: at 268dp and 2.0 a line of the label ended inside "ambiguous".
 * CONTROL: at 371dp and 1.0 the hint stays beside the label on the label's line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h548dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LkCheckRowStacksHintTest {

    @get:Rule
    val compose = createComposeRule()

    private class Measured(val label: Rect, val hint: Rect, val labelLines: List<String>)

    private fun measure(width: Dp, fontScale: Float): Measured {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                LocalKeepTheme {
                    Box(Modifier.width(width)) {
                        LkCheckRow(LABEL, checked = true, onToggle = {}, first = true, hint = HINT)
                    }
                }
            }
        }
        compose.waitForIdle()
        val labelNode = compose.onNodeWithText(LABEL, useUnmergedTree = true).fetchSemanticsNode()
        val hintNode = compose.onNodeWithText(HINT, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        labelNode.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)
            ?.action?.invoke(results)
        val layout = results.single()
        val lines = (0 until layout.lineCount).map {
            layout.layoutInput.text.text.substring(layout.getLineStart(it), layout.getLineEnd(it)).trim()
        }
        return Measured(labelNode.boundsInRoot, hintNode.boundsInRoot, lines)
    }

    @Test
    fun theLabelIsNotBrokenInsideAWordAtTheNarrowestLargestWindow() {
        val m = measure(268.dp, 2.0f)
        val words = LABEL.split(' ').toSet()
        assertTrue(
            m.labelLines.all { line -> line.split(' ').all { it in words } },
            "\"$LABEL\" broke inside a word at 268dp / 2.0: ${m.labelLines}",
        )
        assertTrue(
            m.hint.top >= m.label.bottom - 1f,
            "the hint should sit under the label when the row is squeezed (label ${m.label}, hint ${m.hint})",
        )
    }

    @Test
    fun theHintStaysBesideTheLabelWhenThereIsRoom() {
        val m = measure(371.dp, 1.0f)
        assertEquals(1, m.labelLines.size, "label wrapped at 371dp / 1.0: ${m.labelLines}")
        assertTrue(
            m.hint.left >= m.label.right - 1f && m.hint.top < m.label.bottom,
            "the hint left the label's line at 371dp / 1.0 (label ${m.label}, hint ${m.hint})",
        )
    }

    private companion object {
        const val LABEL = "Avoid ambiguous"
        const val HINT = "I l 1 O 0"
    }
}
