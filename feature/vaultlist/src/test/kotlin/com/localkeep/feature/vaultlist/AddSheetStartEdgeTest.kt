package com.localkeep.feature.vaultlist

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Add sheet's glyph plates and its heading share one start edge.
 *
 * The sheet insets its title 14dp inside its own 10dp margin; the cells draw their plates a further
 * [LkSpace.sm] inside their bounds, which left the plates about 7dp to the left of the heading above
 * them. Measured on the sheet itself — its own window re-provides density, so this is a position
 * test, not a font-scale one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h1600dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddSheetStartEdgeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theFirstCellsPlateStartsWhereTheHeadingStarts() {
        compose.setContent {
            LocalKeepTheme {
                AddItemSheet(
                    isPro = true,
                    onSelect = {},
                    onDismiss = {},
                    onNewFolder = {},
                    restored = true,
                )
            }
        }
        compose.waitForIdle()
        val app = RuntimeEnvironment.getApplication()
        val density = compose.density.density
        val heading = compose.onNodeWithText(app.getString(R.string.vaultlist_add_sheet_title))
            .fetchSemanticsNode().boundsInWindow.left
        val cell = compose.onNodeWithTag("add-type-new-folder", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInWindow.left
        // The plate is drawn LkSpace.sm inside the cell's own bounds.
        val plate = cell + LkSpace.sm.value * density
        assertEquals(heading, plate, 1.5f, "heading at $heading px, first plate at $plate px")
    }
}
