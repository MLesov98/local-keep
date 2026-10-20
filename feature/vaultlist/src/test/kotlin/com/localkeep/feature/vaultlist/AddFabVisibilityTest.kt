package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the add FAB is on screen, and the dodge that no longer has to exist because of it.
 *
 * F-12 (device pass 2026-09-15): `shots/041-quickcopy.png` — rows 1–4 put Copy at x=738 and the
 * overflow at x=922; the row level with the FAB put them at x=544 and x=728, a 194px jog in the
 * action column. That jog was the row avoiding the FAB, which it had to do because a tap in the
 * overlap opened the add sheet instead of the row's menu.
 *
 * The decision (principal, 2026-09-16) removes the overlap instead of accommodating it: the FAB
 * leaves while the list scrolls and comes back when it rests, following the same precedent as the
 * IME condition beside it. Nothing in a row moves while the list moves, so every row's action
 * column shares one x for the whole of a scroll.
 *
 * **What that decision did not answer, and this file used to assert it had (2026-09-17).** A
 * *resting* FAB is on screen and really is drawn on top of one row's cluster, so deleting the dodge
 * outright left that row's "More actions" button answering a tap with the add sheet. The dodge is
 * back for exactly that case, with the easing band — the part that moved rows nothing was covering —
 * gone. The two absence tests below asserted the deletion rather than the decision and are replaced
 * by [FabDodgeAtRestTest], which pins both halves: a dodge at rest, none while scrolling.
 */
class AddFabVisibilityTest {

    private val screenSource = File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultListScreen.kt")

    @Test
    fun `the FAB is hidden while the list is scrolling and back when it rests`() {
        assertFalse(
            addFabVisible(keyboardUp = false, listScrolling = true),
            "a FAB on screen mid-scroll is a FAB sitting on top of a row's controls (F-12)",
        )
        assertTrue(
            addFabVisible(keyboardUp = false, listScrolling = false),
            "the add entry point has to come back the moment the list rests",
        )
    }

    /** Control: the older condition still holds on its own axis. */
    @Test
    fun `the FAB is still hidden while the keyboard is up`() {
        assertFalse(addFabVisible(keyboardUp = true, listScrolling = false))
        assertFalse(addFabVisible(keyboardUp = true, listScrolling = true))
    }

    /**
     * The transition, and the reason it has to be `lkTween`.
     *
     * `isScrollInProgress` flips on every flick, so this control appears and disappears far more
     * often than the IME condition beside it ever fires. A hard cut at that frequency reads as a
     * rendering fault rather than as a control stepping aside, which is the next visual pass's
     * finding rather than this one's fix.
     *
     * `lkTween` is what makes it honour reduced motion: it collapses to `tween(0)` when
     * `LocalLkReduceMotion` is set, so the swap is instant while the composition and disposal still
     * happen in the same order. A raw `tween(` here would animate on a setting the rest of the app
     * keeps — exactly the regression `ViewerChromeReducedMotionTest` was written for in
     * `feature/files`. Source-scanned for the same reason that file gives: the property is a claim
     * about which spec a call site passes, and the source answers it exactly.
     */
    @Test
    fun `the FAB animates its show and hide, and collapses under reduced motion`() {
        val screen = screenSource.readText()
        val fab = screen.substringAfter("visible = addFabVisible(").substringBefore("AddFab(")
        assertTrue(
            "AnimatedVisibility" in screen.substringBefore("visible = addFabVisible("),
            "the FAB's visibility must be an AnimatedVisibility, or there is nothing to play on exit",
        )
        listOf("fadeIn(", "fadeOut(", "scaleIn(", "scaleOut(").forEach { spec ->
            assertTrue(spec in fab, "the FAB's transition is missing $spec — a scale and a fade, both ways")
        }
        assertEquals(
            4,
            Regex(Regex.escape("lkTween(LkMotion.Instant)")).findAll(fab).count(),
            "all four halves of the transition must route through lkTween, which reads " +
                "LocalLkReduceMotion; a raw tween animates on a setting the user turned off",
        )
        assertFalse(
            Regex("""(?<![A-Za-z])tween\(LkMotion""").containsMatchIn(fab),
            "the FAB's transition calls a raw tween somewhere: $fab",
        )
    }

    /** The scale it shrinks to is a shrink, not a vanish — a control that goes to zero reads as destroyed. */
    @Test
    fun `the FAB shrinks rather than disappearing to a point`() {
        val scale = Regex("""FabHideScale = ([0-9.]+)f""").find(screenSource.readText())?.groupValues?.get(1)
        assertTrue(scale != null, "FabHideScale is gone — the transition has no stated end state")
        val value = scale!!.toFloat()
        assertTrue(value > 0f && value < 1f, "FabHideScale is $value; it must shrink without vanishing")
    }
}
