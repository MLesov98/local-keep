package com.localkeep.core.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shared screen header neither shatters its title nor eats the window (S-W4-F3).
 *
 * Health, German, 308x548dp at font scale 2.0: the menu button and Lock took 304 of 626px and left
 * "Passwort-Gesundheit" a 157px column, so it broke "Pas / sw / ort- / Ges / und / heit", and with
 * the subtitle the pinned header was 844 of 1096px. The vault list solved the first locally
 * (controls on a row of their own); the rule now lives in [LkScreenHeader] so every screen has it,
 * and a short window sheds the one line that restates the title rather than pin a third of itself.
 *
 * FAILS WITHOUT THE FIX: the title breaks inside a word; the header is taller than a third of the
 * window.
 * CONTROLS: at 411dp and font scale 1.0 the controls stay on the title's row, the title is one line
 * and the subtitle is still there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LkScreenHeaderScalingTest {

    @get:Rule
    val compose = createComposeRule()

    private var windowHeightDp = 0f
    private var trailWidth = 130.dp

    private fun show(title: String = TITLE, subtitle: String? = SUBTITLE, keepSubtitle: Boolean = false) {
        compose.setContent {
            LocalKeepTheme {
                windowHeightDp = LocalConfiguration.current.screenHeightDp.toFloat()
                LkScreenHeader(
                    title = title,
                    subtitle = subtitle,
                    keepSubtitle = keepSubtitle,
                    modifier = Modifier
                        .testTag("header")
                        .padding(horizontal = LkFrame.screenHorizontal)
                        .padding(top = LkFrame.screenTop),
                    leading = { Box(Modifier.size(48.dp).testTag("lead")) },
                    trailing = { Box(Modifier.size(trailWidth, 48.dp).testTag("trail")) { Text("Sperren") } },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun layout(title: String = TITLE): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(title).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
            .action?.invoke(results)
        return results.single()
    }

    /** Every line but the last ends on a space or on the hyphen of the compound. */
    private fun assertNoBreakInsideWord() {
        val r = layout()
        val text = r.layoutInput.text.text
        for (i in 0 until r.lineCount - 1) {
            val end = r.getLineEnd(i, visibleEnd = false)
            val before = text[end - 1]
            assertTrue(
                before == ' ' || before == '-',
                "line $i breaks inside a word: ${text.substring(r.getLineStart(i), end)}|${text.substring(end)} " +
                    "(${r.lineCount} lines)",
            )
            // A hyphenator's own hyphen is not in the text, so a break after a letter is also caught
            // by the check above; the fragment test below catches a letter run of one or two.
            val line = text.substring(r.getLineStart(i), end).trim()
            assertTrue(line.length >= 3, "line $i is a fragment: '$line'")
        }
    }

    @Test
    @Config(qualifiers = "de-w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `German title at the 308dp floor is not broken inside a word`() {
        show()
        assertNoBreakInsideWord()
    }

    @Test
    @Config(qualifiers = "de-w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `the pinned header takes no more than a third of a short window`() {
        show()
        val h = compose.onNodeWithTag("header").getBoundsInRoot().height.value
        assertTrue(h <= windowHeightDp / 3f, "the header is ${h}dp of a ${windowHeightDp}dp window")
    }

    @Test
    @Config(qualifiers = "de-w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `both controls stay reachable on the stacked row`() {
        show()
        val lead = compose.onNodeWithTag("lead").getBoundsInRoot()
        val trail = compose.onNodeWithTag("trail").getBoundsInRoot()
        val title = compose.onNodeWithText(TITLE).getBoundsInRoot()
        assertTrue(lead.bottom.value <= title.top.value + 0.5f, "menu $lead over title $title")
        assertTrue(trail.bottom.value <= title.top.value + 0.5f, "lock $trail over title $title")
        assertTrue(lead.right.value <= trail.left.value, "menu $lead beside lock $trail")
    }

    @Test
    @Config(qualifiers = "de-w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `a short window sheds the subtitle unless the caller says it must stay`() {
        show()
        compose.onNodeWithText(SUBTITLE).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "de-w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `keepSubtitle keeps it`() {
        show(keepSubtitle = true)
        compose.onNodeWithText(SUBTITLE).assertExists()
    }

    @Test
    @Config(qualifiers = "de-w308dp-h891dp-xhdpi", fontScale = 2.0f)
    fun `a tall window at large type keeps the subtitle`() {
        show()
        assertNoBreakInsideWord()
        compose.onNodeWithText(SUBTITLE).assertExists()
    }

    @Test
    @Config(qualifiers = "de-w411dp-h891dp-xxhdpi", fontScale = 1.0f)
    fun `a roomy window keeps one row, one line and the subtitle`() {
        // The word and the button at their 1.0 sizes (icon and "Sperren" are about 100dp).
        trailWidth = 100.dp
        show(title = "Gesundheit")
        assertEquals(1, layout("Gesundheit").lineCount)
        val lead = compose.onNodeWithTag("lead").getBoundsInRoot()
        val title = compose.onNodeWithText("Gesundheit").getBoundsInRoot()
        assertTrue(lead.top.value < title.bottom.value && title.top.value < lead.bottom.value, "controls left the title's row")
        compose.onNodeWithText(SUBTITLE).assertExists()
    }

    private companion object {
        const val TITLE = "Passwort-Gesundheit"
        const val SUBTITLE = "Offline-Analyse · nichts wird irgendwohin gesendet"
    }
}
