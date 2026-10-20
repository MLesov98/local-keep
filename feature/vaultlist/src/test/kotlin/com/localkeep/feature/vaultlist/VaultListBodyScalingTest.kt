package com.localkeep.feature.vaultlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.localkeep.core.model.Folder
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.theme.LkIconButton
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkLockButton
import com.localkeep.core.ui.theme.LkScreenHeader
import com.localkeep.core.ui.theme.LkSearchField
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * SCL-007: the vault list's header and filter column took the whole viewport.
 *
 * German, 308dp at font scale 2.0, a type chip on: the title breaks in four, the count line takes
 * three lines, the search row and the active-filter strip follow, and all of it sat in a column
 * *above* the list. What was left for the rows was 0dp for logins and 29.5dp for 2FA (measured
 * 2026-10-19 on a 720x1280 window at 374dpi; `build/scaling/.../vault-logins__P1-de.png`), with the
 * FAB over the Filter button. A row is ~100dp tall at that size, so not one could ever be brought
 * fully into view.
 *
 * ### What is composed
 *
 * [VaultListBody], the skeleton the screen is made of, with the *real* header components — the same
 * [LkScreenHeader], [LkSearchField], icon buttons, [ActiveFilterStrip] and [FolderBreadcrumb] the
 * screen puts in it — and real [VaultItemRow]s. The screen itself is Hilt-backed and never reaches
 * idle under Robolectric (see `ListViewportWeightTest`), which is why the skeleton was lifted out.
 *
 * The body is given the window the screen actually gets at this profile: 548dp tall, less 24dp of
 * status bar above and 103dp of tab bar and gesture area below (read off the capture above).
 *
 * ### What is deliberately not asserted
 *
 * The keyboard-up case (SCL-015) and the FAB dodge need a device; they are in the report's
 * "still needs a device" list, not faked here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de-w308dp-h548dp-xhdpi", fontScale = 2.0f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VaultListBodyScalingTest {

    @get:Rule
    val compose = createComposeRule()

    private val rows = (0 until ROWS).map { index ->
        VaultItem(
            id = UUID(0L, index.toLong() + 1),
            title = "Konto $index",
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            content = Login(username = "nutzer$index", password = "hunter2"),
        )
    }

    @Composable
    private fun TypeFilteredHeader() {
        LkScreenHeader(
            title = stringResource(ItemTypeFilter.LOGIN.labelRes),
            subtitle = stringResource(
                R.string.vaultlist_count_filtered_folders_suffix,
                "52 Einträge",
                "3 Ordner",
                stringResource(ItemTypeFilter.LOGIN.labelRes),
            ),
            leading = {
                LkIconButton(icon = LkIcons.Menu, label = "Menü", onClick = {})
            },
            trailing = { LkLockButton() },
        )
        Row(
            modifier = Modifier.fillMaxWidth().testTag(SEARCH_ROW),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
        ) {
            LkSearchField(
                value = "",
                onValueChange = {},
                label = stringResource(R.string.vaultlist_search_placeholder),
                modifier = Modifier.weight(1f),
            )
            LkIconButton(icon = LkIcons.Star, label = "Favoriten", onClick = {})
            LkIconButton(icon = LkIcons.Filter, label = "Filter", onClick = {})
        }
        ActiveFilterStrip(
            filters = listOf(ActiveFilter.Type(ItemTypeFilter.LOGIN)),
            onRemove = {},
        )
    }

    private var listState: LazyListState? = null

    private fun show(windowHeight: Dp = WINDOW_HEIGHT, withCrumb: Boolean = false) {
        compose.setContent {
            LocalKeepTheme {
                val state = rememberLazyListState().also { listState = it }
                Box(Modifier.fillMaxWidth().height(windowHeight).testTag(WINDOW)) {
                    VaultListBody(
                        listState = state,
                        onCrumbHeight = {},
                        header = { TypeFilteredHeader() },
                        crumb = if (withCrumb) {
                            {
                                FolderBreadcrumb(
                                    path = listOf(Folder(UUID(1L, 1L), "Arbeit")),
                                    onCrumb = {},
                                    onRoot = {},
                                    modifier = Modifier.testTag(CRUMB),
                                )
                            }
                        } else {
                            null
                        },
                        notice = null,
                        contentPadding = PaddingValues(horizontal = ListSideInset, vertical = 0.dp),
                        modifier = Modifier.testTag(LIST),
                    ) {
                        items(rows, key = { it.id.toString() }) { item ->
                            VaultItemRow(
                                item = item,
                                onClick = {},
                                onToggleFavorite = {},
                                onTrash = {},
                                onQuickCopy = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun boundsOf(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun dp(px: Float) = px / compose.density.density

    /**
     * The defect: at this size, with the filter on, no row can ever be brought fully into view.
     *
     * Scrolled to the first row, its whole box has to sit inside the window. With the header pinned
     * above the list the rows' viewport is a sliver and the row is clipped by it.
     */
    @Test
    fun aRowCanBeBroughtFullyIntoViewWithTheTypeFilterOn() {
        show()
        // The fixture has to be tall, or this proves nothing: the header block measured ~390dp in
        // `vault-logins__P1-de.png`, against the 421dp the body gets here. The shared LkScreenHeader
        // has since learned to stack its controls instead of shattering the title, so the same
        // fixture now measures ~237dp; it still leaves a pinned layout less than half the body.
        val header = dp(boundsOf(SEARCH_ROW).bottom)
        assertTrue(header >= 200f, "fixture header ends at ${header}dp; too short to reproduce SCL-007")

        compose.onNodeWithTag(LIST).performScrollToIndex(1)
        compose.waitForIdle()

        val window = boundsOf(WINDOW)
        val firstRow = compose.onAllNodesWithTag("vaultlist-row-${rows[0].id}").fetchSemanticsNodes()
        assertTrue(
            firstRow.isNotEmpty(),
            "the first row was never composed: the list's viewport is 0dp, which is SCL-007 exactly",
        )
        val row = firstRow.single().boundsInRoot
        assertTrue(
            row.top >= window.top - 1f && row.bottom <= window.bottom + 1f,
            "the first row occupies ${dp(row.top)}..${dp(row.bottom)}dp in a window of " +
                "${dp(window.top)}..${dp(window.bottom)}dp: it cannot be shown whole, so the list " +
                "has no usable viewport (SCL-007)",
        )
    }

    /** The header is part of what scrolls: after the list has moved on, the search row is gone. */
    @Test
    fun theHeaderScrollsAwayWithTheList() {
        show()
        compose.onNodeWithTag(SEARCH_ROW).assertIsDisplayed()

        compose.onNodeWithTag(LIST).performScrollToIndex(4)
        compose.waitForIdle()

        val onScreen = compose.onAllNodesWithTag(SEARCH_ROW).fetchSemanticsNodes()
            .filter { it.boundsInRoot.bottom > boundsOf(WINDOW).top + 1f }
        assertTrue(
            onScreen.isEmpty(),
            "the search row is still on screen after scrolling four rows — the header is pinned",
        )
    }

    /** The breadcrumb is the one piece of the header that stays: where you are is not scrollable. */
    @Test
    fun theBreadcrumbSticksToTheTopWhileTheListScrolls() {
        show(withCrumb = true)
        compose.onNodeWithTag(LIST).performScrollToIndex(6)
        compose.waitForIdle()

        val window = boundsOf(WINDOW)
        val crumb = boundsOf(CRUMB)
        assertEquals(window.top, crumb.top, 1f, "the crumb should sit on the window's top edge, was at ${dp(crumb.top)}dp")
    }

    /**
     * Control: a roomy phone at normal text loses nothing. The first row is on screen without a
     * scroll — the header scrolling away must not cost the common case its list.
     */
    @Test
    @Config(qualifiers = "en-w411dp-h891dp-xhdpi", fontScale = 1.0f)
    fun controlAtNormalSizeTheFirstRowIsVisibleWithoutScrolling() {
        show(windowHeight = 700.dp)
        compose.onNodeWithTag("vaultlist-row-${rows[0].id}").assertIsDisplayed()
    }

    private companion object {
        const val ROWS = 52
        const val LIST = "list"
        const val WINDOW = "window"
        const val SEARCH_ROW = "search-row"
        const val CRUMB = "crumb"

        /** 548dp window, less 24dp status bar above and 103dp tab bar + gesture area below. */
        val WINDOW_HEIGHT = 421.dp
    }
}
