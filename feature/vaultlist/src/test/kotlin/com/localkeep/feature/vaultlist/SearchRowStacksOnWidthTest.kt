package com.localkeep.feature.vaultlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
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
 * The search field keeps room to type in at the narrowest window at the largest text (SCL-024).
 *
 * Device pass 2026-10-19, 308dp at font scale 2.0, a query typed: the field shares its row with the
 * favourites star and the Filter button (two 48dp targets and their gaps) and, once there is text,
 * its own Clear button, so the editable text area was 48dp wide and the first letter of "Seed" sat
 * half cut. The caret keeps the text it is on in view, so nothing was lost, but a well you cannot
 * read what you typed in is not a search field. The two buttons drop to a row under the field when
 * the row is the narrow one, by the same [com.localkeep.core.ui.theme.LkText.stacks] rule the title
 * bar and the health card use, and 360dp at the default size keeps the single row the comps draw.
 *
 * The other half of the finding, "no result visible with the keyboard up", did not reproduce: the
 * captured state was a search scoped to a folder with no match, where the list is empty by
 * construction, and the keyboard-unpinning of the header (`rememberLkKeyboardUnpinsChrome`) has
 * landed since.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchRowStacksOnWidthTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show() {
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                    VaultSearchRow(
                        query = "Seed",
                        onQueryChange = {},
                        favouritesOn = false,
                        onToggleFavourites = {},
                        sheetFilterOn = false,
                        onOpenFilter = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun textAreaDp(): Float =
        compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.width / compose.density.density

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun theTextAreaHasRoomToReadWhatWasTyped() {
        show()
        val area = textAreaDp()
        assertTrue(area >= 100f, "the text area is ${area}dp wide: not enough to read a query in (SCL-024)")
        // The buttons are still there, one row down and together.
        val field = bounds("vaultlist-search")
        val star = bounds("vaultlist-favourites")
        val filter = bounds("vaultlist-filter")
        assertTrue(star.top >= field.bottom - 1f, "the star did not move under the field")
        assertEquals(star.top, filter.top, 0.5f)
    }

    /** Control: at the default size the comps' single row is untouched. */
    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi", fontScale = 1.0f)
    fun controlAt360dpAtTheDefaultSizeTheRowStaysOneLine() {
        show()
        val field = bounds("vaultlist-search")
        val star = bounds("vaultlist-favourites")
        val filter = bounds("vaultlist-filter")
        assertEquals(field.top, star.top, field.height / 2f)
        assertTrue(star.left >= field.right - 1f, "the star is not beside the field")
        assertTrue(filter.left >= star.right - 1f, "the filter is not beside the star")
    }
}
