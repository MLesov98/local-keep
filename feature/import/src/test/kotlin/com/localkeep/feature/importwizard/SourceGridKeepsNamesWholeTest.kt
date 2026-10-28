package com.localkeep.feature.importwizard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The import source grid never breaks a source's name inside a word.
 *
 * At 308dp and font 1.5 the two-up grid left each name ~100dp, and "Bitwarden" and "1Password" came
 * out as "Bitwarde / n" and "1Passwo / rd" (final sweep, 2026-10-28). The grid now drops to one tile a
 * row when the widest word of any name will not fit a half-width tile. [GraphicsMode.Mode.NATIVE] is
 * what lets the names wrap at all; under legacy graphics this would pass against the broken grid.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceGridKeepsNamesWholeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun lines(name: String): Int {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(name, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single().lineCount
    }

    private fun assertWholeNames() {
        // The screen's own side margins, or the grid measures 308dp instead of what it is given.
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.padding(horizontal = LkFrame.screenHorizontal)) { SourceGrid(onSelect = {}) }
            }
        }
        compose.waitForIdle()
        val app = RuntimeEnvironment.getApplication()
        for (source in listOf(ImportSource.BITWARDEN, ImportSource.ONEPASSWORD)) {
            val name = app.getString(source.labelRes)
            assertEquals(1, lines(name), "\"$name\" broke over more than one line")
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h548dp-xhdpi", fontScale = 1.5f)
    fun namesStayWholeAt308dpAndFontOneAndAHalf() = assertWholeNames()

    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun namesStayWholeAt308dpAndFontTwo() = assertWholeNames()

    /** The control: a 411dp phone at the default size keeps the names whole in the two-up grid. */
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", fontScale = 1.0f)
    fun namesStayWholeAtTheDefaultSize() = assertWholeNames()
}
