package com.localkeep.core.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The folder row keeps a readable folder name by dropping "Change" under it when the pair stops
 * fitting (SCL-022).
 *
 * In German at 308dp and font scale 2.0 the weighted name was handed what the glyph and the unwrappable action
 * left in the 200dp field - about 31dp - and "Level 1" broke into fragments one or two letters wide (`I-14__P1`).
 *
 * FAILS WITHOUT THE FIX: the name is a column a few dp wide on the action's row.
 * CONTROL: 411dp at 1.0 keeps the action beside the name, the row the comp draws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LkFolderFieldStacksTest {

    @get:Rule
    val compose = createComposeRule()

    private fun measure(name: String, change: String, widthDp: Int): Pair<Float, Boolean> {
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.width(widthDp.dp)) {
                    LkFolderField(selectedName = name, onChange = {})
                }
            }
        }
        compose.waitForIdle()
        val density = compose.density.density
        val n = compose.onNodeWithText(name, useUnmergedTree = true).fetchSemanticsNode()
        val a = compose.onNodeWithText(change, useUnmergedTree = true).fetchSemanticsNode()
        val beside = a.positionOnScreen.y < n.positionOnScreen.y + n.size.height
        return n.size.width / density to beside
    }

    @Test
    @Config(qualifiers = "de-w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `at the 308dp floor and large type the name keeps a column`() {
        val (width, beside) = measure("Level 1", "Ordner ändern", widthDp = 200)
        assertTrue(width >= 60f, "the folder name is ${width}dp wide, fragments per line")
        assertTrue(!beside, "the action should drop under the name when the pair does not fit")
    }

    @Test
    @Config(qualifiers = "de-w411dp-h891dp-xxhdpi", fontScale = 1.0f)
    fun `a roomy window keeps the action beside the name`() {
        val (_, beside) = measure("Level 1", "Ordner ändern", widthDp = 359)
        assertTrue(beside, "with room to spare the action stays on the name's row")
    }
}
