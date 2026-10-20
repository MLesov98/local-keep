package com.localkeep.core.ui.theme

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [LkDisclosure] folds its body away, keeps its summary on show, and says which state it is in.
 *
 * The body leaving composition (rather than being clipped to zero height) is the property the Backup
 * screen leans on: nothing inside a folded section can be focused, read by a screen reader, or hold
 * state, so a section folded over a half-typed field is genuinely out of the way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LkDisclosureTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(
        initiallyOpen: Boolean,
        locked: Boolean = false,
        onChange: (Boolean) -> Unit = {},
    ) {
        compose.setContent {
            LocalKeepTheme {
                var open by remember { mutableStateOf(initiallyOpen) }
                LkDisclosure(
                    title = "Section title",
                    summary = "Section summary",
                    open = open,
                    locked = locked,
                    onOpenChange = {
                        open = it
                        onChange(it)
                    },
                ) {
                    Text("Section body")
                }
            }
        }
    }

    @Test
    fun `a folded section shows its title and summary and none of its body`() {
        show(initiallyOpen = false)

        compose.onNodeWithText("Section title").assertIsDisplayed()
        compose.onNodeWithText("Section summary").assertIsDisplayed()
        compose.onNodeWithText("Section body").assertDoesNotExist()
    }

    @Test
    fun `tapping the header opens it and tapping again folds it`() {
        val changes = mutableListOf<Boolean>()
        show(initiallyOpen = false, onChange = { changes += it })

        compose.onNodeWithText("Section title").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Section body").assertIsDisplayed()

        compose.onNodeWithText("Section title").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Section body").assertDoesNotExist()

        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun `the header names its state to a screen reader`() {
        show(initiallyOpen = false)

        val folded = compose.onNodeWithText("Section title").fetchSemanticsNode()
        val foldedState = folded.config[SemanticsProperties.StateDescription]
        assertTrue(foldedState.isNotBlank(), "a folded section must say it is folded")

        compose.onNodeWithText("Section title").performClick()
        compose.mainClock.advanceTimeBy(1_000)

        val open = compose.onNodeWithText("Section title").fetchSemanticsNode()
        val openState = open.config[SemanticsProperties.StateDescription]
        assertTrue(openState.isNotBlank() && openState != foldedState, "the state word must change with the section")
    }

    @Test
    fun `a locked section ignores taps and offers no action`() {
        val changes = mutableListOf<Boolean>()
        show(initiallyOpen = true, locked = true, onChange = { changes += it })

        compose.onNodeWithText("Section body").assertIsDisplayed()
        val node = compose.onNodeWithText("Section title").fetchSemanticsNode()
        assertFalse(
            SemanticsActions.OnClick in node.config,
            "a locked header must not advertise a tap that does nothing",
        )
        compose.onNodeWithText("Section title").performClick()
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText("Section body").assertIsDisplayed()
        assertTrue(changes.isEmpty(), "a locked section reported a change: $changes")
    }
}
