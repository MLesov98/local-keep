package com.localkeep.core.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A placeholder is a sentence, and a sentence does not get a smaller budget as the type grows.
 *
 * F-1 (device pass, 2026-09-17): at 360dp and font scale 2.0 the secure note's placeholder was cut
 * in **every** shipped language — en `Too sensitive for a plai…`, fr `Trop sensible pour un …`,
 * de `Zu sensibel für eine N…`, es `Muy sensible para una…`, pt `Muito sensível para u…`
 * (screenshots at 360dp and the largest font scale, 2026-09-17). German is the
 * shortest string in the catalogue at 31 characters and clipped too, so the translations were not
 * the problem: the well drew the placeholder at `maxLines = 1` at every text size, and doubling the
 * text size halves the characters a line holds.
 *
 * The rule is arithmetic on the font scale, so the arithmetic is what is pinned here; the rendering
 * it drives is measured in [LkPlaceholderGrowsWithFontScaleTest], which is the half that can see a
 * well actually grow. Both are needed — this one can pass while nothing is wired to it, which is
 * why the last test below reads the call site.
 *
 * FAILS WITHOUT THE FIX: there was no `lkPlaceholderMaxLines`, and the placeholder `Text` in
 * `LkTextField` carried a literal `maxLines = 1`.
 */
class LkPlaceholderMaxLinesTest {

    /** The default size, and the comps' layout. An empty one-line well stays one line tall. */
    @Test
    fun `the default text size keeps a placeholder on one line`() {
        assertEquals(1, lkPlaceholderMaxLines(1.0f))
    }

    /** The three scales below the step render exactly what they rendered before the fix. */
    @Test
    fun `the scales below the step are unchanged`() {
        assertEquals(1, lkPlaceholderMaxLines(0.85f))
        assertEquals(1, lkPlaceholderMaxLines(1.15f))
        assertEquals(1, lkPlaceholderMaxLines(1.3f))
    }

    /** The scale the finding was measured at. */
    @Test
    fun `the largest accessibility scale gets a second line`() {
        assertEquals(2, lkPlaceholderMaxLines(2.0f))
    }

    /** And every scale between the step and it, so the fix is not tuned to one device setting. */
    @Test
    fun `every scale past the step gets a second line`() {
        assertEquals(2, lkPlaceholderMaxLines(1.31f))
        assertEquals(2, lkPlaceholderMaxLines(1.5f))
        assertEquals(2, lkPlaceholderMaxLines(1.8f))
    }

    /**
     * Two, never three. A placeholder is app-authored prose of a known length, not a user-named
     * string with no bound — a third line would buy nothing and would push the primary action of a
     * short form down for a field that is still empty.
     */
    @Test
    fun `the budget never exceeds two lines`() {
        for (scale in listOf(2.5f, 3.0f, 10.0f)) {
            assertEquals(2, lkPlaceholderMaxLines(scale), "font scale $scale")
        }
    }

    /**
     * A multi-line well lets its placeholder use the rows it already has. The note's sentence was cut
     * to "Too sensitive for a plain n…" at 308dp and font 1.3 beside three empty lines of well
     * (device sweep, 2026-10-28): the one-line step is about characters per line, and a narrow window
     * takes them away exactly as a bigger font does. "Never three" above is about single-line wells,
     * where a third line would push the form down; here the rows are already reserved.
     */
    @Test
    fun `a multi-line well lets its placeholder wrap at any scale`() {
        for (scale in listOf(0.85f, 1.0f, 1.3f, 1.5f, 2.0f)) {
            assertEquals(3, lkPlaceholderMaxLines(scale, singleLine = false), "font scale $scale")
        }
    }

    /** The control: a single-line well is exactly what it was. */
    @Test
    fun `a single-line well keeps the stepped budget`() {
        assertEquals(1, lkPlaceholderMaxLines(1.3f, singleLine = true))
        assertEquals(2, lkPlaceholderMaxLines(2.0f, singleLine = true))
    }

    /**
     * And the well is wired to it. A pure rule nothing calls is the same defect as no rule: the
     * function would have every assertion above passing while the field still drew one line.
     */
    @Test
    fun `the placeholder actually asks the rule how many lines it may have`() {
        val fields = File("src/main/kotlin/com/localkeep/core/ui/theme/LkFields.kt").readText()
        assertTrue(
            "maxLines = lkPlaceholderMaxLines(LocalDensity.current.fontScale, singleLine)" in fields,
            "the placeholder in LkTextField does not take its line budget from " +
                "lkPlaceholderMaxLines — a rule nothing calls changes nothing on screen",
        )
    }
}
