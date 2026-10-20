package com.localkeep.feature.vaultlist

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.localkeep.core.model.Card
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.time.Instant
import java.util.UUID
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A vault row stacks on the width it is given, not on font scale alone (decision 4, SCL-008, SCL-009).
 *
 * The row used to stack only at `fontScale >= LkText.stackAboveFontScale` (1.5). At 308dp - the
 * narrowest window the app supports - and the default size, its content is 216dp: the tile, the copy
 * pill and two gaps take 128 of it, and when a resting FAB covers the row the dodge takes the title
 * column's content down to a glyph ("S..." in the scaling pass, SCL-008). A card row has no pill, so
 * once stacked the dodged overflow plate landed on the last digit of the masked number (SCL-009); the
 * reserve that guards the title in the one-line layout was missing from the two-line one.
 *
 * ### Calibration
 *
 * [com.localkeep.core.ui.theme.LkText.stacks] is asked about the width left to the row's content:
 * the row's own width minus its 10dp start inset and 58dp end strip. With the list's 12dp gutters that
 * is 216dp at 308dp and 268dp at 360dp, either side of `stackBelowWidth` (260dp). 360dp keeps the
 * one-line layout the comps draw - [theRowStaysOneLineAt360dp] pins it - and 308dp stacks.
 *
 * [GraphicsMode.Mode.NATIVE] is what lets the title wrap; under legacy graphics every assertion here
 * would pass against the broken row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VaultItemRowStacksOnWidthTest {

    @get:Rule
    val compose = createComposeRule()

    private var fab by mutableStateOf<Rect?>(null)

    private val login = VaultItem(
        id = LOGIN_ID,
        title = TITLE,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = Login(username = "someone@example.com", password = "hunter2"),
    )

    private val card = VaultItem(
        id = CARD_ID,
        title = "Travel",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = Card(holder = "A Holder", number = "4111111111110005"),
    )

    private fun show(item: VaultItem) {
        fab = null
        compose.setContent {
            LocalKeepTheme {
                VaultItemRow(
                    item = item,
                    onClick = {},
                    onToggleFavorite = {},
                    onTrash = {},
                    onQuickCopy = {},
                    avoidanceBounds = { fab },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun boundsOf(node: SemanticsNodeInteraction): Rect {
        val b = node.fetchSemanticsNode().boundsInWindow
        val d = compose.density.density
        return Rect(b.left / d, b.top / d, b.right / d, b.bottom / d)
    }

    private fun overflow(item: VaultItem) =
        compose.onNodeWithTag("vaultlist-row-${item.id}-overflow", useUnmergedTree = true)

    private fun title() = compose.onNodeWithText(TITLE, useUnmergedTree = true)

    private fun copy(): SemanticsNodeInteraction {
        val app = RuntimeEnvironment.getApplication()
        return compose.onNodeWithContentDescription(
            app.getString(
                R.string.vaultlist_quickcopy_description,
                app.getString(R.string.vaultlist_quickcopy_copy_password),
                TITLE,
            ),
        )
    }

    /**
     * A resting FAB drawn over the overflow button, as on the row nearest the thumb: 56dp wide, 16dp
     * from the window's right edge, so its left edge is 72dp in from the 308dp window's. In pixels.
     */
    private fun coverTheOverflow(item: VaultItem) {
        val b = overflow(item).fetchSemanticsNode().boundsInWindow
        val d = compose.density.density
        fab = Rect((WINDOW_DP - 72f) * d, b.top - 20f, WINDOW_DP * d, b.bottom + 20f)
        compose.waitForIdle()
    }

    private fun layoutOf(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    /** SCL-008: the narrowest window at the default size stacks, so the pill sits under the name. */
    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h640dp-xhdpi", fontScale = 1.0f)
    fun theRowStacksAt308dpAtTheDefaultTextSize() {
        show(login)
        val title = boundsOf(title())
        val copy = boundsOf(copy())
        assertTrue(
            copy.top >= title.bottom - 1f,
            "the copy button ($copy) still shares a line with the title ($title) on a 216dp content " +
                "width: the row has not stacked.",
        )
    }

    /** SCL-008: under a resting FAB the name still fits on one line at 308dp / 1.0. */
    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h640dp-xhdpi", fontScale = 1.0f)
    fun theNameKeepsItsLineWhenTheFabCoversTheRowAt308dp() {
        show(login)
        coverTheOverflow(login)
        val layout = layoutOf(title())
        assertTrue(
            layout.lineCount == 1 && !layout.isLineEllipsized(0),
            "\"$TITLE\" wraps to ${layout.lineCount} lines in ${boundsOf(title()).width}dp " +
                "(ellipsized=${layout.isLineEllipsized(0)}, overflow=${layout.hasVisualOverflow}): " +
                "the FAB dodge starved the title.",
        )
    }

    /** SCL-009: the dodged overflow plate must not land on the masked number of a card row. */
    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h640dp-xhdpi", fontScale = 2.0f)
    fun theOverflowIsNotDrawnOverACardsNumberWhenTheFabCoversTheRow() {
        show(card)
        val number = compose.onNodeWithText("•••• 0005", useUnmergedTree = true)
        coverTheOverflow(card)
        val plate = boundsOf(overflow(card))
        val digits = boundsOf(number)
        val w = minOf(plate.right, digits.right) - maxOf(plate.left, digits.left)
        val h = minOf(plate.bottom, digits.bottom) - maxOf(plate.top, digits.top)
        assertTrue(
            w <= 0f || h <= 0f,
            "the overflow plate $plate shares ${w}x${h}dp with the card number $digits: it is drawn " +
                "over the last digits.",
        )
    }

    /**
     * The control. 360dp at the default size is the layout the comps draw: one line, the copy button
     * beside the title. If this goes red the width threshold has crept above the phone people carry.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi", fontScale = 1.0f)
    fun theRowStaysOneLineAt360dp() {
        show(login)
        val title = boundsOf(title())
        val copy = boundsOf(copy())
        assertTrue(
            copy.top < title.bottom && copy.bottom > title.top,
            "at 360dp / 1.0 the copy button ($copy) no longer shares a line with the title ($title).",
        )
    }

    private companion object {
        val LOGIN_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
        val CARD_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000bb")
        const val TITLE = "SeedReuseA"
        const val WINDOW_DP = 308f
    }
}
