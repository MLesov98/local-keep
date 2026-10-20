package com.localkeep.feature.unlock

import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Use master password" lands the caret in the password well.
 *
 * Dismissing the biometric prompt closed it with focus nowhere and the keyboard down, so the user
 * who had just asked for the password field had to find and tap it. [FocusOnRequest] is the
 * mechanism the screen hangs on the well; [nextPasswordFocusRequests] decides when it fires.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PasswordFocusAfterBiometricChoiceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var requests by mutableIntStateOf(0)

    private fun show(initial: Int = 0) {
        requests = initial
        compose.setContent {
            val requester = remember { FocusRequester() }
            FocusOnRequest(requests, requester)
            BasicTextField(
                value = "",
                onValueChange = {},
                modifier = Modifier.focusRequester(requester).testTag("well"),
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun `a request puts focus in the well`() {
        show()
        compose.onNodeWithTag("well").assertIsNotFocused()
        compose.runOnIdle { requests += 1 }
        compose.waitForIdle()
        compose.onNodeWithTag("well").assertIsFocused()
    }

    /** Control: a count the screen was born with is history, not a request. */
    @Test
    fun `a count that was already there does not take focus`() {
        show(initial = 3)
        compose.onNodeWithTag("well").assertIsNotFocused()
    }

    @Test
    fun `only the user's own choice asks for the well`() {
        assertEquals(1, nextPasswordFocusRequests(0, userChoseAnotherWay = true))
        assertEquals(0, nextPasswordFocusRequests(0, userChoseAnotherWay = false), "a real failure must not steal focus")
    }
}
