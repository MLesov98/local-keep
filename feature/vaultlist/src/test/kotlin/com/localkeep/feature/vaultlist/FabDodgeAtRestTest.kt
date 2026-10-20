package com.localkeep.feature.vaultlist

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The FAB dodge, restored on one condition: only while the list is at rest.
 *
 * The scroll-driven version was deleted on 2026-09-16 (F-12) and took a real defect back with it.
 * At rest the FAB is on screen, it really is drawn on top of one row's action cluster, and a tap in
 * the overlap opens the add sheet instead of that row's menu — on the row nearest the thumb. What
 * F-12 was actually about is the *other* state: while the list scrolled, an easing band moved every
 * row near the FAB by a fraction of the full shift, so the action column sat at several different x
 * positions down one list (rows 1–4 put Copy at x=738, the row under the FAB at x=544).
 *
 * So the mechanism comes back with the band gone. Two facts hold it to "at rest only":
 *
 *  - [fabDodgeBounds] answers null for the whole of a scroll, so no dodge is computed then. The FAB
 *    is hidden anyway, but `AnimatedVisibility` keeps it composed and positioned for the 120ms of
 *    its exit, which is exactly long enough for a row to be seen sliding.
 *  - [fabDodgeOffset] tests intersection rather than proximity, so exactly one row moves and it
 *    moves by a fixed amount. There is no fraction of a shift left to render.
 *
 * Geometry below is the real one, read off a `uiautomator dump` at density 3.5: a 36dp (126px)
 * overflow button at the row's end is the cluster's rightmost control and therefore its anchor.
 */
class FabDodgeAtRestTest {

    private val gap = 8f

    /** The FAB, sitting at the bottom-right the way it does on the vault list. */
    private val fab = Rect(left = 840f, top = 1000f, right = 970f, bottom = 1130f)

    /** The row the FAB is drawn on top of. */
    private val clusterUnderFab = Rect(left = 928f, top = 1030f, right = 1054f, bottom = 1100f)

    /** Any other row: same x, a long way up the list. */
    private val clusterElsewhere = Rect(left = 928f, top = 200f, right = 1054f, bottom = 270f)

    @Test
    fun `the row under the fab dodges clear of it`() {
        val shift = fabDodgeOffset(fab, clusterUnderFab, gap)
        assertTrue(shift.x < 0, "expected a leftward shift, got $shift")
        assertEquals(0, shift.y, "the dodge is horizontal only")
        assertEquals(
            (fab.left - gap).toInt(),
            (clusterUnderFab.right + shift.x).toInt(),
            "the cluster should come to rest one gap clear of the fab",
        )
    }

    @Test
    fun `a row elsewhere in the list does not move`() {
        assertEquals(IntOffset.Zero, fabDodgeOffset(fab, clusterElsewhere, gap))
    }

    /**
     * The whole of F-12, pinned as a property rather than as a pixel: a row that does not overlap
     * gets *nothing*, not a fraction. The deleted mechanism eased the shift in over a 32dp approach,
     * which is what put the action column at several x positions at once.
     */
    @Test
    fun `there is no partial shift for a row merely near the fab`() {
        // 4px above the FAB's top edge: as close as a row can be without touching it.
        val justAbove = Rect(left = 928f, top = 930f, right = 1054f, bottom = 996f)
        assertEquals(
            IntOffset.Zero,
            fabDodgeOffset(fab, justAbove, gap),
            "a row 4px clear of the fab must not move at all",
        )
    }

    @Test
    fun `no dodge is computed while the list is scrolling`() {
        assertNull(fabDodgeBounds(fab, listScrolling = true))
        assertEquals(
            IntOffset.Zero,
            fabDodgeOffset(fabDodgeBounds(fab, listScrolling = true), clusterUnderFab, gap),
            "the row under the fab must be still while the list moves",
        )
    }

    @Test
    fun `at rest the fab's own bounds are what the rows see`() {
        assertSame(fab, fabDodgeBounds(fab, listScrolling = false))
    }

    /** Nulls are the ordinary first-frame state, before either rect has been positioned. */
    @Test
    fun `an unpositioned control does not move`() {
        assertEquals(IntOffset.Zero, fabDodgeOffset(null, clusterUnderFab, gap))
        assertEquals(IntOffset.Zero, fabDodgeOffset(fab, null, gap))
    }

    /**
     * The title column gives back exactly what the cluster takes.
     *
     * `Modifier.offset` moves a control after everything has been measured, so without this the
     * dodged plate is drawn straight through the last word of a title that wrapped to the full
     * width — measured 2026-09-06 at the default font scale.
     */
    @Test
    fun `the details column reserves exactly the distance the cluster travels`() {
        val shift = fabDodgeOffset(fab, clusterUnderFab, gap)
        assertEquals(-shift.x, trailingClusterReserve(fab, clusterUnderFab, gap))
        assertEquals(0, trailingClusterReserve(fab, clusterElsewhere, gap))
    }

    /**
     * And the two halves are wired to each other.
     *
     * `VaultListScreen` is a Hilt-backed full screen and never reaches idle under Robolectric — the
     * same limitation `NewFolderCardAutoFocusTest` records for this module — so the unambiguous fact
     * left to assert is the source. If either call site is reshaped these anchors stop matching and
     * this fails by name rather than passing silently.
     */
    @Test
    fun `the list hands the row its fab bounds only at rest`() {
        val screen = source("VaultListScreen.kt")
        assertTrue(
            "fabDodgeBounds(fabWindowBounds, listState.isScrollInProgress)" in screen,
            "VaultListScreen must gate the row's avoidance bounds on isScrollInProgress",
        )
        assertTrue(
            "onGloballyPositioned { fabWindowBounds = it.boundsInWindow() }" in screen,
            "the FAB must report its own window bounds",
        )
        assertTrue(
            "onDispose { fabWindowBounds = null }" in screen,
            "a disposed FAB must clear its bounds — a positioning callback cannot report a removal",
        )

        val row = source("VaultItemRow.kt")
        assertEquals(
            4,
            Regex("""fab = avoidanceBounds\(\),""").findAll(row).count(),
            "four readers of the same bounds: the quick-copy pill, the reprompt lock plate that " +
                "stands in for it, the overflow button, and the title column's reserve against the " +
                "shift they take",
        )
        assertTrue(
            "onGloballyPositioned { clusterAnchor = it.boundsInWindow() }" in row,
            "the anchor must hang on the wrapper, never on a control that is itself offset",
        )
    }

    private fun source(name: String): String {
        val file = File("src/main/kotlin/com/localkeep/feature/vaultlist/$name")
        if (!file.isFile) fail("cannot find ${file.absolutePath} — this test reads the source it guards")
        return file.readText()
    }
}
