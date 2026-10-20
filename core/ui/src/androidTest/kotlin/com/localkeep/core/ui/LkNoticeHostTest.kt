package com.localkeep.core.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.test.device.DeviceReadyRule
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device checks for the confirmation surface. The unit tests cover [LkNotices]' bookkeeping;
 * these cover the half that only exists once something is composed — that it actually renders, that
 * the Undo is reachable by a finger and by TalkBack, and that it takes itself down again.
 */
@RunWith(AndroidJUnit4::class)
class LkNoticeHostTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()

    @get:Rule
    val rule = createComposeRule()

    @Before
    @After
    fun reset() = LkNotices.clear()

    @Test
    fun aPostedConfirmationIsShown() {
        rule.mainClock.autoAdvance = false
        rule.setContent { LocalKeepTheme { LkNoticeHost() } }

        rule.runOnUiThread { LkNotices.post("Moved to trash") }
        rule.mainClock.advanceTimeBy(LkMotionSettleMs)

        rule.onNodeWithText("Moved to trash").assertIsDisplayed()
    }

    @Test
    fun theConfirmationIsAnnouncedPolitelyRatherThanInterrupting() {
        // A confirmation reports something that already succeeded. Assertive would talk over
        // whatever the screen reader is mid-sentence on, for news that can wait.
        rule.mainClock.autoAdvance = false
        rule.setContent { LocalKeepTheme { LkNoticeHost() } }

        rule.runOnUiThread { LkNotices.post("Item saved") }
        rule.mainClock.advanceTimeBy(LkMotionSettleMs)

        val mode = rule.onNodeWithText("Item saved", useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.LiveRegion)
        assertEquals(LiveRegionMode.Polite, mode, "the confirmation must announce politely")
    }

    @Test
    fun theUndoMeetsTheMinimumTouchTarget() {
        rule.mainClock.autoAdvance = false
        rule.setContent { LocalKeepTheme { LkNoticeHost() } }

        rule.runOnUiThread { LkNotices.post("Moved to trash", actionLabel = "Undo") {} }
        rule.mainClock.advanceTimeBy(LkMotionSettleMs)

        val floor = with(rule.density) { MinTouchTarget.toPx() }
        val bounds = rule.onNodeWithContentDescription("Undo", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            bounds.width >= floor && bounds.height >= floor,
            "\"Undo\" is ${bounds.width}x${bounds.height}px; the floor is ${floor}px square",
        )
    }

    @Test
    fun tappingUndoRunsItAndTakesTheOfferAway() {
        // Retiring the notice is part of the contract: an undo that leaves its own offer on screen
        // invites a second tap, and the second tap would undo the undo.
        var undone = 0
        rule.mainClock.autoAdvance = false
        rule.setContent { LocalKeepTheme { LkNoticeHost() } }

        rule.runOnUiThread { LkNotices.post("Moved to trash", actionLabel = "Undo") { undone++ } }
        rule.mainClock.advanceTimeBy(LkMotionSettleMs)
        rule.onNodeWithContentDescription("Undo", useUnmergedTree = true).performClick()
        rule.mainClock.advanceTimeBy(LkMotionSettleMs)

        assertEquals(1, undone)
        assertEquals(null, LkNotices.current.value)
    }

    @Test
    fun aConfirmationTakesItselfDownAgain() {
        rule.mainClock.autoAdvance = false
        rule.setContent { LocalKeepTheme { LkNoticeHost() } }

        rule.runOnUiThread { LkNotices.post("Item saved") }
        rule.mainClock.advanceTimeBy(LkMotionSettleMs)
        rule.onNodeWithText("Item saved").assertIsDisplayed()

        rule.mainClock.advanceTimeBy(LkNotices.SHORT_MS + LkMotionSettleMs)
        assertEquals(null, LkNotices.current.value, "the confirmation should have retired itself")
    }

    @Test
    fun nothingIsDrawnWhileThereIsNothingToSay() {
        rule.setContent { LocalKeepTheme { LkNoticeHost() } }
        rule.onNodeWithText("Moved to trash").assertDoesNotExist()
    }
}

/** Long enough for the enter/exit transition and the recomposition behind it to settle. */
private const val LkMotionSettleMs = 1_000L
