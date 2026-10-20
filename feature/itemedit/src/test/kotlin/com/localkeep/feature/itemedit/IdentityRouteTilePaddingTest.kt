package com.localkeep.feature.itemedit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.height
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LkIcons
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
 * SCL-030: an identity route tile keeps air above its title and below its subtitle at large text.
 *
 * The tile is `heightIn(min = 66.dp)` with horizontal padding only, so once the two lines outgrew 66dp
 * (2.0 text on a 308dp window) the text column filled the tile edge to edge: title and subtitle
 * touched the ring. Vertical padding goes on the row; at ordinary sizes the 66dp floor still wins,
 * so the tile does not change height there.
 *
 * Bounds, not a screenshot; NATIVE graphics so the text measures at a real height.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IdentityRouteTilePaddingTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show() {
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.fillMaxWidth().padding(horizontal = LkFrame.horizontal)) {
                    IdentityRouteTile(
                        icon = LkIcons.Camera,
                        title = TITLE,
                        primary = true,
                        onClick = {},
                        subtitle = SUBTITLE,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `at 2_0 the text does not touch the tile edges`() {
        show()
        val tile = compose.onNodeWithContentDescription(TILE).getBoundsInRoot()
        val title = compose.onNodeWithText(TITLE, useUnmergedTree = true).getBoundsInRoot()
        val subtitle = compose.onNodeWithText(SUBTITLE, useUnmergedTree = true).getBoundsInRoot()
        assertTrue(tile.height.value > 66f, "the tile must really outgrow its 66dp floor here; it is ${tile.height}")
        assertTrue(
            title.top.value - tile.top.value >= 8f,
            "the title starts ${title.top.value - tile.top.value}dp below the tile's top edge",
        )
        assertTrue(
            tile.bottom.value - subtitle.bottom.value >= 8f,
            "the subtitle ends ${tile.bottom.value - subtitle.bottom.value}dp above the tile's bottom edge",
        )
    }

    /** The control: at ordinary text the 66dp floor still decides the height. */
    @Test
    @Config(qualifiers = "w411dp-h800dp-xxhdpi")
    fun `at ordinary text the tile is still 66dp`() {
        show()
        assertEquals(66f, compose.onNodeWithContentDescription(TILE).getBoundsInRoot().height.value, 0.5f)
    }

    private companion object {
        /** The tile's one merged announcement: title, then subtitle. */
        const val TILE = "Photograph the document. Fastest, nothing to type"
        const val TITLE = "Photograph the document"
        const val SUBTITLE = "Fastest, nothing to type"
    }
}
