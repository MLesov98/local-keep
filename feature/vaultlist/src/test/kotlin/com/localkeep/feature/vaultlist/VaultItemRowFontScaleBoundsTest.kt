package com.localkeep.feature.vaultlist

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A vault row's four children have to fit the narrowest window the app supports, at the largest text
 * the system offers — and the item's own name is the child that must not be the one to lose.
 *
 * This is the app's primary screen, and at font scale 2.0 with the largest display size it
 * collapsed: the title took the remainder of a budget the tile and the copy pill had already
 * spent — 66dp of a 284dp row, so "SeedReuseA" rendered as "Seed / Reus / eA", three characters to a
 * line — and then the copy pill was drawn straight through it, because with the FAB level with the
 * row the trailing cluster slid 75dp left to clear it and 75dp is most of what the title had.
 * Measured on hardware 2026-08-31 at 560dpi (309dp) and again at 420dpi (411dp).
 *
 * **The FAB half of that is gone as of 2026-09-16 (F-12): the FAB leaves the screen while the list
 * scrolls, so no row is ever under a visible one and the row's dodge was deleted with it.** This
 * file used to place a synthetic FAB rect over the row to drive that dodge at full travel; it no
 * longer can, and no longer needs to. What remains is the half that was always independent of the
 * FAB — a title starved by the tile and the pill on a 284dp line — which is what the stacked layout
 * above `LkText.stackAboveFontScale` answers, and which these tests still pin.
 *
 * ### What is asserted, and what is deliberately not
 *
 * The outcome, not the threshold, and not the treatment: a fix that clamped the dodge, or one that
 * moved the copy control somewhere else entirely, would satisfy [theCopyControlNeverDrawsOverTheTitle]
 * just as the stacking does. What the tests do pin is that the copy control still *exists* at 2.0 —
 * dropping it and leaning on the overflow menu was the cheap-looking alternative, and the menu offers
 * favourite, select, hide and trash and no copy at all, so taking it away would remove the one-tap
 * secret from exactly the reader who needs it most.
 *
 * ### Three settings that are load-bearing, not boilerplate
 *
 * [GraphicsMode.Mode.NATIVE] is the difference between this file testing something and testing
 * nothing: under Robolectric's legacy graphics there is no real text layout, so a title cannot wrap,
 * cannot outgrow its column, and every assertion here would pass against the broken row.
 *
 * The 308dp qualifier is the narrowest window the app supports — the width at the system's largest
 * display size, and the number [com.localkeep.core.ui.theme.LkText.stackAboveFontScale] is chosen
 * against. A 411dp screen absorbs most of this, which is why the row shipped.
 *
 * The 12dp horizontal padding is the vault list's own `contentPadding`. Without it the row measures
 * 308dp instead of the 284dp it actually gets, and 24dp is a third of what the title column had.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w308dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VaultItemRowFontScaleBoundsTest {

    @get:Rule
    val compose = createComposeRule()

    private val item = VaultItem(
        id = ITEM_ID,
        title = TITLE,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        // A password is what earns the row its inline copy control — the whole trailing cluster
        // exists only on rows that have one.
        content = Login(username = SUBTITLE, password = "hunter2"),
    )

    private fun showRow() {
        compose.setContent {
            LocalKeepTheme {
                VaultItemRow(
                    item = item,
                    onClick = {},
                    onToggleFavorite = {},
                    onTrash = {},
                    onQuickCopy = {},
                    modifier = Modifier.fillMaxWidth().padding(horizontal = LIST_GUTTER),
                )
            }
        }
        compose.waitForIdle()
    }

    /** Bounds in dp, so every number below is the number a layout inspector would show. */
    private fun boundsOf(node: () -> androidx.compose.ui.test.SemanticsNodeInteraction): Rect {
        val b = node().fetchSemanticsNode().boundsInRoot
        val d = compose.density.density
        return Rect(b.left / d, b.top / d, b.right / d, b.bottom / d)
    }

    private fun titleBounds() = boundsOf { compose.onNodeWithText(TITLE, useUnmergedTree = true) }

    private fun copyControlBounds(): Rect {
        val app = RuntimeEnvironment.getApplication()
        val description = app.getString(
            R.string.vaultlist_quickcopy_description,
            app.getString(R.string.vaultlist_quickcopy_copy_password),
            TITLE,
        )
        return boundsOf { compose.onNodeWithContentDescription(description) }
    }

    private fun overflowBounds() =
        boundsOf { compose.onNodeWithTag("vaultlist-row-$ITEM_ID-overflow") }

    private fun assertNoOverlap(name: String, a: Rect, b: Rect) {
        val overlap = a.intersect(b)
        assertTrue(
            overlap.width <= 0f || overlap.height <= 0f,
            "\"$TITLE\" occupies $a and $name occupies $b — they share " +
                "${overlap.width}x${overlap.height}dp. On this screen that is the item's own name " +
                "drawn through the control beside it, and neither one is readable or usable.",
        )
    }

    /**
     * The defect, at the size and width it was photographed at.
     *
     * Both trailing controls, not just the pill: they used to move as one cluster, so a fix that
     * spared the title from one of them and not the other would be half a fix — and the stacked
     * layout that answers it now moves both or neither.
     */
    @Test
    @Config(fontScale = 2.0f)
    fun theCopyControlNeverDrawsOverTheTitle() {
        showRow()
        assertNoOverlap("the copy button", titleBounds(), copyControlBounds())
        assertNoOverlap("the overflow button", titleBounds(), overflowBounds())
    }

    /**
     * The other half: the title was not merely overdrawn, it was starved.
     *
     * Stated as a comparison rather than a pixel count because the count is not the point — the
     * point is that the row was spending more of a 284dp line on the button that copies the password
     * than on the name that says which password it is. At font scale 1.0 the title has 111dp against
     * the pill's 60; before this changed, 2.0 inverted it to 66 against 81.
     *
     * Never depended on the FAB, which is why it is unchanged by F-12 deleting the dodge.
     */
    @Test
    @Config(fontScale = 2.0f)
    fun theNameGetsMoreRoomThanTheButtonThatCopiesIt() {
        showRow()
        val title = titleBounds()
        val copy = copyControlBounds()
        assertTrue(
            title.width > copy.width,
            "the title has ${title.width}dp and the copy button ${copy.width}dp. A row whose name " +
                "is narrower than its copy button has stopped identifying anything — at this width " +
                "it wraps to three characters a line.",
        )
    }

    /**
     * The control, and the reason the change is gated on the font scale rather than applied always.
     *
     * This is a visual-design-sensitive screen and the row is its most repeated element; a fix that
     * quietly restyled it at the size everyone actually reads it at would be the worse regression of
     * the two. The numbers are what the row measured before the change and must measure after it.
     *
     * The overflow's rectangle is asserted whole because it is the one control that moved: it is an
     * overlay outside the padded container, and it now picks its alignment from the font scale. If
     * the stacked branch ever leaks into the default size, this is where it shows.
     */
    @Test
    // 360dp, not the class's 308dp: the row stacks on its width as well as its font scale now
    // (VaultItemRowStacksOnWidthTest), and 308dp at the default size is a stacked row. 360dp is the
    // narrowest phone that keeps the one-line layout the comps draw, so it is where "inert at the
    // default size" is measured.
    @Config(fontScale = 1.0f, qualifiers = "w360dp-h640dp-xhdpi")
    fun theDefaultTextSizeRendersTheRowExactlyAsItDid() {
        showRow()
        val row = boundsOf { compose.onNodeWithTag("vaultlist-row-$ITEM_ID") }
        assertClose("the row's height", row.height, ROW_HEIGHT_DP)
        assertClose("the title's left edge", titleBounds().left, TITLE_LEFT_DP)

        val overflow = overflowBounds()
        assertClose("the overflow's left edge", overflow.left, OVERFLOW_LEFT_DP)
        assertClose("the overflow's top edge", overflow.top, OVERFLOW_TOP_DP)
        assertClose("the overflow's right edge", overflow.right, OVERFLOW_RIGHT_DP)
        assertClose("the overflow's bottom edge", overflow.bottom, OVERFLOW_BOTTOM_DP)

        // One line, still: the copy control and the title share vertical space. Asserting the two
        // rectangles *do* intersect vertically is the inverse of what the 2.0 test asserts, and it
        // is what stops "make it never overlap" from being answered by stacking every row.
        val title = titleBounds()
        val copy = copyControlBounds()
        assertTrue(
            copy.top < title.bottom && copy.bottom > title.top,
            "at the default text size the copy button ($copy) no longer shares a line with the " +
                "title ($title). The row is one line here and must stay one line.",
        )
    }

    private fun assertClose(what: String, actual: Float, expected: Float) {
        assertTrue(
            abs(actual - expected) <= TOLERANCE_DP,
            "$what measured ${actual}dp where it measured ${expected}dp before the stacked layout " +
                "existed. This change was supposed to be inert at the default text size.",
        )
    }

    private companion object {
        val ITEM_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

        /**
         * Long enough to fill the title column at 2.0 and short enough not to at 1.0. A title that
         * overflowed at both would fail for the length and prove nothing about dynamic type.
         */
        const val TITLE = "SeedReuseA"
        const val SUBTITLE = "someone@example.com"

        /** The vault list's own `contentPadding` — the row never gets the whole window. */
        val LIST_GUTTER = 12.dp

        /** Sub-pixel slack: these are float divisions of integer pixel bounds, not round numbers. */
        const val TOLERANCE_DP = 1f

        // Measured at font scale 1.0 before the stacked layout existed, and unchanged by it. Re-based
        // from 308dp to 360dp when the row began stacking on width: the vertical and title numbers are
        // the same, the overflow's horizontal ones move by the 52dp of extra window.
        const val ROW_HEIGHT_DP = 70f
        const val TITLE_LEFT_DP = 77f
        const val OVERFLOW_LEFT_DP = 300f
        const val OVERFLOW_TOP_DP = 11f
        const val OVERFLOW_RIGHT_DP = 348f
        const val OVERFLOW_BOTTOM_DP = 59f
    }
}
