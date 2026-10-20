package com.localkeep.feature.health

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The health score card stacks on the width it is given, not on font scale alone (SCL-021).
 *
 * The card used to stack only at `fontScale >= LkText.stackAboveFontScale` (1.5). Below that it kept
 * the ring beside the prose at every width, and at 308dp — the narrowest window the app supports —
 * the card's content is 228dp: a 96dp ring at 1.0 left the prose 112dp, and at 1.3 the 125dp ring
 * left 83dp, so the ring dropped under the fold; at 1.45 the caption broke "attenti/on," in a 68dp
 * column. `LkText.stacks` adds the width rule; this pins both halves of it.
 *
 * Geometry, not `assertIsDisplayed`: both nodes report as displayed when they are squeezed side by
 * side. What the finding is about is whether the prose sits beside the ring or under it, so that is
 * what is compared. [GraphicsMode.Mode.NATIVE] makes the text measure at a real width.
 *
 * The 20dp frame margin on each side is the dashboard's own page gutter; without it the card would be
 * 308dp wide instead of the 268dp it gets, and the content 268 instead of 228.
 *
 * FAILS WITHOUT THE FIX: with `val stacked = fontScale >= LkText.stackAboveFontScale`, the 308dp /
 * 1.0 and 308dp / 1.3 cases measure the prose beside the ring, not under it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScoreCardStacksOnWidthTest {

    @get:Rule
    val compose = createComposeRule()

    private fun showCard() {
        compose.setContent {
            LocalKeepTheme {
                ScoreCard(
                    score = 72,
                    verdict = VERDICT,
                    advice = ADVICE,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun boundsOf(node: SemanticsNodeInteraction): Rect {
        val b = node.fetchSemanticsNode().boundsInRoot
        val d = compose.density.density
        return Rect(b.left / d, b.top / d, b.right / d, b.bottom / d)
    }

    private fun ringBounds(): Rect {
        val description = RuntimeEnvironment.getApplication()
            .getString(R.string.health_score_accessibility, 72)
        return boundsOf(compose.onNodeWithContentDescription(description))
    }

    private fun verdictBounds(): Rect = boundsOf(compose.onNodeWithText(VERDICT, useUnmergedTree = true))

    private fun assertStacked() {
        val ring = ringBounds()
        val verdict = verdictBounds()
        assertTrue(
            verdict.top >= ring.bottom - 1f,
            "the verdict starts at ${verdict.top}dp but the ring runs to ${ring.bottom}dp: the prose " +
                "is still beside the ring in a card too narrow to hold both ($ring, $verdict).",
        )
    }

    private fun assertSideBySide() {
        val ring = ringBounds()
        val verdict = verdictBounds()
        assertTrue(
            verdict.left >= ring.right - 1f && verdict.top < ring.bottom,
            "the verdict ($verdict) is no longer beside the ring ($ring). At this width the card " +
                "has room for both and must keep the layout the comps draw.",
        )
    }

    /** The finding: the narrowest window, at the default text size. */
    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h640dp-xhdpi", fontScale = 1.0f)
    fun theCardStacksAt308dpEvenAtDefaultTextSize() {
        showCard()
        assertStacked()
    }

    /** The finding's other photographed point: below the 1.5 switch, at the floor width. */
    @Test
    @Config(sdk = [34], qualifiers = "w308dp-h640dp-xhdpi", fontScale = 1.3f)
    fun theCardStacksAt308dpAtFontScaleOnePointThree() {
        showCard()
        assertStacked()
    }

    /** The control: 360dp at the default size keeps the ring beside the prose, to the pixel. */
    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi", fontScale = 1.0f)
    fun theCardStaysSideBySideAt360dp() {
        showCard()
        assertSideBySide()
    }

    /** The control for the font-scale half: big type still stacks, whatever the width. */
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h640dp-xhdpi", fontScale = 2.0f)
    fun bigTypeStillStacksAtAnyWidth() {
        showCard()
        assertStacked()
    }

    private companion object {
        const val VERDICT = "Good, with a few things to look at"
        const val ADVICE = "A handful of passwords are weak or reused, and one has appeared in a known breach."
        val PAGE_GUTTER = 20.dp
    }
}
