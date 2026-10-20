package com.localkeep.core.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * R-L-06: a wrapping (`singleLine = false`) secret well holding a value that fits one line drew it
 * against the top of the well with an empty second line's worth of air below it, while its trailing
 * glyphs sat centred. The text now centres in the well when it is short and only grows the well
 * when it wraps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h548dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LkPasswordFieldWrappingWellAlignmentTest {

    @get:Rule
    val compose = createComposeRule()

    private fun centreOffsetDp(singleLine: Boolean): Float {
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.fillMaxWidth().testTag("well")) {
                    LkPasswordField(
                        value = "4111",
                        onValueChange = {},
                        revealed = true,
                        singleLine = singleLine,
                        leadingIcon = null,
                        actions = {
                            Box(Modifier.size(48.dp))
                            Box(Modifier.size(48.dp))
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
        val well = compose.onNodeWithTag("well").getUnclippedBoundsInRoot()
        val text = compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        val wellCentre = (well.top + well.bottom).value / 2
        val textCentre = (text.top + text.bottom).value / 2
        return kotlin.math.abs(wellCentre - textCentre)
    }

    @Test
    fun aShortValueInAWrappingWellIsVerticallyCentred() {
        val off = centreOffsetDp(singleLine = false)
        assertTrue(off < 2f, "the text sits ${off}dp off the well's centre")
    }

    /** Control: the single-line well was always centred, and must stay so. */
    @Test
    fun aSingleLineWellStaysCentred() {
        val off = centreOffsetDp(singleLine = true)
        assertTrue(off < 2f, "the text sits ${off}dp off the well's centre")
    }
}
