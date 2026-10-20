package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every vault-row action must be reachable without TalkBack running.
 *
 * `VaultItemRow` offers its actions twice: as `CustomAccessibilityAction`s on the row's semantics
 * node, and as `DropdownMenuItem`s in the visible overflow menu. The two lists are not
 * interchangeable. A `CustomAccessibilityAction` is delivered through the accessibility framework,
 * which in practice means TalkBack — a user on a physical keyboard, a mouse, or Switch Access
 * without TalkBack reaches the overflow menu and nothing else.
 *
 * "Move to folder" was in the accessibility list and not in the menu, which made it the single row
 * action with only one route. Filing by drag has no keyboard equivalent either (§8 of the drag
 * design), so for those users the item simply could not be filed from this screen at all, while
 * favourite, hide, select and trash all worked.
 *
 * Asserted on the source: `:feature:vaultlist`'s unit tests have no Compose UI test dependency, so
 * there is no way to compose the menu here and enumerate it. Same idiom as
 * `SettingsInFlightControlsTest`, including stripping comments first — the prose above the menu item
 * names the menu item, and a scan that could not tell prose from code would pass with the entry
 * deleted.
 */
class RowActionsHaveANonTalkBackRouteTest {

    private val source: String =
        File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultItemRow.kt")
            .readLines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    /**
     * The overflow menu alone.
     *
     * Bounded below before anything is concluded from it: `substringAfter` returns the whole
     * receiver when its delimiter is absent, so a renamed anchor would silently widen this to the
     * entire file — including the accessibility-action list, which is exactly the region this test
     * must *not* be reading. That would turn "the menu offers it" into "the file mentions it".
     */
    private val overflowMenu: String =
        source.substringAfter("R.string.vaultlist_menu_add_favourite")
            .substringBefore("R.string.vaultlist_menu_move_to_trash")

    @Test
    fun `the menu slice is bounded and is the menu`() {
        assertTrue(
            overflowMenu.isNotEmpty() && overflowMenu.length < source.length,
            "the overflow-menu slice is unbounded, so every assertion below is reading the whole " +
                "file and cannot fail; length=${overflowMenu.length} of ${source.length}",
        )
        assertTrue(
            overflowMenu.contains("R.string.vaultlist_menu_select_multiple"),
            "the slice does not contain a menu entry known to sit inside it, so it is not the " +
                "region this test believes it is reading",
        )
        assertTrue(
            !overflowMenu.contains("CustomAccessibilityAction"),
            "the slice reaches into the accessibility-action list, which would let an action with " +
                "no menu entry satisfy the assertions below",
        )
    }

    @Test
    fun `move to folder has a visible menu entry, not only an accessibility action`() {
        assertTrue(
            overflowMenu.contains("moveToFolderLabel"),
            "Move to folder is offered only as a CustomAccessibilityAction, so a Switch Access or " +
                "keyboard user with TalkBack off has no route to it — and filing by drag has no " +
                "keyboard equivalent either. Menu slice: $overflowMenu",
        )
        assertTrue(
            overflowMenu.contains("DropdownMenuItem"),
            "the label appears in the slice but not as a menu item",
        )
    }

    @Test
    fun `move to folder is still offered as an accessibility action`() {
        // The control, and not a formality: the cheapest way to make the assertion above pass is to
        // move the action rather than add to it, which would fix keyboard users by breaking the
        // TalkBack route that already worked.
        assertTrue(
            source.contains("CustomAccessibilityAction(moveToFolderLabel)"),
            "the accessibility action was removed rather than matched by a menu entry",
        )
    }
}
