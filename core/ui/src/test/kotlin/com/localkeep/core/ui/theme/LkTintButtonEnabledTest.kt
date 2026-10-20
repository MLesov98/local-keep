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
 * [LkTintButton] must be able to refuse a tap, and say so — the same two properties
 * [LkTextButtonEnabledTest] pins on [LkTextButton], for the same reason.
 *
 * This was the last button in `LkButtons.kt` with no `enabled` parameter, and it is the one the
 * destructive actions are drawn with. `BackupSettingsScreen`'s Replace — the only control in the
 * app that discards a vault — was therefore the one control on that screen not gated on
 * `isWorking`, contradicting the screen's own comment that "every action button on this screen is
 * already gated". Its confirmation opened during an in-flight import or restore, and the confirmed
 * tap then died at `confirmImport`'s bare `isWorking` return, which writes neither a status nor an
 * error: the other operation's completion notice stayed on screen as the apparent answer.
 *
 * Two properties, because a colour-only "disabled" satisfies neither: the tap must not reach
 * `onClick`, and a screen reader must be told the control is unavailable rather than reading a
 * live button whose label happens to be faint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LkTintButtonEnabledTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aDisabledTintButtonTakesNoTap() {
        var clicks = 0
        compose.setContent {
            LocalKeepTheme {
                LkTintButton(
                    text = "Replace",
                    onClick = { clicks++ },
                    accent = LkTheme.colors.coral,
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
            "LkTintButton fired onClick while disabled — the destructive-action weight cannot " +
                "refuse a tap, so a caller with an operation in flight has no way to stop one",
        )
    }

    @Test
    fun aDisabledTintButtonIsAnnouncedAsUnavailable() {
        compose.setContent {
            LocalKeepTheme {
                LkTintButton(
                    text = "Replace",
                    onClick = {},
                    accent = LkTheme.colors.coral,
                    enabled = false,
                    modifier = Modifier.testTag(TAG),
                )
            }
        }
        compose.onNodeWithTag(TAG).assertIsNotEnabled()
    }

    @Test
    fun anEnabledTintButtonStillClicks() {
        var clicks = 0
        compose.setContent {
            LocalKeepTheme {
                LkTintButton(
                    text = "Replace",
                    onClick = { clicks++ },
                    accent = LkTheme.colors.coral,
                    modifier = Modifier.testTag(TAG),
                )
            }
        }
        compose.onNodeWithTag(TAG).performClick()
        compose.waitForIdle()
        assertEquals(1, clicks, "the default must stay live — `enabled` defaults to true")
    }

    private companion object {
        const val TAG = "lk-tint-button"
    }
}
