package com.localkeep.core.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The search well is the same height with and without the Clear button.
 *
 * The button carries a 48dp touch target and the well pads its content by 12dp above and below, so
 * the well grew from 48dp to 72dp when the first character was typed and the list under it jumped.
 * Four screens share the component (vault list, Settings, Trash, Health).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LkSearchFieldWellHeightTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `typing does not grow the well`() {
        var text by mutableStateOf("")
        compose.setContent {
            LocalKeepTheme {
                LkSearchField(value = text, onValueChange = { text = it }, modifier = Modifier.testTag("well"))
            }
        }
        compose.waitForIdle()
        val empty = compose.onNodeWithTag("well").fetchSemanticsNode().size.height

        text = "abc"
        compose.waitForIdle()
        val typed = compose.onNodeWithTag("well").fetchSemanticsNode().size.height

        assertEquals(empty, typed, "the well must not change height when the Clear button appears")
    }

    /** The control: the well is still the 48dp floor, so removing the padding did not shrink it. */
    @Test
    fun `the well keeps the 48dp touch floor`() {
        compose.setContent {
            LocalKeepTheme {
                LkSearchField(value = "", onValueChange = {}, modifier = Modifier.testTag("well"))
            }
        }
        compose.waitForIdle()
        val px = compose.onNodeWithTag("well").fetchSemanticsNode().size.height
        // xxhdpi = 3x.
        assertEquals(48 * 3, px)
    }
}
