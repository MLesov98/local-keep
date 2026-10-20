package com.localkeep.core.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LkMonogramTest {

    @Test
    fun `initials come from the first two words, short ones included`() {
        // "BO", not "BI": the second word is "of". The doc comment claimed BI for years and no test
        // had ever asked. Recorded as the behaviour it is — skipping stopwords would be a change to
        // what every tile says, not a fix.
        assertEquals("BO", lkMonogram("Bank of Ireland"))
        assertEquals("GO", lkMonogram("Google"))
    }

    @Test
    fun `an untitled item still gets a tile`() {
        assertEquals("?", lkMonogram("   "))
    }

    @Test
    fun `punctuation separates words instead of becoming an initial`() {
        // The whole of 1B-F10 / 2A-F9 / 4C-19, on two devices 2026-09-20: the four imported
        // "(BW)" items rendered L(, M(, P( and V(, and a bracket in an avatar reads as a
        // rendering fault rather than as an initial.
        assertEquals("LB", lkMonogram("Licence (BW)"))
        assertEquals("MB", lkMonogram("Mastercard (BW)"))
        // Still the first two words where there are two real ones — the bracketed suffix is third.
        assertEquals("WN", lkMonogram("Wifi note (BW)"))
    }

    @Test
    fun `a filename's separators are separators`() {
        // Every `t-*` file imported by sweep 4C rendered the identical `T-`, so a file-heavy vault
        // was a column of indistinguishable tiles.
        assertEquals("TA", lkMonogram("t-archive.zip"))
        assertEquals("TV", lkMonogram("t-vector.svg"))
    }

    @Test
    fun `digits give way to letters, but not to nothing`() {
        // `323.png` is the system photo picker's own DISPLAY_NAME, and `32` is not a monogram.
        assertEquals("PN", lkMonogram("323.png"))
        // Where the digit is all the word has to offer, it is still better than "?".
        assertEquals("1P", lkMonogram("1Password"))
        assertEquals("20", lkMonogram("2026"))
    }

    @Test
    fun `a title that is nothing but punctuation still gets a tile`() {
        assertEquals("?", lkMonogram("()"))
    }

    @Test
    fun `an emoji first word survives as the whole emoji, not half a surrogate pair`() {
        // "💰" is outside the BMP, so in UTF-16 it is a high surrogate followed by a low one.
        // Taking Char zero took the high surrogate alone and the tile rendered a replacement box.
        val monogram = lkMonogram("💰 Savings")
        assertFalse(monogram[0].isHighSurrogate() && !monogram[1].isLowSurrogate(), "unpaired surrogate in $monogram")
        assertEquals("💰S", monogram)
    }

    @Test
    fun `a lone BMP character is unaffected`() {
        assertEquals("AB", lkMonogram("alpha beta"))
    }

    @Test
    fun `a single-word emoji title survives as the whole emoji, not half a surrogate pair`() {
        // Same hazard as the two-word case above, but in the words.size == 1 branch, which took by
        // Char instead of by code point: the second UTF-16 unit of "A💰" is a lone high
        // surrogate, and take(2) kept it unpaired.
        val monogram = lkMonogram("A💰")
        assertFalse(monogram[1].isHighSurrogate() && (monogram.length < 3 || !monogram[2].isLowSurrogate()), "unpaired surrogate in $monogram")
        assertEquals("A💰", monogram)
    }
}
