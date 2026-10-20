package com.localkeep.feature.vaultlist

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Folder rows dodge the FAB the way item rows have since F-12.
 *
 * 2A-F13, on a device 2026-09-20 in landscape: `fab-add-item [2195,556][2347,708]` over
 * `vaultlist-folder-row [288,566][2368,692]` ("Folder Work, 6 items"). The Work row's count and
 * chevron were drawn under the FAB while the Personal row's `6 ›` beside it was perfectly legible,
 * so the one thing a folder row says about how much is inside it was hidden on the row nearest the
 * thumb.
 *
 * Nothing interactive is lost — the whole row is one target and one TalkBack stop — which is why
 * this is cosmetic and not a reach defect. It is still the row contradicting itself.
 *
 * The geometry is [fabDodgeOffset]'s, already pinned by [FabDodgeAtRestTest]; what is new here is
 * that a *folder* row reaches for it at all, and that the vault list hands it the same at-rest
 * bounds it hands the item rows. That wiring is source-scanned for the reason that test records:
 * `VaultListScreen` is a Hilt-backed full screen and never reaches idle under Robolectric.
 */
class FolderRowDodgesTheFabTest {

    private val gap = 8f

    /** The landscape geometry from the finding, with the FAB and the row's cluster overlapping. */
    private val fab = Rect(left = 2195f, top = 556f, right = 2347f, bottom = 708f)
    private val countAndChevron = Rect(left = 2280f, top = 594f, right = 2368f, bottom = 664f)

    @Test
    fun `the covered count slides clear of the fab`() {
        val shift = fabDodgeOffset(fab, countAndChevron, gap)
        assertTrue(shift.x < 0, "expected a leftward shift, got $shift")
        assertEquals(
            (fab.left - gap).toInt(),
            (countAndChevron.right + shift.x).toInt(),
            "the cluster should come to rest one gap clear of the fab",
        )
    }

    @Test
    fun `the sibling row that was already legible does not move`() {
        // "Personal", the next row down and clear of the FAB's 708 bottom edge: the device showed
        // its `6 ›` unobstructed, and it must stay put or the strip gains the several-x-positions
        // defect F-12 was about.
        val sibling = Rect(left = 2280f, top = 760f, right = 2368f, bottom = 830f)
        assertEquals(IntOffset.Zero, fabDodgeOffset(fab, sibling, gap))
    }

    @Test
    fun `the folder row asks for the dodge, and the list gives it the at-rest bounds`() {
        val row = source("FolderBrowsing.kt")
        assertTrue(
            "avoidanceBounds: () -> Rect? = { null }" in row,
            "FolderBrowseRow must accept avoidance bounds, defaulted to none for the picker, the " +
                "folders screen and the tests",
        )
        assertTrue(
            "fab = avoidanceBounds()," in row,
            "and read them into fabDodgeOffset",
        )
        assertTrue(
            "onGloballyPositioned { clusterAnchor = it.boundsInWindow() }" in row,
            "the anchor must hang on the wrapper, never on a control that is itself offset — see " +
                "fabDodgeOffset's own note about a shift becoming its own trigger",
        )

        val screen = source("VaultListScreen.kt")
        assertEquals(
            4,
            Regex("""fabDodgeBounds\(fabWindowBounds, listState\.isScrollInProgress\)""")
                .findAll(screen).count(),
            "every reader — the item rows, the folder rows, the header's search row (which the " +
                "scrolling header can put under the FAB) and the empty-vault panel (whose copy can " +
                "sit beside it in a short window, SCL-037) — must take the same at-rest bounds, or " +
                "the strip and the list disagree about when a dodge is allowed",
        )
    }

    private fun source(name: String): String {
        val file = File("src/main/kotlin/com/localkeep/feature/vaultlist/$name")
        if (!file.isFile) fail("cannot find ${file.absolutePath} — this test reads the source it guards")
        return file.readText()
    }
}
