package com.localkeep.feature.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every secret well Settings builds has a show/hide eye, whether or not its caller owns the flag.
 *
 * S10-F-14 (functional pass, 2026-10-15): the duress password, its confirmation and the
 * vault-password re-auth well were built through [PasswordField] without a toggle, so the one
 * secret the user is *creating* and cannot check afterwards was typed blind, beside a change-password
 * form whose three wells all had an eye. The helper now falls back to a flag of its own.
 *
 * Composed for real: this module declares the Compose UI test dependency, so the toggle is looked up
 * by its accessibility name and operated, rather than searched for in the source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsPasswordWellsHaveAnEyeTest {

    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val show get() = app.getString(com.localkeep.core.ui.R.string.core_ui_field_password_show)
    private val hide get() = app.getString(com.localkeep.core.ui.R.string.core_ui_field_password_hide)

    private fun editable(): String =
        compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test
    fun `a well built without a toggle still offers one, and it unmasks`() {
        compose.setContent {
            LocalKeepTheme {
                var value by remember { mutableStateOf("") }
                PasswordField(value = value, onValueChange = { value = it }, label = "Duress password")
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("correct-horse-battery")
        compose.waitForIdle()
        assertTrue(
            editable().all { it == '•' },
            "the control: the well starts masked, so an unmasked read below means the toggle worked",
        )

        compose.onNodeWithContentDescription(show).performClick()
        compose.waitForIdle()

        assertEquals("correct-horse-battery", editable())
        compose.onNodeWithContentDescription(hide).assertExists()
    }

    @Test
    fun `a caller that owns the flag keeps owning it`() {
        var toggles = 0
        compose.setContent {
            LocalKeepTheme {
                PasswordField(
                    value = "abc",
                    onValueChange = {},
                    label = "Current password",
                    revealed = false,
                    onToggleReveal = { toggles++ },
                )
            }
        }

        compose.onNodeWithContentDescription(show).performClick()
        compose.waitForIdle()

        assertEquals(1, toggles, "the caller's toggle was not the one invoked")
        // Its flag says masked and it did not flip it, so the helper must not have unmasked on its own.
        compose.onNodeWithContentDescription(show).assertExists()
    }
}
