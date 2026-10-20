package com.localkeep.feature.unlock

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.localkeep.core.ui.theme.LkDerivingOverlay
import com.localkeep.core.ui.theme.LkPrimaryButton
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
 * The derivation overlay covers the whole unlock window, pinned actions included (SCL-004 / S-L2).
 *
 * ### What was wrong
 *
 * The overlay was emitted inside the scroll region's `BoxWithConstraints`, which is the *content*
 * slot of `LkActionScaffold`. On a 308dp window at 2.0 text with the keyboard down, that region is
 * the ~523px above the pinned Unlock / Use Recovery Kit / footer block: the ring was cut at its
 * bottom edge, the caption below it was never on screen, and the buttons were drawn undimmed
 * underneath a scrim that did not reach them. It happened with the keyboard never raised, so it was
 * independent of the IME: the clip is the scaffold's own layout.
 *
 * ### What is asserted
 *
 * At 308x548dp, font scale 2.0, keyboard down: the overlay's bounds equal the root's, so the pinned
 * Unlock button is under it, and the caption and the rationale are reachable (on screen or by
 * scrolling the overlay itself). The same bounds hold on a roomy 411x891 window, and
 * the control there is that nothing scrolls.
 *
 * The overlay is tagged by a wrapper `Box` that takes whatever constraints the frame offers it, so
 * the measured bounds are the space the frame gave the real [LkDerivingOverlay] — which is exactly
 * what was wrong. `autoAdvance = false` because the scaffold's aura never goes idle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnlockOverlayCoversTheWholeFrameTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                UnlockFrame(
                    shortWindow = false,
                    scroll = rememberScrollState(),
                    hero = { Column { UnlockHero(subtitle = "Subtitle", compact = false) } },
                    entry = { Column {} },
                    actions = {
                        LkPrimaryButton(text = UNLOCK, onClick = {}, enabled = false)
                    },
                    overlay = {
                        Box(Modifier.fillMaxSize().testTag(OVERLAY)) {
                            LkDerivingOverlay(
                                kdfSummary = "3 passes, 64 MiB",
                                title = TITLE,
                                progress = 0.31f,
                                showRationale = true,
                            )
                        }
                    },
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun overlayScrollMax(): Float =
        compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
                hasAnyAncestor(hasTestTag(OVERLAY)),
        ).fetchSemanticsNodes()
            .firstOrNull()?.config?.get(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke() ?: 0f

    private fun bottom(n: SemanticsNode) = n.positionOnScreen.y + n.size.height

    private fun assertCoversFrame() {
        val root = compose.onRoot().fetchSemanticsNode()
        val overlay = compose.onNodeWithTag(OVERLAY).fetchSemanticsNode()
        val unlock = compose.onNodeWithText(UNLOCK).fetchSemanticsNode()
        // FAILS WITHOUT THE FIX: the overlay stopped where the pinned action bar starts.
        assertEquals(
            root.size.height,
            overlay.size.height,
            "the overlay is ${overlay.size.height}px tall in a ${root.size.height}px window: it is " +
                "clipped to the scroll region, so the pinned Unlock button (which starts at " +
                "${unlock.positionOnScreen.y}px) is drawn undimmed beneath it (SCL-004 / S-L2)",
        )
        assertEquals(
            root.positionOnScreen.y,
            overlay.positionOnScreen.y,
            "the overlay does not start at the window's top",
        )
        assertTrue(
            bottom(overlay) >= bottom(unlock),
            "the overlay ends at ${bottom(overlay)}px but Unlock ends at ${bottom(unlock)}px",
        )
    }

    @Test
    @Config(qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `at 308dp and 2_0 the overlay covers the pinned actions and its caption is reachable`() {
        show()
        assertCoversFrame()
        val overlay = compose.onNodeWithTag(OVERLAY).fetchSemanticsNode()
        val rationale = compose.onNodeWithText(
            compose.activity.getString(com.localkeep.core.ui.R.string.core_ui_deriving_rationale),
        ).fetchSemanticsNode()
        val title = compose.onNodeWithText(TITLE).fetchSemanticsNode()
        val reach = bottom(overlay) + overlayScrollMax()
        assertTrue(
            title.positionOnScreen.y >= overlay.positionOnScreen.y,
            "the caption starts above the overlay",
        )
        assertTrue(
            bottom(rationale) <= reach + 1f,
            "the rationale ends at ${bottom(rationale)}px, past what the overlay can scroll to ($reach px)",
        )
    }

    /** A roomy window is covered the same way: coverage is not a function of the window's size. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a roomy window is covered too`() {
        show()
        assertCoversFrame()
    }

    /** The control, green before and after: a column that fits shows whole and does not scroll. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a roomy window shows the whole overlay with nothing to scroll`() {
        show()
        compose.onNodeWithText(TITLE).assertIsDisplayed()
        compose.onNodeWithText(
            compose.activity.getString(com.localkeep.core.ui.R.string.core_ui_deriving_rationale),
        ).assertIsDisplayed()
        assertEquals(0f, overlayScrollMax(), "a column that fits must not scroll")
    }

    private companion object {
        const val OVERLAY = "unlock-overlay"
        const val TITLE = "Reading the vault"
        const val UNLOCK = "Unlock"
    }
}
