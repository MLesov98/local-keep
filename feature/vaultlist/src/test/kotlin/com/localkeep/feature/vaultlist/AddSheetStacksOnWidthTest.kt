package com.localkeep.feature.vaultlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Add sheet's grid drops to one cell per row when two columns cannot hold its labels (SCL-013).
 *
 * Device pass 2026-10-19, 308dp at font scale 2.0: half the window left a label 55dp, so "Password"
 * came out "Passw/ord", "Recovery" "Recov/ery", and "GPS location" lost its last letter outright
 * ("locatio") because `maxLines = 2` ended the text with no ellipsis to say anything was missing.
 * German broke at 1.0 on the same floor. A letter that silently disappears from the name of the
 * thing you are about to create is the worst version of this, so the assertions are on the laid-out
 * text, not on positions alone.
 *
 * The grid is rendered directly (see [AddTypeGrid]'s KDoc: the sheet's own window re-provides
 * density, so a font scale set from outside would not reach it) at 280dp, the width the sheet gives
 * it at a 308dp window after its margins and inset. [GraphicsMode.Mode.NATIVE] is what lets the text
 * actually wrap; under legacy graphics every assertion here would pass against the broken grid.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddSheetStacksOnWidthTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show() {
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.width(280.dp)) {
                    AddTypeGrid(isPro = true, onSelect = {}, onNewFolder = {})
                }
            }
        }
        compose.waitForIdle()
    }

    private fun text(label: String): SemanticsNodeInteraction =
        compose.onNodeWithText(label, useUnmergedTree = true)

    private fun layoutOf(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private fun string(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    private fun top(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    private fun left(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.left

    /**
     * Every character of [label] sits on some line. A `maxLines` cut with no ellipsis ends the last
     * line short of the text — "GPS locatio" — so the last line's end is the whole test. Read off
     * the layout's lines rather than `hasVisualOverflow`, which also trips on a width probe taken by
     * the row's intrinsic-height pass and says nothing about what was drawn.
     */
    private fun assertEveryLetterIsLaidOut(label: String, layout: TextLayoutResult) {
        assertEquals(label, layout.layoutInput.text.text)
        assertEquals(
            label.length,
            layout.getLineEnd(layout.lineCount - 1, visibleEnd = false),
            "'$label' was cut after ${layout.getLineEnd(layout.lineCount - 1, visibleEnd = false)} of " +
                "${label.length} characters",
        )
    }

    /** Every type label, as the sheet draws it. */
    private fun labels(): List<String> = listOf(
        R.string.vaultlist_add_new_folder,
        R.string.vaultlist_type_login,
        R.string.vaultlist_type_password,
        R.string.vaultlist_type_totp,
        R.string.vaultlist_type_note,
        R.string.vaultlist_type_file,
        R.string.vaultlist_type_coordinate,
        R.string.vaultlist_type_phrase,
        R.string.vaultlist_type_card,
        R.string.vaultlist_type_identity,
    ).map(::string)

    /** SCL-013: at the floor with the largest text every label is whole, and no word is cut. */
    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h640dp-xhdpi", fontScale = 2.0f)
    fun everyLabelIsWholeAndUncutAt308dpAtTwoTimesText() {
        show()
        labels().forEach { label ->
            val layout = layoutOf(text(label))
            assertEveryLetterIsLaidOut(label, layout)
            assertTrue(
                labelBreaksOnlyBetweenWords(layout, maxLines = Int.MAX_VALUE),
                "'$label' was cut inside a word across ${layout.lineCount} lines",
            )
        }
        // The cells go one to a row, so each label has the whole width rather than half of it.
        assertEquals(left("add-type-new-folder"), left("add-type-login"), 0.5f)
        assertTrue(top("add-type-login") > top("add-type-new-folder"), "the first pair still shares a row")
    }

    /** SCL-013, German: starts at 1.0 on the same floor, where "Zugangsdaten" does not fit half. */
    @Test
    @Config(sdk = [34], qualifiers = "de-w308dp-h640dp-xhdpi", fontScale = 1.0f)
    fun germanCompoundsAreWholeAt308dpAtDefaultText() {
        show()
        labels().forEach { label ->
            val layout = layoutOf(text(label))
            assertEveryLetterIsLaidOut(label, layout)
            assertTrue(
                labelBreaksOnlyBetweenWords(layout, maxLines = Int.MAX_VALUE),
                "'$label' was cut inside a word across ${layout.lineCount} lines",
            )
        }
    }

    /** Control: with room, the comps' two columns are untouched. */
    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h640dp-xhdpi", fontScale = 1.0f)
    fun controlTheDefaultTextSizeKeepsTwoColumns() {
        show()
        assertEquals(top("add-type-new-folder"), top("add-type-login"), 0.5f)
        assertTrue(left("add-type-login") > left("add-type-new-folder"), "the pair is not side by side")
    }
}
