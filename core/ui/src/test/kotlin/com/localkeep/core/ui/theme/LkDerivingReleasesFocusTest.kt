package com.localkeep.core.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Raising the derivation overlay drops focus, and with it the keyboard (SCL-004).
 *
 * The overlay is a [androidx.compose.foundation.layout.fillMaxSize] box inside whatever the screen
 * leaves above the keyboard. Unlock's Go key submits with the field still focused, so the overlay
 * was caught with the keyboard up on a 308dp window: its ring cut at the keyboard's edge and the
 * caption off screen. Nothing is editable while it is up, so it releases focus itself — the rule
 * onboarding's `ReleaseFocusWhileWorking` applied to one screen, now held by every caller.
 *
 * Robolectric has no IME, so what is asserted is the focus half; `keyboard.hide()` follows it in
 * the same effect.
 *
 * FAILS WITHOUT THE FIX: the field is still focused with the overlay on top.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w308dp-h548dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LkDerivingReleasesFocusTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun run(showOverlay: Boolean): Unit {
        compose.mainClock.autoAdvance = false
        val requester = FocusRequester()
        var working by mutableStateOf(false)
        compose.setContent {
            LocalKeepTheme {
                BasicTextField(
                    value = "",
                    onValueChange = {},
                    modifier = Modifier.testTag(FIELD).focusRequester(requester),
                )
                if (working) LkDerivingOverlay(title = "Opening", progress = 0.1f)
            }
        }
        compose.runOnUiThread { requester.requestFocus() }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag(FIELD).assertIsFocused()
        working = showOverlay
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        if (showOverlay) compose.onNodeWithTag(FIELD).assertIsNotFocused() else compose.onNodeWithTag(FIELD).assertIsFocused()
    }

    @Test
    fun `the overlay takes focus off the field`() = run(showOverlay = true)

    /** The control: with no overlay up the field keeps focus, so the test cannot pass by never focusing. */
    @Test
    fun `without the overlay the field keeps focus`() = run(showOverlay = false)

    private companion object {
        const val FIELD = "field"
    }
}
