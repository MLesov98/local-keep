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

    /**
     * SCL-008 at the narrowest window and the default size: the name is whole and the copy button is
     * clear of it. This asserted that the row *stacked* until 2026-10-28, which was the mechanism
     * rather than the outcome — and the mechanism was "every row stacks below 260dp", so "Bank" at
     * 308dp got a line to itself with a dead half-row beside its buttons. The stack is now decided by
     * whether the name fits ([vaultRowStacks]); "SeedReuseA" does, so it keeps the one-line layout,
     * and the starvation SCL-008 was about is pinned by the FAB case below.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h640dp-xhdpi", fontScale = 1.0f)
    fun theNameIsWholeAndClearOfItsCopyButtonAt308dpAtTheDefaultTextSize() {
        show(login)
        val title = boundsOf(title())
        val copy = boundsOf(copy())
        val layout = layoutOf(title())
        assertTrue(
            layout.lineCount == 1 && !layout.isLineEllipsized(0),
            "\"$TITLE\" is cut or wrapped at 308dp / 1.0 (lines=${layout.lineCount}).",
        )
        val w = minOf(copy.right, title.right) - maxOf(copy.left, title.left)
        val h = minOf(copy.bottom, title.bottom) - maxOf(copy.top, title.top)
        assertTrue(w <= 0f || h <= 0f, "the copy button ($copy) is drawn over the title ($title).")
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

    /**
     * 2026-10-28: a 360dp Galaxy S20 at a large text size. The row used to stack at any width from
     * font scale 1.5, so "Login 1" — six characters with room to spare — got its copy button on a
     * line of its own beneath an empty gap. The stack is for a name that does not fit, not a size.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi", fontScale = 1.5f)
    fun aShortNameKeepsItsCopyButtonBesideItAt360dpAtFontScaleOneAndAHalf() {
        val short = login.copy(title = "Login 1")
        show(short)
        val title = boundsOf(compose.onNodeWithText("Login 1", useUnmergedTree = true))
        val app = RuntimeEnvironment.getApplication()
        val copy = boundsOf(
            compose.onNodeWithContentDescription(
                app.getString(
                    R.string.vaultlist_quickcopy_description,
                    app.getString(R.string.vaultlist_quickcopy_copy_password),
                    "Login 1",
                ),
            ),
        )
        assertTrue(
            copy.top < title.bottom && copy.bottom > title.top,
            "at 360dp / 1.5 the copy button ($copy) was moved off the title's line ($title) for a " +
                "name that fits beside it.",
        )
    }

    /**
     * The other half of the same measurement: at 1.3 the old rule kept the row on one line and broke
     * "accounts.google.com" mid-word ("accounts.googl / e.com"). A word wider than the column the
     * one-line layout leaves is what earns the stack.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi", fontScale = 1.3f)
    fun aLongSingleWordNameIsNotBrokenMidWordAt360dp() {
        val long = login.copy(title = "accounts.google.com")
        show(long)
        val layout = layoutOf(compose.onNodeWithText("accounts.google.com", useUnmergedTree = true))
        assertTrue(
            layout.lineCount == 1,
            "\"accounts.google.com\" wraps to ${layout.lineCount} lines at 360dp / 1.3: the row kept " +
                "a column too narrow for the name's only word.",
        )
    }

    private fun copyFor(title: String): SemanticsNodeInteraction {
        val app = RuntimeEnvironment.getApplication()
        return compose.onNodeWithContentDescription(
            app.getString(
                R.string.vaultlist_quickcopy_description,
                app.getString(R.string.vaultlist_quickcopy_copy_password),
                title,
            ),
        )
    }

    /**
     * Width alone does not stack a row. Below 260dp of content every row used to stack, so at 308dp
     * and font 1.3 "Bank" — four letters — sat over a dead half-row with its buttons on a line of
     * their own (device sweep, 2026-10-28).
     */
    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h640dp-xhdpi", fontScale = 1.3f)
    fun aFourLetterNameKeepsItsCopyButtonBesideItAt308dp() {
        show(login.copy(title = "Bank"))
        val title = boundsOf(compose.onNodeWithText("Bank", useUnmergedTree = true))
        val copy = boundsOf(copyFor("Bank"))
        assertTrue(copy.top < title.bottom && copy.bottom > title.top, "Copy ($copy) left the title's line ($title)")
    }

    /**
     * A row that does stack puts the cluster beside the subtitle, not on a line of its own under it:
     * the name takes the first line whole, and nothing is left as a dead half-row (2026-10-28).
     */
    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi", fontScale = 1.5f)
    fun aStackedRowSharesTheSubtitleLineWithItsCopyButton() {
        val long = login.copy(title = "accounts.google.com")
        show(long)
        val title = boundsOf(compose.onNodeWithText("accounts.google.com", useUnmergedTree = true))
        val subtitle = boundsOf(compose.onNodeWithText("someone@example.com", useUnmergedTree = true))
        val copy = boundsOf(copyFor("accounts.google.com"))
        assertTrue(copy.top >= title.bottom - 1f, "the row did not stack: Copy ($copy) is beside the title ($title)")
        assertTrue(
            copy.top < subtitle.bottom && copy.bottom > subtitle.top,
            "Copy ($copy) is not on the subtitle's line ($subtitle): it took a line of its own",
        )
    }

    private companion object {
        val LOGIN_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
        val CARD_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000bb")
        const val TITLE = "SeedReuseA"
        const val WINDOW_DP = 308f
    }
}
