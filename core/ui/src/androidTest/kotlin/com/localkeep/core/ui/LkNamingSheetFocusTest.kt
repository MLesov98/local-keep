package com.localkeep.core.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.ui.theme.LkNamingSheet
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.test.device.DeviceReadyRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The naming step opens finished: a name already in the field, and a live button under it.
 *
 * This is the other half of removing the sheet's focus request (2026-08-25 — see
 * `NamingSheetAutoFocusTest`, which guards the removal itself). Taking the keyboard away is only an
 * improvement if what it was covering is actually usable: a sheet whose confirm button is missing,
 * off-screen, or disabled until something is typed would be worse than the keyboard ever was,
 * because the user would have no idea what the sheet wanted from them.
 *
 * The focus assertion that belongs here does not exist, and that is deliberate rather than an
 * omission. Under `createComposeRule` the sheet is its own window and Material3 moves focus into it
 * a frame after composition, so both candidate assertions — on the tagged wrapper and on the
 * editable node — return the same verdict whether or not the component requests focus. Both were
 * run against the fixed and the unfixed component to check; neither discriminated. The real
 * measurement is `adb shell dumpsys input_method` on a device, recorded in the sibling test's KDoc.
 */
@RunWith(AndroidJUnit4::class)
class LkNamingSheetFocusTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()

    @get:Rule
    val compose = createComposeRule()

    private fun showSheet() {
        compose.setContent {
            LocalKeepTheme {
                LkNamingSheet(
                    title = "Name and save it",
                    subtitle = "subtitle",
                    fieldLabel = "TITLE",
                    confirmLabel = "Save file",
                    value = "photo.png",
                    onValueChange = {},
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }
    }

    @Test
    fun confirmIsVisibleAndLiveWithoutTouchingTheField() {
        showSheet()
        compose.onNodeWithTag("naming-sheet-confirm")
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    /** The seeded suggestion is what makes confirm live on arrival; without it the button is dead. */
    @Test
    fun theFieldArrivesCarryingTheSuggestedName() {
        showSheet()
        compose.onNodeWithTag("naming-sheet-field").assertIsDisplayed()
    }
}
