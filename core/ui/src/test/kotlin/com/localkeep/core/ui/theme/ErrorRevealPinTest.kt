package com.localkeep.core.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scroll-pin defect class, which has shipped twice, finally gets a signal.
 *
 * The symptom the second time (`010651b`): saving with an empty title left the item editor
 * **unscrollable in both directions**. The cause was an unsatisfiable `bringIntoView` — an errored
 * `LkTextField` asks to reveal its well *plus* room for the caption beneath it, and once the IME had
 * shortened the viewport that rect no longer fit anywhere. The scroller cannot satisfy an impossible
 * request from any position, so it re-corrected every frame and undid each gesture as it arrived.
 *
 * ## Why this is a unit test and not a Compose one
 *
 * The first attempt was a Compose test, and it **passed identically before and after the fix**: a
 * `createComposeRule` window has no IME, so the viewport never shortens and the request is always
 * satisfiable. A test that cannot fail is worse than no test, so it was deleted — one step too
 * early, because the conclusion drawn was "this is untestable" when the true conclusion was "the IME
 * is not the defect".
 *
 * The defect is *a reveal request whose rect exceeds the space available to satisfy it*. The IME is
 * one way to produce that; a fixed-height container is another; and the arithmetic that decides it
 * is the rule itself. So the arithmetic is what is pinned here, and — unlike its predecessor — it
 * goes red the moment the clamp is removed.
 *
 * A second attempt at driving this through Compose was written and abandoned on the same day: with
 * the reveal fighting the scroller the composition never becomes idle, so `waitForIdle()` hangs
 * rather than fails. A hanging test reports nothing at all, which is the failure mode this whole
 * exercise exists to avoid.
 */
class ErrorRevealPinTest {

    private companion object {
        /** `LkTextField.minHeight`, the 48dp touch floor, at density 1. */
        const val WELL = 48f

        /** `ErrorReveal` — one caption line plus its spacer. */
        const val CAPTION = 48f
    }

    /**
     * The regression. This is the geometry that shipped the bug: a viewport shorter than the rect,
     * which is what onboarding step 2 looked like once the keyboard was up.
     */
    @Test
    fun `a request never exceeds the viewport that has to satisfy it`() {
        val viewport = 72f

        val requested = errorRevealHeightPx(
            wellHeightPx = WELL,
            errorRevealPx = CAPTION,
            availableHeightPx = viewport,
        )

        assertTrue(
            requested <= viewport,
            "asked to reveal ${requested}px into a ${viewport}px viewport — a request that cannot " +
                "be satisfied from any scroll position, so every gesture is undone by it",
        )
    }

    /**
     * The other half, and the one a lazy fix would break: with room to spare, the caption still
     * comes with the field. Clamping unconditionally would stop the pin and quietly restore the
     * original complaint — a red ring whose explanation is off-screen.
     */
    @Test
    fun `a viewport with room to spare still reveals the caption`() {
        assertEquals(
            WELL + CAPTION,
            errorRevealHeightPx(WELL, CAPTION, availableHeightPx = 1_800f),
        )
    }

    /**
     * The floor. A viewport shorter than the well itself is a degenerate layout, and scrolling to a
     * fraction of the field would reintroduce "a focused field you cannot see" while fixing the pin.
     * The well is the minimum useful reveal, so the clamp stops there.
     */
    @Test
    fun `the clamp never falls below the field itself`() {
        assertEquals(WELL, errorRevealHeightPx(WELL, CAPTION, availableHeightPx = 20f))
    }

    /**
     * Before the window has been measured there is no viewport to clamp against, and guessing one
     * would scroll against a layout that does not exist yet. Unknown means unclamped: the request is
     * exactly what it was before this function existed.
     */
    @Test
    fun `an unmeasured viewport clamps nothing`() {
        assertEquals(WELL + CAPTION, errorRevealHeightPx(WELL, CAPTION, availableHeightPx = 0f))
        assertEquals(WELL + CAPTION, errorRevealHeightPx(WELL, CAPTION, availableHeightPx = -5f))
    }

    /**
     * The IME arriving is the transition that broke it, so it is asserted as a transition rather
     * than as two unrelated numbers: the same field, the same caption, one window, keyboard down
     * then up. Before, the whole rect fits. After, it must have shrunk to fit — and it must still
     * be asking for something.
     */
    @Test
    fun `the request shrinks when the keyboard takes the viewport`() {
        val window = 800f
        val imeHeight = 740f

        val closed = errorRevealHeightPx(WELL, CAPTION, availableHeightPx = window)
        val open = errorRevealHeightPx(WELL, CAPTION, availableHeightPx = window - imeHeight)

        assertEquals(WELL + CAPTION, closed)
        assertTrue(open < closed, "the request ignored the keyboard and stayed at ${open}px")
        assertTrue(open <= window - imeHeight, "still larger than what the keyboard left")
        assertTrue(open >= WELL, "shrank past the field itself")
    }
}
