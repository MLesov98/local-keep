package com.localkeep.app.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
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
 * Every bottom-bar label is whole, and the three tabs stay one height (SCL-019).
 *
 * German at font scale 2.0 on a 308dp window rendered "Gesundhei" on the device: the label shrank to
 * its floor and the ~88dp tab was still narrower than the word, so the last letter was clipped. A
 * one-line label cannot hyphenate, so the floor has to be one the longest label fits at. The labels
 * also shrank independently, so the selected tab (whose short word fits at full size) stood taller
 * than the other two and moved its icon.
 *
 * Only the height half can be red here. Robolectric's font is far narrower than the emulator's:
 * "Gesundheit" fits a 200dp window's tab even at the old 9sp floor, so the width assertion below
 * passes before and after the change and is kept as a guard, not as proof of the floor. The floor
 * itself (11dp rendered, not 9sp) is a device check.
 *
 * FAILS WITHOUT THE FIX: the tabs differ in height ([114, 104] measured).
 * CONTROL: English at 411dp and 1.0 keeps the full 12sp on every label.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomTabsFitTheirLabelsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalKeepTheme { LkBottomBar(route = "vault", onTab = {}) } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun labelResults(): List<Pair<SemanticsNode, TextLayoutResult>> =
        compose.onAllNodes(SemanticsMatcher("has text layout") { SemanticsActions.GetTextLayoutResult in it.config })
            .fetchSemanticsNodes()
            .map { node ->
                val out = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
                node to out.first()
            }

    /** The three clickable tab columns, found by the label they carry. */
    private fun tabs(): List<SemanticsNode> =
        compose.onAllNodes(SemanticsMatcher("tab") { it.config.contains(SemanticsProperties.ContentDescription) })
            .fetchSemanticsNodes()
            .filter { it.size.width > 0 && it.size.height > 0 }

    @Test
    @Config(qualifiers = "de-w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `german labels are whole on the 308dp floor at 2_0 text`() {
        show()
        val labels = labelResults()
        assertEquals(3, labels.size, "expected the three tab labels")
        labels.forEach { (node, layout) ->
            assertTrue(
                !layout.didOverflowWidth && layout.getLineRight(0) <= node.size.width + 1f,
                "'${layout.layoutInput.text.text}' is ${layout.getLineRight(0)}px wide in a ${node.size.width}px label slot",
            )
            assertEquals(1, layout.lineCount)
        }
    }

    @Test
    @Config(qualifiers = "de-w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `the three tabs are one height whatever size each label settled on`() {
        show()
        val heights = tabs().map { it.size.height }.distinct()
        assertEquals(1, heights.size, "tabs differ in height: $heights")
    }

    /** Pixels between the selection dot's bottom and its tab's bottom edge. */
    private fun dotGapPx(route: String): Float {
        val tab = compose.onNodeWithTag("tab-$route", useUnmergedTree = true).fetchSemanticsNode()
        val dot = compose.onNodeWithTag("tab-dot-$route", useUnmergedTree = true).fetchSemanticsNode()
        return (tab.positionInRoot.y + tab.size.height) - (dot.positionInRoot.y + dot.size.height)
    }

    /**
     * SCL-036. From font scale 1.45 the icon, label slot and dot filled the tab's 52dp floor and the
     * dot sat on its bottom edge (0.3dp measured on the device, against 5dp at 1.0).
     *
     * FAILS WITHOUT THE FIX: the gap collapses to 0 once the content outgrows the floor.
     */
    @Test
    @Config(qualifiers = "en-w411dp-h891dp-xxhdpi", fontScale = 2.0f)
    fun `the selection dot keeps a gap above the tab edge at 2_0 text`() {
        show()
        val gapDp = dotGapPx("vault") / compose.density.density
        assertTrue(gapDp >= 3f, "the dot is ${gapDp}dp above the tab's bottom edge; the floor is 3dp")
    }

    /** The control: ordinary size keeps its gap (5dp measured on the device). */
    @Test
    @Config(qualifiers = "en-w411dp-h891dp-xxhdpi", fontScale = 1.0f)
    fun `the selection dot keeps a gap above the tab edge at ordinary size`() {
        show()
        val gapDp = dotGapPx("vault") / compose.density.density
        assertTrue(gapDp >= 3f, "the dot is ${gapDp}dp above the tab's bottom edge; the floor is 3dp")
    }

    /** The control: nothing shrinks when nothing needs to. */
    @Test
    @Config(qualifiers = "en-w411dp-h891dp-xxhdpi", fontScale = 1.0f)
    fun `english at ordinary size keeps the full label size`() {
        show()
        val heights = tabs().map { it.size.height }.distinct()
        assertEquals(1, heights.size, "tabs differ in height: $heights")
        labelResults().forEach { (_, layout) ->
            assertTrue(!layout.didOverflowWidth)
            assertEquals(12f, layout.layoutInput.style.fontSize.value, "labels keep 12sp when they fit")
        }
    }
}
