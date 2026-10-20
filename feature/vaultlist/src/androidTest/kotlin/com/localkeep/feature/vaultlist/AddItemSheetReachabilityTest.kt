package com.localkeep.feature.vaultlist

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.ui.theme.LocalKeepTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every item type the sheet offers is on screen when the sheet opens.
 *
 * Reported from a phone, 2026-08-19, as *"clicking + → 'Location' doesn't open location"*. The row
 * opened perfectly well when tapped; it was never on screen to tap. `LkSheet` opened at Material's
 * half-height detent, which fitted four of nine rows and cut the fifth flush with the display edge
 * — and a row clipped mid-height reads as a rendering edge, not as an invitation to scroll. File,
 * Location, Card and Identity were, to a user, absent.
 *
 * This is the third time the sheet has outgrown its container. The first fix made it scroll; the
 * list has grown from six rows to nine since. So the assertion here is deliberately *displayed*,
 * not *exists*: reachable-after-a-gesture-nobody-knows-to-make is the exact failure being pinned.
 *
 * Both ends of the list, because the defect was one-sided. `add-type-login` proves the sheet is
 * really drawn — an assertion that only looked at the bottom could pass on an empty screen.
 */
@RunWith(AndroidJUnit4::class)
class AddItemSheetReachabilityTest {

    @get:Rule
    val compose = createComposeRule()

    private fun openSheet() {
        compose.setContent {
            LocalKeepTheme {
                AddItemSheet(isPro = true, onSelect = {}, onDismiss = {}, onNewFolder = {})
            }
        }
    }

    @Test
    fun everyTypeRowIsOnScreenWithoutScrolling() {
        openSheet()

        compose.onNodeWithTag("add-type-login").assertIsDisplayed()
        compose.onNodeWithTag("add-type-coordinate").assertIsDisplayed()
        compose.onNodeWithTag("add-type-identity").assertIsDisplayed()
    }
}
