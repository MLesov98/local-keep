package com.localkeep.core.ui.theme

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performImeAction
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The name field's own keyboard key confirms, exactly as the button below it does.
 *
 * It was a Next key with nothing to move to, so typing a name and pressing the key at the end of the
 * keyboard did nothing visible. With a blank name the button refuses, and so does the key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LkNamingSheetImeActionTest {

    @get:Rule
    val compose = createComposeRule()

    private var confirms = 0

    private fun show(value: String) = compose.setContent {
        LocalKeepTheme {
            LkNamingSheet(
                title = "Name it",
                subtitle = "",
                fieldLabel = "Name",
                confirmLabel = "Save",
                value = value,
                onValueChange = {},
                onDismiss = {},
                onConfirm = { confirms++ },
            )
        }
    }

    @Test
    fun `the keyboard action confirms a name`() {
        show("Visa")
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performImeAction()
        assertEquals(1, confirms)
    }

    /** The control: a blank name is refused by the key as it is by the button. */
    @Test
    fun `the keyboard action does not confirm a blank name`() {
        show("")
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performImeAction()
        assertEquals(0, confirms)
    }
}
