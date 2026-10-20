package com.localkeep.core.ui.theme

import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The grouping is display-only, so its whole contract is: the glyphs gain spaces, the value does
 * not, and the caret still lands where the user put it.
 *
 * That last part is the reason this file exists. Offset mapping across inserted characters is the
 * classic place a `VisualTransformation` goes wrong, and it goes wrong invisibly: the number stores
 * and matches perfectly while the caret jumps a character every time it crosses a gap, which reads
 * as "the keyboard is broken" and gets filed against the wrong component.
 */
class LkCardNumberTransformationTest {

    private fun render(value: String, groups: LkCardDigitGroups = LkCardDigitGroups.Default) =
        LkCardNumberTransformation(groups).filter(AnnotatedString(value))

    @Test
    fun `a sixteen digit number renders in four groups of four`() {
        assertEquals("4242 4242 4242 4242", render("4242424242424242").text.text)
    }

    @Test
    fun `an amex number renders four-six-five, the way it is printed`() {
        assertEquals(
            "3782 822463 10005",
            render("378282246310005", LkCardDigitGroups.Amex).text.text,
            "grouping an Amex as 4-4-4-4 puts breaks where the card itself has none",
        )
    }

    @Test
    fun `a partial number groups as far as it goes and no further`() {
        assertEquals("4242 42", render("424242").text.text)
    }

    @Test
    fun `a number longer than any scheme keeps flowing rather than being cut off`() {
        // The same field holds loyalty and membership numbers. Dropping their tail would lose data
        // the user can see they typed.
        // Past the scheme's length the digits are regrouped in fours from the right, so a masked
        // tail ("last four") is one group rather than straddling two.
        assertEquals("424 2424 2424 2424 2999", render("4242424242424242999").text.text)
    }

    @Test
    fun `the value itself is never mutated`() {
        val digits = "4242424242424242"
        val filtered = render(digits)
        assertEquals(19, filtered.text.text.length, "three spaces should be added for display")
        assertEquals(
            digits,
            buildString { filtered.text.text.forEach { if (it != ' ') append(it) } },
            "the transformation must add spaces and change nothing else",
        )
    }

    @Test
    fun `every caret position survives the round trip through the inserted spaces`() {
        val digits = "4242424242424242"
        val mapping = render(digits).offsetMapping
        for (original in 0..digits.length) {
            val transformed = mapping.originalToTransformed(original)
            assertEquals(
                original,
                mapping.transformedToOriginal(transformed),
                "caret at digit $original did not survive the round trip",
            )
        }
    }

    @Test
    fun `a caret crossing a group boundary skips the inserted space`() {
        val mapping = render("4242424242424242").offsetMapping
        // "4242 4242…" — the caret after four digits sits at the group edge, and the fifth digit
        // is one further along than it would be without the space.
        assertEquals(4, mapping.originalToTransformed(4))
        assertEquals(6, mapping.originalToTransformed(5))
        assertEquals(4, mapping.transformedToOriginal(5), "the space itself belongs to the digit before it")
    }

    @Test
    fun `amex offsets follow the uneven group sizes`() {
        val mapping = render("378282246310005", LkCardDigitGroups.Amex).offsetMapping
        // 3782|822463|10005 — one space before digit 4, two before digit 10.
        assertEquals(4, mapping.originalToTransformed(4))
        assertEquals(11, mapping.originalToTransformed(10))
        for (original in 0..15) {
            assertEquals(original, mapping.transformedToOriginal(mapping.originalToTransformed(original)))
        }
    }

    @Test
    fun `group totals match the schemes they describe`() {
        assertEquals(16, LkCardDigitGroups.Default.total)
        assertEquals(15, LkCardDigitGroups.Amex.total)
    }
}
