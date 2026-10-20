package com.localkeep.core.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The vault row → item detail tile morph, and the two ways it silently breaks.
 *
 * Both failures were measured on a device at `animator_duration_scale 10` on 2026-08-10, and
 * neither is visible in a build log, a screenshot of either end state, or a unit test of the
 * composable — which is why they survived a shipped release.
 */
class MonogramMorphTest {

    private val source =
        File("src/main/kotlin/com/localkeep/core/ui/theme/LkContainers.kt").readText()

    /**
     * `sharedElement` remeasures its content at every intermediate size; `sharedBounds` with
     * `scaleToBounds` measures once and scales, which is what the design does
     * (`transform: scale(w/60)`) and the only thing that moves the glyph.
     *
     * Under `sharedElement` the monogram jumped to its destination size on the first frame and the
     * tile grew around it: the container ran 140→158px while the glyph sat at exactly 61×41px in
     * every single frame, and on the way back the glyph was 28px tall from the first frame while
     * the container ran 129→110. Swapping back to `sharedElement` compiles, looks reasonable in
     * review, and restores that bug in full.
     */
    @Test
    fun `the monogram morph scales its content instead of remeasuring it`() {
        assertTrue(
            "sharedBounds(" in source && "scaleToBounds()" in source,
            "LkMonogramTile must morph with sharedBounds + scaleToBounds, or the monogram snaps to " +
                "its destination size on the first frame and the tile grows around a glyph that " +
                "never moves.",
        )
        assertFalse(
            "Modifier.sharedElement(" in source,
            "sharedElement remeasures the content at every size — see the measurements above.",
        )
    }

    /**
     * A shared element is drawn in the `SharedTransitionLayout` overlay, outside either screen's
     * fade. Any duration beyond the navigation transition is therefore time the tile spends at full
     * opacity over a screen that has already finished arriving — which is what "the icon tries to
     * escape the screen" was describing.
     *
     * The design's hero is slightly *longer* than its screen transition (.52s against .46s) and can
     * afford to be: it has no overlay and no back transition at all. Copying that ratio here is what
     * produced the defect, so the two land together instead.
     */
    @Test
    fun `the morph does not outlast the screen transition it belongs to`() {
        assertTrue(
            "LkMotion.Screen" in source,
            "The monogram morph must run at LkMotion.Screen so it lands with the navigation " +
                "transition rather than continuing over a settled screen.",
        )
        assertTrue(
            LkMotion.Screen <= LkMotion.Panel,
            "LkMotion.Screen (${LkMotion.Screen}ms) is the morph's duration and must not exceed " +
                "the navigation crossing.",
        )
    }

    /**
     * The glow moved inside the shared node when the morph became a scale. Left on a wrapper
     * outside it, the wrapper stays composed at *both* ends for the length of the crossing, so a
     * tile-shaped glow with no tile in it sits at the destination while the tile is still in
     * flight. That was the flicker.
     */
    @Test
    fun `the glow travels with the tile`() {
        val sharedNode = source.substringAfter(".then(sharedModifier)")
        assertTrue(
            "drawBehind" in sharedNode.substringBefore(".lkSurface("),
            "The halo must be drawn inside the node carrying the shared modifier and before " +
                "lkSurface, or it is left behind at both ends of the morph.",
        )
    }
}
