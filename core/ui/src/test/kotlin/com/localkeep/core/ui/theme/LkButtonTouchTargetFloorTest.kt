package com.localkeep.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `LkSecondaryButton` and `LkTintButton` take a `height` that several real call sites override
 * below the 48dp floor — `VaultListScreen`'s empty-state actions at 40dp, `ItemEditScreen`'s paired
 * actions at 42dp, onboarding's secondary prompts at 46dp. Those numbers are the comp, not a bug:
 * the floor has to come from a *touch-target* node separate from the painted card (see the doc on
 * [LkSecondaryButton]), because coercing `height` itself would silently redraw every one of those
 * buttons taller than its design.
 *
 * At fontScale 1.0 the label is tall enough on some of these that a fixed-height reading of "is this
 * button 48dp" could pass by luck, the same trap `FontScaleTest`'s own header names for the 2.0f
 * direction. 0.85 — Android's smallest system font size — is the scale where the label is smallest
 * and can least be mistaken for what is holding the floor up, so it is the one that actually proves
 * `minTouchTarget()` on the outer node is doing the work rather than incidental label growth.
 *
 * ### Why Robolectric rather than androidTest
 *
 * Same reasoning as [LkDialogBoundsTest]: a measured height off one layout pass is geometry, not a
 * pixel, and needs no device — so it belongs where it runs on every push (`./gradlew test`) rather
 * than only when a phone or emulator is attached. `FontScaleTest` (`core/ui/src/androidTest`)
 * carries the same two cases as coverage for the on-device instrumented pass; this is the one that
 * actually gates a `testFossDebugUnitTest` run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LkButtonTouchTargetFloorTest {

    @get:Rule
    val compose = createComposeRule()

    /** Renders [content] and returns the tagged node's height in dp. */
    private fun targetHeight(content: @Composable () -> Unit): Float {
        compose.setContent { LocalKeepTheme { content() } }
        compose.waitForIdle()
        return compose.onNodeWithTag(TAG).fetchSemanticsNode().size.height / compose.density.density
    }

    @Test
    @Config(fontScale = 0.85f)
    fun secondaryButtonTouchTargetHoldsTheFloorAtTheSmallestFontScale() {
        val target = targetHeight {
            LkSecondaryButton(text = "Skip", onClick = {}, height = 40.dp, modifier = Modifier.testTag(TAG))
        }
        assertTrue(
            target >= 48f,
            "LkSecondaryButton's touch target was ${target}dp at fontScale 0.85 with height=40.dp — " +
                "below the 48dp floor. A short label at the smallest system font size cannot force " +
                "the card to grow, so the floor has to come from minTouchTarget() on the outer node.",
        )
    }

    @Test
    @Config(fontScale = 0.85f)
    fun tintButtonTouchTargetHoldsTheFloorAtTheSmallestFontScale() {
        val target = targetHeight {
            LkTintButton(
                text = "Copy",
                onClick = {},
                accent = LkTheme.colors.jade,
                height = 42.dp,
                modifier = Modifier.testTag(TAG),
            )
        }
        assertTrue(
            target >= 48f,
            "LkTintButton's touch target was ${target}dp at fontScale 0.85 with height=42.dp — below " +
                "the 48dp floor.",
        )
    }

    private companion object {
        const val TAG = "touch-target-floor-subject"
    }
}
