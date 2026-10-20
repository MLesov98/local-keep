package com.localkeep.core.ui.theme

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [LkPasswordField.maskTransformation]: null keeps today's `•` mask; a value replaces it while masked
 * and is ignored once revealed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h548dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LkPasswordFieldMaskTransformationTest {

    @get:Rule
    val compose = createComposeRule()

    private object Stars : VisualTransformation {
        override fun filter(text: AnnotatedString) =
            TransformedText(AnnotatedString("*".repeat(text.length)), OffsetMapping.Identity)
    }

    private fun show(revealed: Boolean, mask: VisualTransformation?) {
        compose.setContent {
            LocalKeepTheme {
                if (mask == null) {
                    LkPasswordField("abc", {}, revealed = revealed)
                } else {
                    LkPasswordField("abc", {}, revealed = revealed, maskTransformation = mask)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun nullKeepsTheBulletMask() {
        show(revealed = false, mask = null)
        compose.onNodeWithText("•••", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aGivenTransformationReplacesTheMaskWhileMasked() {
        show(revealed = false, mask = Stars)
        compose.onNodeWithText("***", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aGivenTransformationIsIgnoredOnceRevealed() {
        show(revealed = true, mask = Stars)
        compose.onNodeWithText("abc", useUnmergedTree = true).assertExists()
    }

    // --- revealedTransformation (R-L-06) -------------------------------------------------------

    private object Upper : VisualTransformation {
        override fun filter(text: AnnotatedString) =
            TransformedText(AnnotatedString(text.text.uppercase()), OffsetMapping.Identity)
    }

    private fun showRevealedWith(revealed: Boolean, reveal: VisualTransformation?) {
        compose.setContent {
            LocalKeepTheme {
                if (reveal == null) {
                    LkPasswordField("abc", {}, revealed = revealed)
                } else {
                    LkPasswordField("abc", {}, revealed = revealed, revealedTransformation = reveal)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun revealedNullShowsThePlainValue() {
        showRevealedWith(revealed = true, reveal = null)
        compose.onNodeWithText("abc", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aRevealedTransformationShapesTheValueWhileRevealed() {
        showRevealedWith(revealed = true, reveal = Upper)
        compose.onNodeWithText("ABC", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aRevealedTransformationIsIgnoredWhileMasked() {
        showRevealedWith(revealed = false, reveal = Upper)
        compose.onNodeWithText("•••", useUnmergedTree = true).assertExists()
    }
}
