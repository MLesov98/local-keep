package com.localkeep.feature.itemedit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How many lines the detail screen lets two rows occupy, and why neither number is a taste call.
 *
 * Both rules answer the same clause of the UI/UX spec — critical content is not truncated at large
 * font sizes — and both were violated by a literal `maxLines` that had no font scale in it. They
 * are tested here rather than on a device because the rule is arithmetic; what a device would add
 * is the *width*, and every assertion below is deliberately written to hold at any width.
 */
class DetailFieldLineCapsTest {

    // --- A revealed secret is read, not glimpsed ---------------------------------------------

    @Test
    fun `a masked secret stays on exactly one line`() {
        // The invariant, and the reason the function takes a flag rather than being deleted
        // outright: wrapped bullets are a length channel. This assertion is expected to hold both
        // before and after the fix — it is here to catch a future "just uncap it" that uncaps the
        // masked branch along with the revealed one.
        assertEquals(1, secretValueMaxLines(revealed = false))
    }

    @Test
    fun `a revealed recovery phrase is never capped below the number of words it has`() {
        // The longest secret this app's own editors produce. The phrase editor offers 12/15/18/21/
        // 24 words, and SecretReadField receives them joined by spaces. Synthetic on purpose — one
        // BIP-39 word repeated — so nothing that reads like a real recovery phrase is committed.
        val words = List(24) { "abandon" }

        // A floor that needs no width measurement and therefore holds on every device: text breaks
        // at word boundaries, so the narrowest column that renders this phrase at all puts one word
        // per line and needs 24. A cap of 24 is therefore sufficient at every width, and any cap
        // below it truncates the phrase at *some* width. The shipped cap of three truncated it at
        // every width, including the default font scale on a normal phone.
        assertTrue(
            secretValueMaxLines(revealed = true) >= words.size,
            "a ${words.size}-word phrase needs at least ${words.size} lines; " +
                "the cap is ${secretValueMaxLines(revealed = true)}",
        )
    }

    @Test
    fun `revealing raises the cap rather than merely changing the glyphs`() {
        // The masked and revealed branches share one Text call, so it is possible to "fix" the
        // reveal by making the bullets wrap too. Stated as a relation so that answer stays wrong.
        assertTrue(secretValueMaxLines(revealed = true) > secretValueMaxLines(revealed = false))
    }

    // --- The website row identifies the site, so it must stay identifying ---------------------

    @Test
    fun `the website row is a single line at the default font scale and the small bumps`() {
        // Inert where nothing is wrong. Rounding rather than ceiling is what buys this: the row
        // only grows once the text has genuinely outgrown one line, not the first pixel above 1.0.
        assertEquals(1, siteRowMaxLines(1f))
        assertEquals(1, siteRowMaxLines(1.15f))
        assertEquals(1, siteRowMaxLines(1.3f))
    }

    @Test
    fun `the website row gains a line as the character budget halves`() {
        // Doubling the text size halves what a line holds, so holding maxLines flat shrinks the
        // budget in characters — which is how two URLs sharing a prefix came to render identically
        // up to the ellipsis on the one row that exists to tell them apart.
        assertEquals(2, siteRowMaxLines(1.5f))
        assertEquals(2, siteRowMaxLines(2f))
        assertTrue(siteRowMaxLines(2f) > siteRowMaxLines(1f))
    }

    @Test
    fun `the website row stops growing at three lines`() {
        // A URL is app-shaped rather than user-authored prose, so it needs less headroom than the
        // vault list's title — but it is not bounded either, hence a ceiling rather than none.
        assertEquals(3, siteRowMaxLines(3f))
        assertEquals(3, siteRowMaxLines(10f))
    }

    @Test
    fun `the website row never collapses to zero lines below the default scale`() {
        // A Text with maxLines = 0 renders nothing at all, so the floor is not decoration: the
        // system's small-text settings put fontScale under 1.0 and rounding alone would reach 0.
        assertEquals(1, siteRowMaxLines(0.85f))
        assertEquals(1, siteRowMaxLines(0.4f))
    }
}
