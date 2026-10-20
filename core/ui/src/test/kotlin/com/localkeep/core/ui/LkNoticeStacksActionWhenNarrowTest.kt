package com.localkeep.core.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A notice with an action keeps a readable message column by stacking the action under it (L-03).
 *
 * At 308dp and font scale 2.0 the glyph plate, the gaps and "Rückgängig" took everything but 99px,
 * and "1 Eintrag verschoben" was set one syllable per line over most of the list
 * (`scalefix/shots/SCL-007-de-10-dropped2.png`). The message is the point of a notice; the action
 * gives way.
 *
 * FAILS WITHOUT THE FIX: the message column is ~42dp wide and the action is on the message's row.
 * CONTROL: 411dp at 1.0 keeps the action beside the message, the plate the comps draw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LkNoticeStacksActionWhenNarrowTest {

    @get:Rule
    val compose = createComposeRule()

    @BeforeTest
    @AfterTest
    fun reset() = LkNotices.clear()

    private data class Geometry(val messageWidthDp: Float, val actionBesideMessage: Boolean)

    private fun measure(message: String, action: String): Geometry {
        compose.setContent { LocalKeepTheme { LkNoticeHost() } }
        LkNotices.post(message, action) {}
        compose.waitForIdle()
        val density = compose.density.density
        val m = compose.onNodeWithText(message).fetchSemanticsNode()
        val a = compose.onNodeWithText(action).fetchSemanticsNode()
        val besideMessage = a.positionOnScreen.y < m.positionOnScreen.y + m.size.height
        return Geometry(m.size.width / density, besideMessage)
    }

    @Test
    @Config(qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `at the 308dp floor and large type the message keeps a column and the action goes under it`() {
        val g = measure("1 Eintrag verschoben", "Rückgängig")
        assertTrue(g.messageWidthDp >= 120f, "the message column is ${g.messageWidthDp}dp wide, a syllable per line")
        assertTrue(!g.actionBesideMessage, "the action should sit under the message when the row is this narrow")
    }

    @Test
    @Config(qualifiers = "w308dp-h548dp-xhdpi", fontScale = 1.0f)
    fun `the 308dp floor stacks at ordinary type too`() {
        val g = measure("1 Eintrag verschoben", "Rückgängig")
        assertTrue(!g.actionBesideMessage, "a row this narrow stacks at any font scale")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi", fontScale = 1.0f)
    fun `a roomy window keeps the action beside the message`() {
        val g = measure("Moved to trash", "Undo")
        assertTrue(g.actionBesideMessage, "with room to spare the action stays on the message's row")
    }
}
