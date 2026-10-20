package com.localkeep.core.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import kotlin.math.abs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A dialog must stay inside the display, and its two actions must stay reachable.
 *
 * [LkDialogSurface] is the container behind every dialog in the app — thirty-six call sites — and
 * it constrained one axis. `widthIn`, and on the other axis nothing at all: no maximum, no scroll.
 * A Column with no upper bound does not clip and does not scroll, it simply keeps laying out, so a
 * dialog whose content outgrew the screen was severed rather than bounded. Measured here before the
 * fix, on a 360x640dp phone at font scale 2.0 with a two-sentence body: the surface reported the
 * full 640dp of the display and *both* buttons reported a bottom of 614dp — the same pixel, one
 * behind the other, jammed against the bottom edge with the body cut off above them. That is what
 * "the actions are gone" looks like from a semantics tree. There is no gesture out of it, and a
 * dialog raised with `dismissible = false` does not even have Back.
 *
 * It reaches production two ways, and both are tested: a body long enough to overflow (the longer
 * languages, and any dialog listing folders or licences), and a large accessibility font scale.
 *
 * ### `performScrollTo` and node bounds, never `assertIsDisplayed`
 *
 * `assertIsDisplayed` cannot see this defect. A dialog is its own window, and a node in a window
 * the size of the display still reports as displayed while sitting underneath the button drawn on
 * top of it — the same trap `FolderDeleteDialogLocaleTest` records, where padding a body with 700
 * characters passed that assertion cleanly. Two instruments do fail correctly, and the fix needs
 * both halves, so both are asserted on the same rendering:
 *
 * - [performScrollTo] requires a scrollable ancestor and throws when there is none. That is the
 *   half that lets an oversize dialog move.
 * - Bounds arithmetic, for the half that gives it somewhere to move. A scroll container with no
 *   height limit is exactly as tall as its content, so it never scrolls; the viewport has to be
 *   smaller than the content before scrolling means anything.
 *
 * ### Two settings that are load-bearing, not boilerplate
 *
 * [GraphicsMode.Mode.NATIVE] is the difference between this file testing something and testing
 * nothing. Under Robolectric's legacy graphics there is no real text layout: the first run of this
 * test measured a 4,000-character body at 119dp, so no amount of text could overflow anything and
 * every assertion here passed on the broken code. With native graphics the same body measures past
 * the display, which is the point.
 *
 * The 360x640dp qualifier is a real compact phone rather than the flagship this repo is usually
 * driven on. A 411x891dp screen absorbs font scale 2.0 with room to spare, so a test written
 * against one would only ever have caught the long-body half.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LkDialogBoundsTest {

    @get:Rule
    val compose = createComposeRule()

    private val screenHeightDp: Float
        get() = RuntimeEnvironment.getApplication().resources.configuration.screenHeightDp.toFloat()

    private fun showDialog(body: String) {
        compose.setContent {
            LocalKeepTheme {
                LkDialog(
                    onDismiss = {},
                    title = TITLE,
                    body = body,
                    // Tags the surface itself: LkDialog hands its `modifier` straight to
                    // LkDialogSurface, so this is the node whose height the fix bounds.
                    modifier = Modifier.testTag(SURFACE),
                    confirm = LkDialogAction(
                        label = CONFIRM,
                        onClick = {},
                        emphasis = LkActionEmphasis.Destructive,
                    ),
                    dismissLabel = DISMISS,
                )
            }
        }
        compose.waitForIdle()
    }

    /** Height of the tagged node, in dp. */
    private fun heightOf(tag: String): Float =
        compose.onNodeWithTag(tag).fetchSemanticsNode().size.height / compose.density.density

    /** How far the bottom of the tagged node sits below the top of the surface, in dp. */
    private fun bottomWithinSurface(tag: String): Float {
        val surface = compose.onNodeWithTag(SURFACE).fetchSemanticsNode()
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        return (node.positionOnScreen.y + node.size.height - surface.positionOnScreen.y) /
            compose.density.density
    }

    /**
     * Both actions, not either. A dialog that keeps only its destructive button is worse than one
     * that keeps neither: the safe way out is the one that must never be the casualty of a long
     * sentence.
     */
    private fun assertActionsAreReachable(case: String) {
        for (tag in listOf(CONFIRM_TAG, DISMISS_TAG)) {
            // Throws when the surface has no scrollable ancestor — the pre-fix state.
            compose.onNodeWithTag(tag).performScrollTo()
            val bottom = bottomWithinSurface(tag)
            val height = heightOf(SURFACE)
            assertTrue(
                bottom <= height + TOLERANCE_DP,
                "[$case] after scrolling to it, \"$tag\" ends ${bottom}dp down a surface only " +
                    "${height}dp tall. The dialog scrolls but is not bounded, so there was no " +
                    "viewport to scroll within and the button is still outside the window.",
            )
        }
    }

    @Test
    fun aBodyTooLongForTheScreenKeepsBothActionsReachable() {
        showDialog(LONG_BODY)
        assertActionsAreReachable("long body")
    }

    @Test
    @Config(fontScale = 2.0f)
    fun theLargestAccessibilityFontScaleKeepsBothActionsReachable() {
        showDialog(TALL_BODY)
        assertActionsAreReachable("fontScale 2.0")
    }

    /**
     * An oversize dialog is still a dialog, not a page.
     *
     * The bound is the display less one rung of margin at each end, matching the order of the
     * margin the surface already keeps on its left and right. Without it the Column is sized by the
     * dialog window instead, which lands it flush against all four edges of the screen — measured
     * at exactly 640.0dp on a 640dp display before the fix.
     */
    @Test
    fun anOversizeDialogStopsShortOfTheDisplayEdges() {
        showDialog(LONG_BODY)
        val height = heightOf(SURFACE)
        val ceiling = screenHeightDp - MARGIN_DP
        assertTrue(
            height <= ceiling + TOLERANCE_DP,
            "the dialog surface measured ${height}dp against a ${screenHeightDp}dp display, past " +
                "the ${ceiling}dp ceiling. LkDialogSurface has no height bound of its own, so it " +
                "is being sized by the dialog window and runs edge to edge.",
        )
    }

    /**
     * The control, and the reason the fix is a maximum rather than a height.
     *
     * The ordinary delete confirmation measured 332.5dp on this screen before the change, and must
     * measure 332.5dp after it: the surface still wraps its content, nowhere near the ceiling, with
     * both actions inside it and nothing to scroll. A fix that stretched the surface toward the
     * bound would satisfy every assertion above and silently restyle all thirty-six call sites,
     * which in a codebase where dialog geometry is specified is the worse regression of the two.
     */
    @Test
    fun anOrdinaryDialogRendersExactlyAsItDid() {
        showDialog(SHIPPING_BODY)
        val height = heightOf(SURFACE)
        assertTrue(
            abs(height - ORDINARY_HEIGHT_DP) <= TOLERANCE_DP,
            "an ordinary dialog measured ${height}dp where it measured ${ORDINARY_HEIGHT_DP}dp " +
                "before the height bound and the scroll were added. The change was supposed to be " +
                "inert at the default text size.",
        )
        val dismissBottom = bottomWithinSurface(DISMISS_TAG)
        assertTrue(
            dismissBottom <= height + TOLERANCE_DP,
            "the dismiss button ends ${dismissBottom}dp down a ${height}dp surface at the default " +
                "text size — an ordinary dialog must need no scrolling at all.",
        )
    }

    private companion object {
        const val SURFACE = "dialog-surface-under-test"

        /** The tags `LkDialog` puts on its own two actions — see `LkDialogConfirm`. */
        const val CONFIRM_TAG = "dialog-confirm"
        const val DISMISS_TAG = "dialog-dismiss"

        const val TITLE = "Delete \"Arbeitsunterlagen\"?"
        const val CONFIRM = "Delete forever"
        const val DISMISS = "Keep folder"

        /** Sub-pixel slack: these are float divisions of integer pixel bounds, not round numbers. */
        const val TOLERANCE_DP = 1f

        /** `LkSpace.lg` at each end, which is what the fix subtracts from the display height. */
        val MARGIN_DP = LkSpace.lg.value * 2

        /**
         * Measured on this configuration at font scale 1.0, before the fix, and unchanged by it.
         * The number is the assertion — see [anOrdinaryDialogRendersExactlyAsItDid].
         */
        const val ORDINARY_HEIGHT_DP = 332.5f

        /** Roughly the length the shipping folder-delete confirmation runs to in German. */
        const val SHIPPING_BODY =
            "This folder and the 1000 items in it will be deleted permanently. Folders have no " +
                "trash, so this cannot be undone."

        /**
         * Fits at the default text size, does not at 2.0. That pairing is what makes the font-scale
         * test about the font scale: a body that overflowed at 1.0 too would pass or fail for the
         * long-body reason and prove nothing about dynamic type.
         */
        val TALL_BODY = "$SHIPPING_BODY $SHIPPING_BODY"

        /** Past any phone at any text size — the case the layout genuinely cannot absorb. */
        val LONG_BODY = "$SHIPPING_BODY ".repeat(40)
    }
}
