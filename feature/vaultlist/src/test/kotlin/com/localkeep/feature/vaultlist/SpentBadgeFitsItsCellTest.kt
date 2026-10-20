package com.localkeep.feature.vaultlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The quota badge has to fit on one line, in every language.
 *
 * Naming both numbers costs width. "1 OF 1" was seven characters and fitted beside the label;
 * "LIMIT 1 - 10 HELD" is seventeen and French "LIMITE 1 - 10 EN TOUT" twenty-one, so beside the
 * label they would have left "Identité" about 24 dp of a 128 dp share — a clipped word traded for a
 * readable badge, which is not a fix. The badge moved under the label instead, where it has the
 * whole 128 dp. This is the measurement that says it belongs there rather than the claim that it
 * does.
 *
 * Measured as a height difference rather than against a pinned pixel count. A badged cell is an
 * unbadged one plus `LkSpace.xs` and one line of `LkText.badge`; a badge that wraps adds a second
 * line, and the delta says which happened without this test having to know what a line of 9 sp
 * measures. `add-type-login` is the reference — a one-word label in a row with no badge in it, so
 * the row's `IntrinsicSize.Min` is not quietly supplying the height being compared.
 *
 * Rendered directly rather than through `AddItemSheet`: `ModalBottomSheet` draws in a window of its
 * own that re-provides `LocalDensity`, so a width imposed from outside does not reach the grid —
 * the same reason `AddItemSheetRowHeightTest` renders `AddTypeGrid` on its own. 392 dp is the
 * narrowest width the sheet's own layout notes reason about, so it is the unfavourable case.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w392dp-h800dp-xhdpi")
class SpentBadgeFitsItsCellTest {

    @get:Rule
    val compose = createComposeRule()

    private fun render() {
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.width(392.dp)) {
                    AddTypeGrid(
                        isPro = false,
                        spentAllowances = mapOf(
                            NewItemType.CARD to 10,
                            NewItemType.IDENTITY to 25,
                            NewItemType.FILE to 7,
                        ),
                        onSelect = {},
                        onNewFolder = {},
                    )
                }
            }
        }
    }

    private fun height(tag: String): Float =
        compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height

    /**
     * One line of badge, not two. The bound is two lines' worth of a 9 sp badge plus its gap and
     * padding — comfortably above a single line at this density and comfortably below a wrapped
     * one, so it fails on a wording that outgrows the cell and not on a font metric changing by a
     * pixel.
     */
    private fun assertBadgeIsOneLine() {
        render()
        val reference = height("add-type-login")
        listOf("add-type-card", "add-type-identity", "add-type-file").forEach { tag ->
            val delta = height(tag) - reference
            assertTrue(
                delta > 0f,
                "$tag is no taller than an unbadged cell (${height(tag)} px vs $reference px), so " +
                    "the badge is not being drawn at all",
            )
            assertTrue(
                delta <= ONE_LINE_CEILING_PX,
                "$tag is $delta px taller than an unbadged cell, which is more than one line of " +
                    "badge — the wording has outgrown the cell and wrapped inside the pill",
            )
        }
    }

    @Test
    fun `the badge is one line in English`() {
        assertBadgeIsOneLine()
    }

    /** French is the longest of the five, so it is the one that decides whether the wording fits. */
    @Test
    @Config(sdk = [34], qualifiers = "fr-w392dp-h800dp-xhdpi")
    fun `the badge is one line in French`() {
        assertBadgeIsOneLine()
    }

    @Test
    @Config(sdk = [34], qualifiers = "es-w392dp-h800dp-xhdpi")
    fun `the badge is one line in Spanish`() {
        assertBadgeIsOneLine()
    }

    @Test
    @Config(sdk = [34], qualifiers = "pt-w392dp-h800dp-xhdpi")
    fun `the badge is one line in Portuguese`() {
        assertBadgeIsOneLine()
    }

    @Test
    @Config(sdk = [34], qualifiers = "de-w392dp-h800dp-xhdpi")
    fun `the badge is one line in German`() {
        assertBadgeIsOneLine()
    }

    /**
     * The control. File shares its row with Secure note, which carries no badge, and
     * `IntrinsicSize.Min` has to keep that pair level rather than letting the badged one grow alone
     * — the defect that note in `AddTypeGrid` exists to prevent, now that a cell can grow again.
     */
    @Test
    fun `a badged cell and its unbadged neighbour are the same height`() {
        render()
        val file = height("add-type-file")
        val note = height("add-type-note")
        assertTrue(
            file == note,
            "the File cell is $file px and the Secure note cell beside it $note px — the badge has " +
                "grown one cell of the pair and left the other top-aligned",
        )
    }

    private companion object {
        /**
         * Measured, not derived. With the ceiling forced to 1 px on 2026-09-18 this test reported
         * **26.0 px** for all three badged cells in all five languages — one line of
         * `LkText.badge` and the `LkSpace.xs` gap above it, less whatever of it the cell's
         * `minTouchTarget()` floor was already absorbing.
         *
         * A wrapped badge adds a second 9 sp line at 1.3 line height, which is ~23 px more at
         * xhdpi and font scale 1.0, so the wrapped delta is around 49 px. 38 sits between the two
         * with room on both sides: far enough above 26 that a font metric moving by a pixel does
         * not fail it, far enough below 49 that a wording which outgrows the cell does.
         */
        const val ONE_LINE_CEILING_PX = 38f
    }
}
