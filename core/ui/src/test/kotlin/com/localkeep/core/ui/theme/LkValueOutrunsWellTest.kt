package com.localkeep.core.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A value wider than its well fades out; it is never sliced through a glyph.
 *
 * 2026-09-15: a 20-character generated password in the item editor had its twentieth bullet **cut in
 * half** at the right edge of the text area — no ellipsis, no scroll hint, nothing to say the value
 * continued. At font scale 2 the same field sliced the twelfth (`shots/124-fs2-editor.png`). A
 * single-line `BasicTextField` clips at the box edge and follows the caret only while focused, so a
 * field being *read* rather than typed into shows half a glyph and calls it done (F-18).
 *
 * The trigger is a measurement of the drawn text against the measured box, which is why it holds at
 * any font scale rather than at the one it was tuned on. This pins the rule; the rendering around it
 * is Compose and is verified by the sources below.
 *
 * FAILS WITHOUT THE FIX: there was no `lkValueOutrunsWell`, and the `Box` holding the text field
 * carried nothing but `Modifier.weight(1f)`.
 */
class LkValueOutrunsWellTest {

    @Test
    fun `a value wider than the well is clipped`() {
        assertTrue(lkValueOutrunsWell(singleLine = true, valueWidthPx = 481, availableWidthPx = 480))
    }

    @Test
    fun `a value that fits is not`() {
        assertFalse(lkValueOutrunsWell(singleLine = true, valueWidthPx = 200, availableWidthPx = 480))
    }

    /**
     * Exactly filling the well is whole, not truncated. Fading the last glyph of a value that fits
     * would report a truncation that did not happen — which is the same category of lie as the
     * sliced glyph, pointing the other way.
     */
    @Test
    fun `a value that exactly fills the well is whole`() {
        assertFalse(lkValueOutrunsWell(singleLine = true, valueWidthPx = 480, availableWidthPx = 480))
    }

    /**
     * The first composition, before layout has run. Treating an unmeasured box as zero-width would
     * fade every field in the app for one frame — including the empty ones.
     */
    @Test
    fun `an unmeasured well never reports a clip`() {
        assertFalse(lkValueOutrunsWell(singleLine = true, valueWidthPx = 900, availableWidthPx = 0))
    }

    /** A multi-line well wraps, so it has no horizontal overflow to hide. */
    @Test
    fun `a multi-line well is never faded`() {
        assertFalse(lkValueOutrunsWell(singleLine = false, valueWidthPx = 5_000, availableWidthPx = 480))
    }

    /**
     * And the field is wired to it: the box reports its width, the drawn text is measured through
     * the *visual transformation* — bullets for a masked value, characters for a revealed one, and
     * those are different widths — and the fade is applied to the result.
     */
    @Test
    fun `the well measures what it actually draws, and fades on the answer`() {
        val fields = File("src/main/kotlin/com/localkeep/core/ui/theme/LkFields.kt").readText()
        assertTrue(
            "visualTransformation.filter(AnnotatedString(value)).text.text" in fields,
            "the well measures the raw value rather than the bullets it draws",
        )
        assertTrue(".onSizeChanged { contentWidthPx = it.width }" in fields)
        assertTrue(".lkFadeTrailingEdge(clipped)" in fields)
        // Measured against the font scale, not against a character count: `measurer` is remembered
        // with the density and the font resolver, so naming it as a key is what re-measures when the
        // user changes their system text size — the case shots/124 was captured in.
        assertTrue("remember(drawnText, textStyle, measurer)" in fields)
    }

    /**
     * And the bullets are never capped to make the value fit. `PasswordVisualTransformation` draws
     * one bullet per character; drawing fewer would desync the run from the caret and from the real
     * length, which is a worse defect than the one being fixed.
     */
    @Test
    fun `the fix never shortens what is drawn`() {
        val fields = File("src/main/kotlin/com/localkeep/core/ui/theme/LkFields.kt").readText()
        val transformation = fields.substringAfter("visualTransformation = if (revealed)", "")
            .substringBefore("\n")
        assertTrue("PasswordVisualTransformation('•')" in transformation)
        assertTrue("coerceAtMost" !in transformation && "take(" !in transformation)
    }
}
