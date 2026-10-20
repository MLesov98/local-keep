package com.localkeep.core.ui.theme

import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Prose styles carry an optimised line break, so a sentence does not end on a one-word last line.
 *
 * The greedy platform breaker filled each line and left whatever remained, which on a two-line
 * caption was often a single orphaned word ("added.", "version."). Headings get the balanced break
 * and prose the high-quality one.
 *
 * The control is [LkText.body]: text fields default to it, and a re-wrapping break would make the
 * words jump lines under the caret on every keystroke. It, and the mono and secret styles, must stay
 * on the platform default.
 */
class LkProseLineBreakTest {

    @Test
    fun `prose styles use the paragraph break`() {
        assertEquals(LineBreak.Paragraph, LkText.bodyQuiet.lineBreak)
        assertEquals(LineBreak.Paragraph, LkText.subtitle.lineBreak)
        assertEquals(LineBreak.Paragraph, LkText.caption.lineBreak)
    }

    @Test
    fun `heading styles use the balanced break`() {
        listOf(LkText.display, LkText.title, LkText.heading, LkText.screenTitle, LkText.sheetTitle).forEach {
            assertEquals(LineBreak.Heading, it.lineBreak)
        }
    }

    @Test
    fun `the styles a text field edits in are left on the platform break`() {
        assertEquals(LineBreak.Unspecified, LkText.body.lineBreak, "LkTextField defaults to body")
        listOf(LkText.monoField, LkText.secretInput, LkText.masked).forEach {
            assertEquals(LineBreak.Unspecified, it.lineBreak)
        }
        assertNotEquals(LkText.bodyQuiet.lineBreak, LkText.body.lineBreak)
    }

    /**
     * German compounds broke mid-word everywhere (SCL-010, SCL-018, SCL-019: "Passw/ord", "Gener/ator",
     * "Neuer Ordner" in four fragments) because the app had no hyphenation policy. Headers and prose
     * hyphenate at syllables instead, in whatever locale the text is in; the platform breaker does
     * nothing about a word that does not fit.
     */
    @Test
    fun `header and prose styles hyphenate automatically`() {
        listOf(
            LkText.display, LkText.title, LkText.heading, LkText.screenTitle, LkText.sheetTitle,
            LkText.cardTitle, LkText.navLabel, LkText.bodyQuiet, LkText.subtitle, LkText.caption,
        ).forEach {
            assertEquals(Hyphens.Auto, it.hyphens)
        }
    }

    /**
     * The control, and the reason the list above is not "everything". A text field edits in
     * [LkText.body], and a value being typed (a password, a mono value, a masked secret) must never
     * be hyphenated - the soft hyphen would be drawn inside a secret.
     */
    @Test
    fun `body, mono and secret styles never hyphenate`() {
        listOf(
            LkText.body, LkText.monoField, LkText.monoValue, LkText.monoCaption, LkText.secretInput,
            LkText.masked, LkText.numeralLarge,
        ).forEach {
            assertTrue(it.hyphens != Hyphens.Auto, "a style that renders a value must not hyphenate")
        }
    }
}
