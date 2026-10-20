package com.localkeep.feature.vaultlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.theme.LkIconButton
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkLockButton
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
 * The vault list's two header rows at the narrowest window at the largest text, German.
 *
 * Device pass 2026-10-19, 720x1280 at 374dpi, font scale 2.0 (308x548dp), a type filter on:
 *
 * - **SCL-007.** At first paint the add FAB sat over all but the top 11px of the Filter button
 *   (`vaultlist-filter [561,792][673,803]` against `fab-add-item [537,803][673,939]`). The header
 *   scrolls with the list now, so a control in it can rest under the FAB the way a row's trailing
 *   cluster can; the rows dodge it and the header did not.
 * - **SCL-019.** The title "Zugangsdaten" rendered as `Zu- / gan / gs- / da- / ten`: the Lock
 *   button (icon and the word "Sperren") took 304 of 626px, the title was left 157px, and `gangs-`
 *   does not fit 157px, so the line broke mid-syllable with no hyphen. The style was already a
 *   hyphenating one; the slot was the defect.
 *
 * Both are composed from the real components the screen uses ([VaultSearchRow], [VaultTitleBar]) —
 * the screen itself is Hilt-backed and never reaches idle under Robolectric (see
 * `ListViewportWeightTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de-w308dp-h548dp-xhdpi", fontScale = 2.0f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VaultListChromeScalingTest {

    @get:Rule
    val compose = createComposeRule()

    private var fab by mutableStateOf<Rect?>(null)

    private fun boundsOf(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun showSearchRow() {
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                    VaultSearchRow(
                        query = "",
                        onQueryChange = {},
                        favouritesOn = false,
                        onToggleFavourites = {},
                        sheetFilterOn = true,
                        onOpenFilter = {},
                        fab = { fab },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * SCL-007. The FAB is parked over the Filter button, which is where the capture had it; the row
     * has to give way so that Filter ends clear of the FAB's left edge.
     */
    @Test
    fun theFilterButtonIsNotLeftUnderTheFab() {
        showSearchRow()
        val filterAtRest = boundsOf("vaultlist-filter")
        fab = Rect(
            left = filterAtRest.left - 24f,
            top = filterAtRest.top + 11f,
            right = filterAtRest.right + 16f,
            bottom = filterAtRest.bottom + 130f,
        )
        compose.waitForIdle()

        val filter = boundsOf("vaultlist-filter")
        assertTrue(
            filter.right <= fab!!.left + 1f,
            "the Filter button spans ${filter.left}..${filter.right}px against a FAB starting at " +
                "${fab!!.left}px: the FAB still covers it (SCL-007)",
        )
        val star = boundsOf("vaultlist-favourites")
        assertTrue(star.right <= filter.left + 1f, "the favourites star was pushed onto the Filter button")
        val field = boundsOf("vaultlist-search")
        assertTrue(field.width >= 60f * compose.density.density, "the search field was crushed to ${field.width}px")
    }

    /** Control: with no FAB over the row — a scroll in progress hands null — nothing moves. */
    @Test
    fun controlWithNoFabTheRowKeepsItsFullWidth() {
        showSearchRow()
        val before = boundsOf("vaultlist-filter")
        fab = null
        compose.waitForIdle()
        assertEquals(before.right, boundsOf("vaultlist-filter").right, 0.5f)
    }

    /** Control: a FAB on the screen but nowhere near the row costs the row nothing. */
    @Test
    fun controlAFabBelowTheRowCostsItNothing() {
        showSearchRow()
        val before = boundsOf("vaultlist-filter")
        fab = Rect(before.left, before.bottom + 200f, before.right, before.bottom + 340f)
        compose.waitForIdle()
        assertEquals(before.right, boundsOf("vaultlist-filter").right, 0.5f)
    }

    private fun showTitleBar() {
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                    VaultTitleBar(
                        title = stringResource(ItemTypeFilter.LOGIN.labelRes),
                        subtitle = null,
                        leading = { LkIconButton(icon = LkIcons.Menu, label = "Menü", onClick = {}) },
                        trailing = { LkLockButton() },
                        modifier = Modifier.testTag(BAR),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun titleLayout(): TextLayoutResult {
        val node = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    /**
     * SCL-019. The title is a word of twelve letters in a 267dp column. It must not be cut into
     * fragments; two lines is the most a compound that long is allowed to take.
     */
    @Test
    fun theTitleIsNotBrokenIntoFragments() {
        showTitleBar()
        val layout = titleLayout()
        assertTrue(
            layout.lineCount <= 2,
            "'${layout.layoutInput.text.text}' laid out on ${layout.lineCount} lines in a " +
                "${layout.size.width}px slot: the Lock button starved the heading (SCL-019)",
        )
    }

    private companion object {
        const val BAR = "bar"
    }
}
