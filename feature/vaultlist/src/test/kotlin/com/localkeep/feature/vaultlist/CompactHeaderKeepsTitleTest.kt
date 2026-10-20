package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The short-window header keeps the vault's name and its count.
 *
 * F-34 (device pass 2026-09-15): in landscape the vault list drops its title row, which left the
 * only screen in the app with no name on it and removed the one line stating how large the vault is.
 * Dropping the *stacked* header is right — it took 194dp of a 359dp window and left the list a 45dp
 * viewport — but the title at [com.localkeep.core.ui.theme.LkText.cardTitle] on one line, between
 * two buttons that are drawn anyway, costs nothing the row's touch target was not already spending.
 *
 * A source scan over the `else` branch: the alternative is an instrumented landscape run, and this
 * pins the two facts that were actually lost. The filter controls below are out of scope by
 * decision and are not asserted on here.
 */
class CompactHeaderKeepsTitleTest {

    private val screen = File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultListScreen.kt").readText()

    @Test
    fun `the compact row still names the screen and states the count`() {
        assertTrue(
            "vaultlist-compact-title" in screen,
            "the short-window header has no title node — F-34 is the landscape list with no name on it",
        )
        assertTrue(
            "vaultlist-compact-count" in screen,
            "the short-window header has no count line — the vault's size is stated nowhere else",
        )
        assertTrue(
            "state.countLabel()" in screen.substringAfter("vaultlist-compact-title"),
            "the compact count must be the same countLabel the full header renders, not a second " +
                "way of saying it",
        )
    }

    @Test
    fun `the compact title is a smaller style than the full header's`() {
        val compact = screen.substringAfter("vaultlist-compact-title")
        assertFalse(
            "LkText.screenTitle" in compact.substringBefore("LkLockButton()"),
            "the compact row must not use the full header's 21sp screenTitle — the height it saves " +
                "is the whole point of the short-window branch",
        )
        assertTrue(
            "LkText.cardTitle" in screen.substringBefore("vaultlist-compact-title").takeLast(600) ||
                "LkText.cardTitle" in compact.take(600),
            "the compact title should be LkText.cardTitle",
        )
    }

    /**
     * Control: the buttons the 2026-09-11 fix recovered are still there. Losing them is the worse
     * regression — the drawer is the only route to nine destinations and the lock button is the only
     * lock-on-demand — and a rewrite of this row is exactly when that would happen again.
     */
    @Test
    fun `the compact row still carries the drawer and lock buttons`() {
        val compact = screen.substringAfter("vaultlist-compact-title").substringBefore("openedWithRecoveryKit")
        assertTrue("LkLockButton()" in compact, "the short-window header lost the lock button")
        assertTrue(
            "nav-open-drawer" in screen.substringBefore("vaultlist-compact-title").takeLast(1200),
            "the short-window header lost the drawer button",
        )
    }
}
