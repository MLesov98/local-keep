package com.localkeep.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.ui.theme.LkEmptyState
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkSecondaryButton
import com.localkeep.core.ui.theme.LocalKeepTheme
import androidx.compose.ui.unit.dp
import com.localkeep.test.device.DeviceReadyRule
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The 96dp icon plate *looks* like a button — the 2026-07-29 hand-test tapped it on every empty
 * category and nothing happened. When the state has a primary action, the plate must run it too,
 * and must say so to a screen reader.
 */
@RunWith(AndroidJUnit4::class)
class LkEmptyStateTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theIconPlateRunsTheWiredActionAndAnnouncesIt() {
        var taps = 0
        compose.setContent {
            LocalKeepTheme {
                LkEmptyState(
                    icon = LkIcons.Plus,
                    title = "No logins yet",
                    body = "body",
                    onIconTap = { taps++ },
                    iconTapLabel = "Add a login",
                )
            }
        }
        compose.onNodeWithContentDescription("Add a login").performClick()
        assertEquals(1, taps)
    }

    @Test
    fun theIconPlateStaysInertWhenNoActionIsWired() {
        compose.setContent {
            LocalKeepTheme {
                LkEmptyState(icon = LkIcons.Shield, title = "t", body = "b")
            }
        }
        // A decorative plate that still advertised a click would be the same lie in reverse.
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun anUnwiredPlateIsNotClickableEvenWhenTheStateHasAnActionButton() {
        // The shape that caused three rounds of the same report: a state with a real action whose
        // plate is not the way to reach it (search results offering "Clear search"). The plate must
        // then be decoration — no click action at all — and the button carries the work. The plate's
        // *appearance* also drops its surface and ring in this case, which a semantics tree cannot
        // see; that half is verified on device.
        var buttonTaps = 0
        compose.setContent {
            LocalKeepTheme {
                LkEmptyState(
                    icon = LkIcons.Search,
                    title = "No matches",
                    body = "b",
                    action = {
                        LkSecondaryButton(text = "Clear search", onClick = { buttonTaps++ })
                    },
                )
            }
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNodeWithText("Clear search").performClick()
        assertEquals(1, buttonTaps)
    }

    /**
     * The panel's only control has to be on screen in a region too short to hold the plate as well.
     *
     * On the vault list, searching inside a folder for something that lives elsewhere puts the
     * whole state — "Nothing here matches …" and the "Search outside <Folder>" button that is the
     * only way out of that dead end — under the on-screen keyboard. Measured on the emulator at
     * density 3.5: the button sat at y=1681 against a keyboard edge at y=1580, and a tap at its own
     * reported position went to the keyboard, appending a character to the query. The 96dp plate
     * plus its gap is 399px of the 611px the panel had; without it everything fits, and the button
     * measured y=1382 afterwards. Found by the F03 QA persona, 2026-08-29.
     *
     * 180dp is chosen to be genuinely too short for the full panel, which the control below proves
     * rather than assumes — without that control a fixture that was quietly tall enough would pass
     * this whether or not [LkEmptyState] had a compact mode at all.
     */
    @Test
    fun theActionIsOnScreenInAShortRegionWhenCompact() {
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.fillMaxWidth().height(180.dp).clipToBounds()) {
                    LkEmptyState(
                        icon = LkIcons.Search,
                        title = "No matching items",
                        body = "Nothing here matches this, but 1 item elsewhere in your vault does.",
                        compact = true,
                        action = { LkSecondaryButton(text = "Search outside Work", onClick = {}) },
                    )
                }
            }
        }
        compose.onNodeWithText("Search outside Work").assertIsDisplayed()
    }

    /**
     * The control: the same region with the plate cannot show the button, which is the defect.
     *
     * `clipToBounds` is load-bearing — a `Box` does not clip, so without it the panel simply
     * overflows and every node still reports itself displayed, and this pair would pass whether or
     * not [LkEmptyState] had a compact mode.
     */
    @Test
    fun theActionFallsOffTheSameRegionWithThePlate() {
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.fillMaxWidth().height(180.dp).clipToBounds()) {
                    LkEmptyState(
                        icon = LkIcons.Search,
                        title = "No matching items",
                        body = "Nothing here matches this, but 1 item elsewhere in your vault does.",
                        action = { LkSecondaryButton(text = "Search outside Work", onClick = {}) },
                    )
                }
            }
        }
        compose.onNodeWithText("Search outside Work").assertIsNotDisplayed()
    }
}
