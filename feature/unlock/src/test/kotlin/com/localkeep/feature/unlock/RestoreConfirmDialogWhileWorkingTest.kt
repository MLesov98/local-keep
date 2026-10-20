package com.localkeep.feature.unlock

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
 * The confirmation for the pre-unlock restore must not offer a cancel it cannot honour.
 *
 * `UnlockViewModel.confirmRestore` sets `isWorking` and then *launches*; the phase leaves `CONFIRM`
 * only after the quarantine copy and `restoreVaultFile` have both completed. The screen renders this
 * dialog on `phase == CONFIRM`, so for the whole of an irreversible vault replacement the dialog was
 * still up with a live Cancel, a live Back and a live outside-tap — all wired to
 * `cancelRestoreConfirm`, which only flips the phase back and drops the proof. The write finished
 * anyway, so the dialog closed as though the replacement had been called off and the screen then
 * said "Vault restored" over a vault that had in fact been replaced.
 *
 * The cancel is withdrawn rather than made to abort: a half-written vault is worse than a completed
 * one. What is asserted here is only that no dismiss is *offered* while the write is in flight, and
 * that the dialog itself stays on screen.
 *
 * Rendered directly rather than through `PreUnlockRestoreScreen`, which takes a `UnlockViewModel`
 * (Hilt, five Android collaborators); the decision under test — which controls does this dialog put
 * on screen for a given `isWorking` — needs none of them. Same harness as
 * `:feature:vaultlist`'s `RenameFolderDialogErrorTest`.
 */
@RunWith(RobolectricTestRunner::class)
// Tall window: Robolectric's default (320x470px) is small enough that a dialog's stacked actions
// scroll out of the viewport, and assertIsDisplayed() fails on a node that exists but is scrolled
// away — which would mask the result of the control below.
@Config(sdk = [34], qualifiers = "w411dp-h1600dp-xhdpi")
class RestoreConfirmDialogWhileWorkingTest {

    @get:Rule
    val compose = createComposeRule()

    private var cancels = 0
    private var confirms = 0

    private fun show(isWorking: Boolean) {
        cancels = 0
        confirms = 0
        compose.setContent {
            LocalKeepTheme {
                RestoreConfirmDialog(
                    isWorking = isWorking,
                    onCancel = { cancels++ },
                    onConfirm = { confirms++ },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun string(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    @Test
    fun `no dismiss is offered while the replacement is being written`() {
        show(isWorking = true)
        compose.onNodeWithTag("dialog-dismiss").assertDoesNotExist()
        // And the dialog has not gone anywhere: the user is held on the question they answered,
        // not returned to a screen that implies the replacement was called off.
        compose.onNodeWithText(string(R.string.unlock_restore_confirm_title)).assertIsDisplayed()
        assertEquals(0, cancels)
    }

    @Test
    fun `the confirm cannot be pressed a second time while the replacement is being written`() {
        show(isWorking = true)
        compose.onNodeWithTag("dialog-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("dialog-confirm").performClick()
        assertEquals(0, confirms)
    }

    /**
     * The control. Must stay green: before the write starts the dialog is an ordinary question with
     * a working Cancel, so a change that simply deleted the dismiss label would fail here rather
     * than passing both tests by removing the feature.
     */
    @Test
    fun `Cancel is offered and reaches the view-model before the replacement starts`() {
        show(isWorking = false)
        compose.onNodeWithTag("dialog-dismiss").assertIsDisplayed().performClick()
        assertEquals(1, cancels)
    }

    /** The second half of the control: the confirm is live until it is pressed. */
    @Test
    fun `the confirm reaches the view-model before the replacement starts`() {
        show(isWorking = false)
        compose.onNodeWithTag("dialog-confirm").assertIsDisplayed().performClick()
        assertEquals(1, confirms)
    }
}
