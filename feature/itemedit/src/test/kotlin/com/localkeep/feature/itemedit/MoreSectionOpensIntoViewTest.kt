package com.localkeep.feature.itemedit

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Opening "Folder, tags and more" brings what it opened into view.
 *
 * Device report C3 finding 5 (2026-10-04): on a login lengthened by a two-factor block, the row sat
 * at the bottom of the screen, and tapping it opened the panel *below* the fold — the only visible
 * change was a chevron turning. The tester toggled it four times before a long swipe showed what
 * had been opening all along.
 *
 * The harness is the editor's shape reduced to what matters: a scrolling column whose last row is
 * the disclosure, with the panel composed under it only while open — the `if (open)` that
 * `MoreSection` uses — and the requester held outside that branch, as `MoreSection` holds it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
class MoreSectionOpensIntoViewTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var scroll: ScrollState

    private fun show(startOpen: Boolean) {
        compose.setContent {
            LocalKeepTheme {
                scroll = rememberScrollState()
                var open by remember { mutableStateOf(startOpen) }
                val reveal = rememberOpenReveal(open)
                Column(Modifier.height(VIEWPORT).testTag(VIEWPORT_TAG).verticalScroll(scroll)) {
                    // The fields above the disclosure: everything a login with 2FA puts first.
                    Spacer(Modifier.fillMaxWidth().height(VIEWPORT - ROW + 2.dp))
                    Box(Modifier.fillMaxWidth().height(ROW).testTag(ROW_TAG).clickable { open = !open })
                    if (open) {
                        Box(Modifier.fillMaxWidth().height(PANEL).testTag(PANEL_TAG).bringIntoViewRequester(reveal))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `tapping the row scrolls the opened panel fully into view`() {
        show(startOpen = false)

        compose.onNodeWithTag(ROW_TAG).performClick()
        compose.waitForIdle()

        val viewport = compose.onNodeWithTag(VIEWPORT_TAG).fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag(PANEL_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(scroll.value > 0, "the form did not move, so the panel opened below the fold")
        assertTrue(
            panel.bottom <= viewport.bottom + 1f,
            "the panel's bottom ${panel.bottom} is past the viewport's ${viewport.bottom}",
        )
    }

    @Test
    fun `a section that is already open when the form appears does not pull the form down`() {
        // The control. Choosing a folder disposes the form and recomposes it with the section still
        // open, by design (see `moreOpen` in ItemEditScreen); the user returns to the top of the
        // item, not to a scroll they did not make.
        show(startOpen = true)

        assertEquals(0, scroll.value)
    }

    private companion object {
        val VIEWPORT = 400.dp
        val ROW = 48.dp
        val PANEL = 240.dp
        const val VIEWPORT_TAG = "viewport"
        const val ROW_TAG = "more-row"
        const val PANEL_TAG = "more-panel"
    }
}
