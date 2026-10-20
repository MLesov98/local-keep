package com.localkeep.feature.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Creating the vault takes focus, and the keyboard with it, out of the way of the overlay.
 *
 * Go on the confirm field submits the form while the field keeps focus, so the IME stayed up over
 * the lower half of the screen where "Creating your vault" explains the wait.
 *
 * Observed through the two services the composable talks to rather than through a field's
 * `Focused` flag: under Robolectric the host view re-takes Compose focus the moment it is cleared,
 * so even a direct `clearFocus(force = true)` reads as still focused and the flag cannot tell a
 * working effect from a missing one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReleaseFocusWhileWorkingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val focus = mock<FocusManager>()
    private val keyboard = mock<SoftwareKeyboardController>()
    private var working by mutableStateOf(false)

    private fun show() {
        working = false
        compose.setContent {
            CompositionLocalProvider(
                LocalFocusManager provides focus,
                LocalSoftwareKeyboardController provides keyboard,
            ) { ReleaseFocusWhileWorking(working) }
        }
        compose.waitForIdle()
    }

    @Test
    fun `focus and the keyboard are released when the wait begins`() {
        show()
        compose.runOnIdle { working = true }
        compose.waitForIdle()
        verify(focus).clearFocus(any())
        verify(keyboard).hide()
    }

    /** Control: an idle screen leaves the field and the keyboard alone. */
    @Test
    fun `nothing is released while nothing is working`() {
        show()
        verify(focus, never()).clearFocus(any())
        verify(keyboard, never()).hide()
    }
}
