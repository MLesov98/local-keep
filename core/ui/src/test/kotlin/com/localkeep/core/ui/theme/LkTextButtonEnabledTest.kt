package com.localkeep.core.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [LkTextButton] must be able to refuse a tap, and say so.
 *
 * Every other button in this file already can — [LkPrimaryButton], [LkSecondaryButton] and
 * [LkIconButton] each take `enabled` and pass it to their `clickable`. This one did not, and a
 * caller with an in-flight operation had only one lever left: paint the label faint and leave the
 * control live. `HiddenItemsScreen`'s "Make visible" did exactly that, so during a mutation the
 * button looked unavailable, took the tap anyway, and the ViewModel's own `isWorking` guard dropped
 * it while the banner area was deliberately empty — a press that produced nothing at all.
 *
 * Two properties, because a colour-only "disabled" satisfies neither: the tap must not reach
 * `onClick`, and a screen reader must be told the control is unavailable rather than reading a
 * live button whose label happens to be grey.
 *
 * Robolectric for the same reason as [LkButtonTouchTargetFloorTest] — this is composition and
 * semantics, not pixels, so it belongs in the suite that gates every push.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LkTextButtonEnabledTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aDisabledTextButtonSwallowsNothingBecauseItTakesNoTap() {
        var clicks = 0
        compose.setContent {
            LocalKeepTheme {
                LkTextButton(
                    text = "Make visible",
                    onClick = { clicks++ },
                    enabled = false,
                    modifier = Modifier.testTag(TAG),
                )
            }
        }
        compose.onNodeWithTag(TAG).performClick()
        compose.waitForIdle()
        assertEquals(
            0,
            clicks,
            "LkTextButton fired onClick while disabled — a caller that paints it unavailable has " +
                "no way to stop the tap, and the press dies silently in the caller's own guard",
        )
    }

    @Test
    fun aDisabledTextButtonIsAnnouncedAsUnavailable() {
        compose.setContent {
            LocalKeepTheme {
                LkTextButton(
                    text = "Make visible",
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.testTag(TAG),
                )
            }
        }
        compose.onNodeWithTag(TAG).assertIsNotEnabled()
    }

    @Test
    fun anEnabledTextButtonStillClicks() {
        var clicks = 0
        compose.setContent {
            LocalKeepTheme {
                LkTextButton(
                    text = "Make visible",
                    onClick = { clicks++ },
                    modifier = Modifier.testTag(TAG),
                )
            }
        }
        compose.onNodeWithTag(TAG).performClick()
        compose.waitForIdle()
        assertEquals(1, clicks, "the default must stay live — `enabled` defaults to true")
    }

    private companion object {
        const val TAG = "lk-text-button"
    }
}
