package com.localkeep.feature.generator

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
 * The Random / Passphrase toggle keeps both words whole. Two equal halves left "Passphrase" too
 * little at 308dp and font 2.0, and it broke as "Passphras / e" (final sweep, 2026-10-28); the halves
 * now yield to the labels only when a label will not fit one. NATIVE graphics, or nothing wraps.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModeToggleKeepsLabelsWholeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun passphraseLines(): Int {
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.padding(horizontal = LkFrame.screenHorizontal)) {
                    ModeToggle(random = true, onSelect = {})
                }
            }
        }
        compose.waitForIdle()
        val label = RuntimeEnvironment.getApplication().getString(R.string.generator_mode_passphrase)
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single().lineCount
    }

    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun passphraseStaysOneLineAt308dpAndFontTwo() = assertEquals(1, passphraseLines())

    /** The control: the default size keeps it whole, as it always did. */
    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi", fontScale = 1.0f)
    fun passphraseStaysOneLineAtTheDefaultSize() = assertEquals(1, passphraseLines())
}
