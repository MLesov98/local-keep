package com.localkeep.feature.vaultlist

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The offer to set a new master password after a Recovery Kit unlock shows the action it offers.
 *
 * The note was a sentence made tappable, with the action's name existing only as the click label
 * TalkBack reads — so a sighted user saw a message, not a button, and the one route to resetting
 * the password they had just proved they do not know went unused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h800dp-xhdpi")
class RecoveryKitNoteDrawsItsActionTest {

    @get:Rule
    val compose = createComposeRule()

    private var taps = 0

    private fun show() {
        taps = 0
        compose.setContent { LocalKeepTheme { RecoveryKitSetPasswordNote(onAction = { taps++ }) } }
        compose.waitForIdle()
    }

    private fun string(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    @Test
    fun theActionIsDrawnBesideTheSentence() {
        show()
        compose.onNodeWithText(string(R.string.vaultlist_recovery_kit_set_password_action), useUnmergedTree = true)
            .assertIsDisplayed()
        // Control: the sentence is still there too.
        compose.onNodeWithText(string(R.string.vaultlist_recovery_kit_set_password), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun tappingAnywhereOnTheBlockTakesTheOffer() {
        show()
        compose.onNodeWithTag("recovery-kit-set-password").performClick()
        assertEquals(1, taps)
        compose.onNodeWithText(string(R.string.vaultlist_recovery_kit_set_password_action), useUnmergedTree = true)
            .performClick()
        assertEquals(2, taps, "the drawn action must be part of the tap target, not a dead label")
    }
}
