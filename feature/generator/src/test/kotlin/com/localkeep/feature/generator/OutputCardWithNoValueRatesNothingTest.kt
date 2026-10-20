package com.localkeep.feature.generator

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.ui.theme.LkStrength
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * With nothing generated, the card rates nothing and announces nothing.
 *
 * S3-F-16 (functional pass, 2026-10-15): with all four character classes off the card showed one
 * coral dash and "Weak · 0 bits" beside an active-looking dice, and its live-region node kept saying
 * "New password generated, 0 characters". There is no password to rate, so the meter has no subject,
 * and nothing was generated, so there is nothing to announce.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OutputCardWithNoValueRatesNothingTest {

    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun strengthWord(bits: Int) = app.getString(LkStrength.ofBits(bits).label)
    private fun generatedAnnouncement(chars: Int) =
        app.resources.getQuantityString(R.plurals.generator_announce_generated, chars, chars)

    private var value by mutableStateOf("Xk7!pQ2#vLm9")
    private var bits by mutableStateOf(72)

    private fun show() {
        compose.setContent {
            LocalKeepTheme {
                OutputCard(value = value, entropyBits = bits, onRegenerate = {}, onCopy = {})
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun clearGenerated() {
        value = ""
        bits = 0
        compose.waitForIdle()
    }

    @Test
    fun `an empty card shows no strength verdict`() {
        show()
        compose.onNodeWithContentDescription(app.getString(R.string.generator_regenerate)).performClick()
        compose.waitForIdle()
        compose.onNode(hasContentDescription(generatedAnnouncement(12))).assertExists()
        assertEquals(
            1,
            compose.onAllNodesWithTextCount(strengthWord(72)),
            "the control: a generated value is rated",
        )

        clearGenerated()

        assertEquals(
            0,
            compose.onAllNodesWithTextCount(strengthWord(0)),
            "a non-existent password was rated '${strengthWord(0)}'",
        )
    }

    @Test
    fun `an empty card announces nothing about a generated password`() {
        show()
        compose.onNodeWithContentDescription(app.getString(R.string.generator_regenerate)).performClick()
        compose.waitForIdle()

        clearGenerated()

        compose.onNode(hasContentDescription(generatedAnnouncement(0))).assertDoesNotExist()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
