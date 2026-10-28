package com.localkeep.feature.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The File editor's closed "Folder, tags and more" row does not set its label one word to a line.
 * With the label in `weight(1f)` beside "none set" it took four lines in a sliver at 308dp and font
 * 2.0 (final sweep, 2026-10-28). It is a flow row now, as the item editor's is. NATIVE graphics, or
 * nothing wraps and this passes against the broken row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileMoreHeadlineWrapsWholeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun theLabelTakesAtMostTwoLinesAt308dpAndFontTwo() {
        val label = RuntimeEnvironment.getApplication().getString(R.string.files_edit_more_label)
        compose.setContent {
            LocalKeepTheme {
                // The row's real share of a 308dp window: the editor's margins and the row's padding.
                Box(Modifier.padding(horizontal = 46.dp)) {
                    Row(Modifier.fillMaxWidth()) { FileMoreHeadlineText(label, "none set") }
                }
            }
        }
        compose.waitForIdle()
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        val lines = results.single().lineCount
        assertTrue(lines <= 2, "\"$label\" took $lines lines")
    }
}
