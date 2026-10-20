package com.localkeep.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.ui.theme.LkChip
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.test.device.DeviceReadyRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A selected chip must stay a control.
 *
 * `Modifier.selectable` sets `SemanticsProperties.Selected`, and Compose maps that to
 * `info.isClickable = false` with no `ACTION_CLICK` — "a selected item cannot usefully be clicked"
 * is true of a tab and false of every chip in this app, all of which toggle. On device
 * (2026-08-10, API 36) a selected chip emitted no clickable node at all in the uiautomator tree:
 * once Favourites was on, a TalkBack user had no way to turn it off, because the only control that
 * could was no longer a control. Touch was unaffected, which is why a read-only sweep never saw it.
 *
 * Both assertions fail against `selectable`: the toggle action is absent, and there is no on/off
 * state to read.
 */
@RunWith(AndroidJUnit4::class)
class SelectedChipStaysOperableTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aSelectedChipCanStillBeToggledOff() {
        rule.setContent {
            LocalKeepTheme {
                var on by remember { mutableStateOf(true) }
                LkChip(text = "Favourites", selected = on, onClick = { on = !on })
            }
        }

        rule.onNodeWithText("Favourites").assertIsOn()
        rule.onNodeWithText("Favourites").performClick()
        rule.onNodeWithText("Favourites").assertIsOff()
    }

    @Test
    fun anUnselectedChipIsNotReportedAsOn() {
        rule.setContent {
            LocalKeepTheme {
                LkChip(text = "Logins", selected = false, onClick = {})
            }
        }

        rule.onNodeWithText("Logins").assertIsOff()
    }
}
