package com.localkeep.feature.vaultlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.theme.LkFrame
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
 * A type-filtered empty state keeps its action on one line when the FAB is beside it, at large text.
 *
 * Device font-scale sweep 2026-10-28, 360dp wide at font scale 1.5, filtered to Cards / Identities /
 * 2FA codes: `clearOfFab` narrowed the *whole* panel by the FAB's reach, and the panel's own side
 * padding plus the button's 40dp on each side came out of what was left, so the button was a ~130dp
 * plate and "Add a card" broke to "Add a / card" — its bottom then under the bottom bar. At 411dp the
 * same panel was full width and fine, which is how it passed every earlier pass.
 *
 * Only the sentence can ever sit beside the FAB (SCL-037): the button is below it, and the
 * `gutterFab` the caller reserves under the panel keeps that row clear of the FAB's. So the reserve
 * belongs on the copy and the action keeps the panel's width.
 *
 * Composed from [EmptyVault] with the modifier the screen gives it — the screen is Hilt-backed and
 * never reaches idle under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 1.5f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmptyVaultActionKeepsItsWidthTest {

    @get:Rule
    val compose = createComposeRule()

    private var fab by mutableStateOf<Rect?>(null)

    private fun label(res: Int) = RuntimeEnvironment.getApplication().getString(res)

    private fun show(filter: ItemTypeFilter) {
        fab = null
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.fillMaxSize()) {
                    EmptyVault(
                        query = "",
                        filtered = true,
                        onClear = {},
                        typeFilter = filter,
                        onAddOfType = {},
                        modifier = Modifier.padding(top = 48.dp, bottom = LkFrame.gutterFab),
                        fab = { fab },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** The resting FAB as the screen places it — 58dp, 20dp in from the end — level with the copy. */
    private fun parkTheFab(widthPx: Float, level: Float): Rect {
        val d = compose.density.density
        return Rect(widthPx - 78f * d, level, widthPx - 20f * d, level + 58f * d).also {
            fab = it
            compose.waitForIdle()
        }
    }

    private fun windowWidthPx() = RuntimeEnvironment.getApplication().resources.displayMetrics.widthPixels.toFloat()

    private fun assertActionStaysOneLine(filter: ItemTypeFilter, actionRes: Int, bodyRes: Int) {
        show(filter)
        val body = compose.onNodeWithText(label(bodyRes), useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        parkTheFab(windowWidthPx(), body.top)
        val node = compose.onNodeWithText(label(actionRes), useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        assertEquals(1, results.single().lineCount, "\"${label(actionRes)}\" broke over ${results.single().lineCount} lines")
    }

    @Test
    fun cardsActionStaysOnOneLineBesideTheFab() =
        assertActionStaysOneLine(ItemTypeFilter.CARD, R.string.vaultlist_empty_add_card, R.string.vaultlist_empty_body_addable)

    @Test
    fun identitiesActionStaysOnOneLineBesideTheFab() =
        assertActionStaysOneLine(ItemTypeFilter.IDENTITY, R.string.vaultlist_empty_add_identity, R.string.vaultlist_empty_body_addable)

    @Test
    fun totpActionStaysOnOneLineBesideTheFab() =
        assertActionStaysOneLine(ItemTypeFilter.TOTP, R.string.vaultlist_empty_add_totp, R.string.vaultlist_empty_body_addable)

    /** The same panel at the narrowest window and the largest text, where the button had the least to spare. */
    @Test
    @Config(qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun identitiesActionStaysOnOneLineAtTheNarrowestWindow() =
        assertActionStaysOneLine(ItemTypeFilter.IDENTITY, R.string.vaultlist_empty_add_identity, R.string.vaultlist_empty_body_addable)

    /** Control: the copy still ends clear of the FAB (SCL-037) — moving the reserve must not lose it. */
    @Test
    fun controlTheCopyStillEndsClearOfTheFab() {
        show(ItemTypeFilter.CARD)
        val body = compose.onNodeWithText(label(R.string.vaultlist_empty_body_addable), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInWindow
        val parked = parkTheFab(windowWidthPx(), body.top)
        val after = compose.onNodeWithText(label(R.string.vaultlist_empty_body_addable), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInWindow
        assertTrue(after.right <= parked.left + 1f, "the copy spans ..${after.right}px against a FAB starting at ${parked.left}px")
    }

    /**
     * The button itself ends clear of a FAB parked level with it. Full width, the plate ran under the
     * FAB at its end — "Add a card" / "Add a 2FA code" covered at 360dp / 1.5 (final sweep,
     * 2026-10-28). It hugs its label, centred, now.
     */
    private fun assertActionClearOfFab(filter: ItemTypeFilter, actionRes: Int) {
        show(filter)
        val before = compose.onNodeWithText(label(actionRes)).fetchSemanticsNode().boundsInWindow
        val parked = parkTheFab(windowWidthPx(), before.top)
        val button = compose.onNodeWithText(label(actionRes)).fetchSemanticsNode().boundsInWindow
        assertTrue(button.right <= parked.left + 1f, "\"${label(actionRes)}\" spans ..${button.right}px under a FAB from ${parked.left}px")
    }

    @Test
    fun cardsActionEndsClearOfAFabLevelWithIt() = assertActionClearOfFab(ItemTypeFilter.CARD, R.string.vaultlist_empty_add_card)

    @Test
    fun totpActionEndsClearOfAFabLevelWithIt() = assertActionClearOfFab(ItemTypeFilter.TOTP, R.string.vaultlist_empty_add_totp)

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi", fontScale = 2.0f)
    fun totpActionEndsClearOfAFabAtTheLargestText() = assertActionClearOfFab(ItemTypeFilter.TOTP, R.string.vaultlist_empty_add_totp)

}
