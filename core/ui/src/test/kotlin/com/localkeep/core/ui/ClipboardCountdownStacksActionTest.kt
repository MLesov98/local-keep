package com.localkeep.core.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.AfterTest
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The clipboard countdown keeps a readable title by dropping "Jetzt löschen" under it when the row is
 * narrow (SCL-016).
 *
 * At 308dp and font scale 2.0 in German, "Jetzt löschen" took the room beside the text and the title
 * was stacked one letter per line, the toast filling most of the window
 * (`item-detail__P1-de`). The title is the point of the toast; the action gives way, as it does in
 * the notice host.
 *
 * FAILS WITHOUT THE FIX: the title column is a few dp wide and the action is on the title's row.
 * CONTROL: 411dp at 1.0 keeps the action beside the text, the layout the comps draw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClipboardCountdownStacksActionTest {

    @get:Rule
    val compose = createComposeRule()

    @AfterTest
    fun reset() = ClipboardHelper.clearNow(RuntimeEnvironment.getApplication())

    private data class Geometry(val titleWidthDp: Float, val titleHeightDp: Float, val actionBesideTitle: Boolean)

    private fun measure(): Geometry {
        compose.setContent { LocalKeepTheme { ClipboardCountdown() } }
        ClipboardHelper.copy(RuntimeEnvironment.getApplication(), "Passwort", "not-a-real-secret")
        compose.waitForIdle()
        val density = compose.density.density
        val title = compose.onNodeWithText("Passwort kopiert").fetchSemanticsNode()
        val action = compose.onNodeWithText("Jetzt löschen").fetchSemanticsNode()
        val beside = action.positionOnScreen.y < title.positionOnScreen.y + title.size.height
        return Geometry(title.size.width / density, title.size.height / density, beside)
    }

    @Test
    @Config(qualifiers = "de-w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `at the 308dp floor and large type the title keeps its column and the action goes under it`() {
        val g = measure()
        assertTrue(g.titleWidthDp >= 100f, "the title column is ${g.titleWidthDp}dp wide, a few letters per line")
        assertTrue(!g.actionBesideTitle, "the action should sit under the text when the row is this narrow")
    }

    @Test
    @Config(qualifiers = "de-w411dp-h891dp-xxhdpi", fontScale = 1.0f)
    fun `a roomy window keeps the action beside the text`() {
        val g = measure()
        assertTrue(g.actionBesideTitle, "with room to spare the action stays on the text's row")
    }
}
