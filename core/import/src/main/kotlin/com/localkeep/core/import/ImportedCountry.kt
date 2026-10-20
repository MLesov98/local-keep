package com.localkeep.core.import

import java.util.Locale

/**
 * A source export's country cell, as something a person reads.
 *
 * 1Password writes an address's country as a lowercased ISO 3166-1 alpha-2 code, so an imported
 * passport rendered `1 Analytical St / London E1 6AN / gb` — while the same document imported from
 * Bitwarden, which writes free text, showed `UK` (1A-F10, 2026-09-20).
 *
 * Only a bare two-letter code is touched. Anything else is the user's own text and is returned
 * exactly as written: a source that already spells the country out must not have its wording
 * rewritten, and a three-letter or numeric code is not something this can name without guessing.
 *
 * [locale] decides the language of the name and defaults to the device's, because the value is
 * written into the vault at import time and there is no later render to resolve it at — the same
 * trade [ImportDefaultTitles] documents for fallback titles. A parameter, not a hidden read, so a
 * test can state which language it is asserting.
 */
internal object ImportedCountry {

    private val ALPHA2 = Regex("[A-Za-z]{2}")

    /**
     * Assigned codes only. `getDisplayCountry` answers an unassigned one with *"Unknown Region"* —
     * a sentence, in place of the two characters the file actually held, which is worse than the
     * code it replaced. Membership is asked of the platform's own list rather than guessed.
     */
    private val ASSIGNED: Set<String> = Locale.getISOCountries().toSet()

    fun of(cell: String, locale: Locale = Locale.getDefault()): String {
        val trimmed = cell.trim()
        if (!ALPHA2.matches(trimmed)) return cell
        val region = trimmed.uppercase(Locale.ROOT)
        if (region !in ASSIGNED) return region
        val named = runCatching {
            Locale.Builder().setRegion(region).build().getDisplayCountry(locale)
        }.getOrNull().orEmpty()
        return named.ifBlank { region }
    }
}
