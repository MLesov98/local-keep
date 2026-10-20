package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The vault list's viewport is the whole window, not what a pinned header leaves of it.
 *
 * History, because this test changed meaning. It used to pin `Box(Modifier.weight(1f))` against
 * `fillMaxSize()`: with a header column above the list, `fillMaxSize` asked for the parent's height
 * rather than the remainder, overflowing the window by the header's height — which took
 * `imePadding()` to subtract the whole keyboard and made `listBounds`, the edge drag autoscroll
 * reads, report a bottom below the screen (carried since 2026-08-27).
 *
 * The header is not above the list any more: it is the list's first item (SCL-007, see
 * [VaultListBodyScalingTest]), so there is no remainder to take and the old failure cannot recur by
 * that route. What can recur is somebody putting a sibling back above the list, which would
 * reintroduce both the overflow and the 0dp viewport. That is what this guards.
 *
 * ### Why this reads the source
 *
 * `VaultListScreen` is Hilt-backed and full-screen, and a `createComposeRule` render of it never
 * reaches idle under Robolectric — the limitation `NewFolderCardAutoFocusTest` records for this
 * module. The layout that matters is measured by [VaultListBodyScalingTest]; this only pins which
 * composable the screen puts it in.
 */
class ListViewportWeightTest {

    private fun screen(): String {
        val file = File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultListScreen.kt")
        if (!file.isFile) fail("cannot find ${file.absolutePath} — this test reads the source it guards")
        return file.readText()
    }

    @Test
    fun `the drop-target box holds the list body and nothing above it`() {
        val code = screen()
        val anchor = code.indexOf("""LkDropTargetKey("drop-here")""")
        if (anchor < 0) fail("could not find the whole-screen drop target — has the list changed shape?")
        val opening = code.lastIndexOf("Box(", anchor)
        if (opening < 0) fail("could not find the Box that carries the drop target")
        val body = code.indexOf("VaultListBody(", anchor)
        if (body < 0) fail("the drop-target box no longer contains VaultListBody")
        val between = code.substring(anchor, body)
        assertTrue(
            "Column(" !in between && "Spacer(" !in between,
            "something sits between the drop target and the list body — a header pinned above the " +
                "list is the SCL-007 layout: $between",
        )
    }

    @Test
    fun `the list is bounded by the keyboard on the list itself`() {
        val code = screen()
        val call = code.substring(code.indexOf("VaultListBody("))
        val modifier = call.substring(call.indexOf("modifier = Modifier"), call.indexOf("headerModifier"))
        assertTrue("listBounds = it.boundsInRoot()" in modifier, "drag autoscroll lost its viewport: $modifier")
        // The bound is applied by the body itself, and it is not `imePadding()`: that counted the
        // keyboard twice over the shell's tab-bar slot (L-02, see [ListEndsAtTheKeyboardTest]).
        assertTrue(".imePadding()" !in modifier, "the screen is back on imePadding(): $modifier")
        val body = File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultListBody.kt").readText()
        assertTrue(
            "Modifier.aboveKeyboard().then(modifier)" in body,
            "VaultListBody lost the keyboard bound on its list",
        )
        assertTrue(".imePadding()" !in body, "VaultListBody is back on imePadding()")
    }
}
