package com.localkeep.feature.settings

import androidx.compose.foundation.layout.Column
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Biometric unlock body does not end on a single word (D3-L-16): "replacement." sat alone on
 * the third line. The body is two short sentences, so it takes the balanced break; the ordinary
 * Settings body keeps the paragraph break.
 *
 * FAILS WITHOUT THE FIX: with `balanced = false` the last line is "replacement." alone at this width.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShortBodyHasNoOrphanTest {

    @get:Rule
    val compose = createComposeRule()

    private fun lastLine(balanced: Boolean, text: String): String {
        compose.setContent {
            LocalKeepTheme {
                Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp)) { Body(text, balanced) }
            }
        }
        compose.waitForIdle()
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val out = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        val layout = out.first()
        val last = layout.lineCount - 1
        return text.substring(layout.getLineStart(last), layout.getLineEnd(last, visibleEnd = true)).trim()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `balanced body does not end on one word`() {
        val last = lastLine(balanced = true, text = BODY)
        assertTrue(last.contains(' '), "last line is the single word '$last'")
    }

    private companion object {
        const val BODY =
            "Opens LocalKeep with biometrics instead of typing your master password. A convenience on top of it, never a replacement."
    }
}
