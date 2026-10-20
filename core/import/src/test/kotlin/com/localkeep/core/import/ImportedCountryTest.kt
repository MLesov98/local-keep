package com.localkeep.core.import

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 1Password writes an address's country as a lowercased ISO 3166-1 alpha-2 code, so an imported
 * passport rendered `1 Analytical St / London E1 6AN / gb` — while the same document imported from
 * Bitwarden, which writes free text, showed `UK` (1A-F10, 2026-09-20).
 */
class ImportedCountryTest {

    private val english = Locale.ENGLISH

    @Test
    fun `a two-letter code becomes the country's name`() {
        assertEquals("United Kingdom", ImportedCountry.of("gb", english))
        assertEquals("Germany", ImportedCountry.of("DE", english))
    }

    /** The user's own words are never rewritten — only a bare code is interpreted. */
    @Test
    fun `text that is not a bare code is returned exactly`() {
        assertEquals("United Kingdom", ImportedCountry.of("United Kingdom", english))
        assertEquals("UK", ImportedCountry.of("UK", english), "two letters, and already a name")
        assertEquals("GBR", ImportedCountry.of("GBR", english))
        assertEquals("", ImportedCountry.of("", english))
        assertEquals("  ", ImportedCountry.of("  ", english))
    }

    /** A code nothing can name is still shown as a code, not as lower case. */
    @Test
    fun `an unknown code falls back to its upper-case form`() {
        assertEquals("ZZ", ImportedCountry.of("zz", english))
    }

    @Test
    fun `the name follows the locale it is asked for`() {
        assertEquals("Allemagne", ImportedCountry.of("de", Locale.FRENCH))
    }
}
