package com.localkeep.feature.unlock

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import com.localkeep.core.ui.theme.LocalKeepTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Why a full-looking phrase will not open is said in the pinned action bar, not under the field.
 *
 * The sentence used to be a child of the scrolling entry, which is the band the keyboard covers for
 * the whole task; a 24-word phrase failing its checksum showed an amber "24 of 24 words" over a
 * dead button and the reason was out of sight. [RecoveryWordProblem] is what the action bar now
 * hosts. The checksum case uses 24 x "abandon", which is well-formed words but not a kit (the real
 * 24-word all-zero entropy phrase ends in "art").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecoveryWordProblemTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(words: String) {
        compose.setContent { LocalKeepTheme { RecoveryWordProblem(words) } }
        compose.waitForIdle()
    }

    private val tag = "unlock-recovery-word-problem"

    @Test
    fun `a full phrase that fails its checksum is explained`() {
        show(List(24) { "abandon" }.joinToString(" "))
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    @Test
    fun `more than twenty-four words is explained`() {
        show(List(32) { "abandon" }.joinToString(" "))
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    /** Controls: a phrase still being typed, and a whole valid kit, have nothing to explain. */
    @Test
    fun `a phrase still being typed says nothing`() {
        show(List(5) { "abandon" }.joinToString(" "))
        compose.onNodeWithTag(tag).assertDoesNotExist()
    }

    @Test
    fun `a whole valid kit says nothing`() {
        show(List(23) { "abandon" }.joinToString(" ") + " art")
        compose.onNodeWithTag(tag).assertDoesNotExist()
    }
}
