package com.localkeep.core.ui.theme

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.ui.MinTouchTarget
import com.localkeep.test.device.DeviceReadyRule
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [LkInlineError] grew an optional action for the one error that leaves the user nothing to press:
 * a failed *read*, which renders an empty list. A failed write keeps its own control on screen, so
 * the action has to stay opt-in — a second button pointing at a control the user already has is a
 * longer route to the same place.
 */
@RunWith(AndroidJUnit4::class)
class LkInlineErrorActionTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()

    @get:Rule
    val rule = createComposeRule()

    private val message = "Couldn't read the vault, so this list may be incomplete."

    @Test
    fun theMessageShowsWithNoActionByDefault() {
        rule.setContent { LocalKeepTheme { LkInlineError(message) } }

        rule.onNodeWithText(message).assertIsDisplayed()
        rule.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun anActionAppearsOnlyWhenBothLabelAndHandlerAreGiven() {
        // Guards the call sites, which pass `"Retry".takeIf { loadFailed }` alongside
        // `viewModel::retryLoad.takeIf { loadFailed }` — two independently nullable values that
        // must never disagree into a dead control.
        rule.setContent {
            LocalKeepTheme {
                LkInlineError(message, actionLabel = "Retry", onAction = null)
            }
        }
        rule.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun theActionFires() {
        var retried = 0
        rule.setContent {
            LocalKeepTheme {
                LkInlineError(message, actionLabel = "Retry") { retried++ }
            }
        }

        rule.onNodeWithContentDescription("Retry").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun theActionMeetsTheMinimumTouchTarget() {
        rule.setContent {
            LocalKeepTheme {
                LkInlineError(message, actionLabel = "Retry") { }
            }
        }

        val floor = with(rule.density) { MinTouchTarget.toPx() }
        val bounds = rule.onNodeWithContentDescription("Retry", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            bounds.width >= floor && bounds.height >= floor,
            "\"Retry\" is ${bounds.width}x${bounds.height}px; the floor is ${floor}px square",
        )
    }

    @Test
    fun aLongMessageDoesNotSqueezeTheActionOffTheRow() {
        // The message takes weight(1f) so the action keeps its width. Without it the button was
        // whatever space a two-line error left over, which on a narrow screen is nothing.
        rule.setContent {
            LocalKeepTheme {
                LkInlineError(
                    "Couldn't read the vault, so this list may be incomplete. " +
                        "Some items may be missing until this succeeds.",
                    actionLabel = "Retry",
                ) { }
            }
        }

        val floor = with(rule.density) { MinTouchTarget.toPx() }
        val bounds = rule.onNodeWithContentDescription("Retry", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(bounds.width >= floor, "action collapsed to ${bounds.width}px")
    }
}
