package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The card wizard's typing layout is a function of one number — the keyboard's progress — so what it
 * is allowed to do is pinned here as arithmetic, not as a screenshot.
 *
 * Measured on the owner's phone profile (1080×2340 at 450dpi, font 1.15): the full face is
 * 588px tall, the strip 186px, the card margin 16dp = 45px. The keyboard's inset animates smoothly
 * (about 370ms to show, 210ms to hide) and the progress below follows it frame for frame — see the
 * probe log in the fix plan.
 *
 * Every test names the change that turns it red.
 */
class CardWizardKeyboardTest {

    private val full = 588
    private val strip = 186
    private val margin = 45
    private val fade = faceRegionFadeEnd(full, strip, margin)

    private fun grid(): List<Float> = (0..200).map { it / 200f }

    // ── keyboard progress ───────────────────────────────────────────────────────────────────────

    @Test
    fun `no keyboard is zero progress`() {
        assertEquals(0f, imeOpenness(0, 0, 0))
    }

    @Test
    fun `a keyboard rising is a fraction of where it is going`() {
        assertEquals(0.5f, imeOpenness(source = 0, target = 660, current = 330))
    }

    @Test
    fun `a keyboard falling is a fraction of where it started`() {
        assertEquals(0.25f, imeOpenness(source = 660, target = 0, current = 165))
    }

    @Test
    fun `a settled keyboard is fully open`() {
        assertEquals(1f, imeOpenness(660, 660, 660))
    }

    /** Numeric to QWERTY: the keyboard stays up and only changes height — it must not read as a dip. */
    @Test
    fun `a resize with the keyboard staying up is fully open throughout`() {
        assertEquals(1f, imeOpenness(source = 660, target = 760, current = 700))
        assertEquals(1f, imeOpenness(source = 760, target = 660, current = 700))
    }

    /**
     * A hardware keyboard still leaves the keyboard's navigation row as an inset (126px measured on a
     * 1080x2400 phone). Turns red if the minimum is dropped: the card would fold to the strip with
     * the whole screen free.
     */
    @Test
    fun `the navigation row a hardware keyboard leaves behind is not a keyboard`() {
        assertEquals(0f, imeOpenness(source = 126, target = 126, current = 126, minKeyboard = 262))
        assertEquals(0f, imeOpenness(source = 0, target = 126, current = 63, minKeyboard = 262))
    }

    /** The control for the test above: a real keyboard is unaffected by the minimum. */
    @Test
    fun `a real keyboard is still a keyboard when the minimum is set`() {
        assertEquals(0.5f, imeOpenness(source = 0, target = 820, current = 410, minKeyboard = 262))
        assertEquals(1f, imeOpenness(source = 820, target = 820, current = 820, minKeyboard = 262))
    }

    // ── the face region ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `at rest the region is the full face, fully visible, with no strip`() {
        assertEquals(full, faceRegionHeight(0f, full, strip, fade))
        assertEquals(1f, fullFaceAlpha(0f, fade))
        assertEquals(0f, stripAlpha(0f, fade))
    }

    @Test
    fun `with the keyboard fully up the region is the strip, fully visible, with no full face`() {
        assertEquals(strip, faceRegionHeight(1f, full, strip, fade))
        assertEquals(0f, fullFaceAlpha(1f, fade))
        assertEquals(1f, stripAlpha(1f, fade))
    }

    /**
     * The invariant that keeps a cut card off the screen: the region only starts to lose height once
     * the full face has faded out. Turns red if the region shrinks from progress 0 — the full face
     * would be clipped through its outline while still visible.
     */
    @Test
    fun `the full face is invisible before the region loses any height`() {
        for (f in grid()) {
            if (faceRegionHeight(f, full, strip, fade) < full) {
                assertTrue(
                    fullFaceAlpha(f, fade) <= 0.02f,
                    "at progress $f the region is shorter than the face but the face is still drawn " +
                        "(alpha ${fullFaceAlpha(f, fade)})",
                )
            }
        }
    }

    @Test
    fun `the strip is whole before the region starts to shrink`() {
        assertEquals(1f, stripAlpha(fade, fade), 0.001f)
    }

    @Test
    fun `the region never grows as the keyboard rises`() {
        var last = Int.MAX_VALUE
        for (f in grid()) {
            val h = faceRegionHeight(f, full, strip, fade)
            assertTrue(h <= last, "height rose from $last to $h at progress $f")
            last = h
        }
    }

    /** A linear cross-fade (fade end 1) leaves both faces half-visible at the midpoint. */
    @Test
    fun `the cross-fade is finished within the card margin, not spread over the whole rise`() {
        assertTrue(fade < 0.2f, "fade end $fade — the faces would overlap for most of the animation")
        assertEquals(margin.toFloat() / (full - strip), fade, 0.001f)
    }

    // ── structure the layout depends on ─────────────────────────────────────────────────────────

    private val wizard = File("src/main/kotlin/com/localkeep/feature/itemedit/CardWizard.kt").readText()

    /**
     * `onConsumedWindowInsetsChanged` has to come before `imePadding()` in the modifier chain: it
     * reports what the *ancestors* consumed, and progress is measured on the same inset `imePadding`
     * applies. Reversed, the 24dp navigation bar is counted as keyboard and the layout opens early.
     */
    @Test
    fun `consumed insets are read before imePadding`() {
        // The root column both wizards share lives in WizardFrame; assert the card wizard uses it.
        assertTrue("WizardFrame(" in wizard, "the card wizard no longer draws through WizardFrame")
        val frame = File("src/main/kotlin/com/localkeep/feature/itemedit/WizardFrame.kt").readText()
        val consumed = frame.indexOf("keyboard.consumedInsetsModifier()")
        val padding = frame.indexOf(".imePadding()")
        assertTrue(consumed in 0 until padding, "consumed=$consumed imePadding=$padding")
    }

    private val keyboardSource =
        File("src/main/kotlin/com/localkeep/feature/itemedit/CardWizardKeyboard.kt").readText()

    /**
     * The strip is pinned to the top of the viewport while the keyboard is up, and drawn above the
     * form that scrolls beneath it. Turns red if either goes: on a screen too short for the strip and
     * the focused field together (731dp measured) the card would scroll out of sight, or the form
     * would be drawn over it.
     */
    @Test
    fun `the strip is pinned to the top of the viewport while typing`() {
        assertTrue(
            "scroll.value * stripAlpha(" in keyboardSource,
            "the region must be translated by the scroll offset, weighted by how far the strip has taken over",
        )
        assertTrue(".zIndex(1f)" in keyboardSource, "the pinned strip must draw above the scrolling form")
    }

    /**
     * Validate on submit: the length hint is drawn only for a challenge. It used to fire from the
     * first digit — 14 of 16 keystrokes on a Visa — and sat under the pinned buttons.
     */
    @Test
    fun `the length hint is only drawn once Continue has been tapped on it`() {
        assertTrue(
            "if (!challenged || !CardNumberValidator.isImplausible(brand, digitCount)) return" in wizard,
            "CardNumberLengthHint must return unless the user has been challenged",
        )
        assertTrue(
            "CardNumberLengthHint(brand, state.cardNumber.count(Char::isDigit), challenged = true)" in wizard,
            "the only call site must be the challenged branch",
        )
    }
}
