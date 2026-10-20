package com.localkeep.core.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.localkeep.core.ui.R
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The derivation overlay must scroll when its column is taller than the window (SCL-004).
 *
 * Under the API 29 root banner at 2.0 text the ring, the title, the KDF line and the Tier G
 * rationale add up to more than the window. The column was centred and did not scroll, so it
 * overflowed at both ends: "Reading the vault" lost its descenders at the top edge and the
 * rationale was never on screen. Asserted on a 360x560dp window at 2.0: the rationale can be
 * scrolled to and is then displayed, and the title is displayed at rest. The control is a roomy
 * 411x891 window at 1.0, where everything is on screen with no scroll at all.
 *
 * [GraphicsMode.Mode.NATIVE] for real text layout; `autoAdvance = false` because nothing on this
 * overlay needs the clock and the ring is not animated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LkDerivingScrollsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                LkDerivingOverlay(
                    kdfSummary = "3 passes, 64 MiB",
                    title = TITLE,
                    progress = 0.4f,
                    showRationale = true,
                )
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun rationale() = compose.activity.getString(R.string.core_ui_deriving_rationale)

    @Test
    @Config(qualifiers = "w360dp-h560dp-xhdpi", fontScale = 2.0f)
    fun `the rationale can be scrolled to and the title is whole when the column is taller than the window`() {
        show()
        // FAILS WITHOUT THE FIX: no scrollable ancestor, so `performScrollTo` throws.
        compose.onNodeWithText(rationale()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(TITLE).performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h560dp-xhdpi", fontScale = 2.0f)
    fun `at rest the column starts at the top, not centred past both edges`() {
        show()
        val title = compose.onNodeWithText(TITLE).fetchSemanticsNode()
        assertTrue(
            title.positionOnScreen.y >= 0f,
            "the title starts at y=${title.positionOnScreen.y}, above the window — its top is clipped",
        )
    }

    /** The control: a column that fits is exactly where it was and does not scroll. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a roomy window shows everything with nothing to scroll`() {
        show()
        compose.onNodeWithText(rationale()).assertIsDisplayed()
        compose.onNodeWithText(TITLE).assertIsDisplayed()
        // Absent counts as nothing to scroll, so the control reads the same before and after the fix.
        val max = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNodes()
            .firstOrNull()?.config?.get(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke() ?: 0f
        assertEquals(0f, max, "a column that fits must not have anything to scroll")
    }

    private companion object {
        const val TITLE = "Reading the vault"
    }
}
