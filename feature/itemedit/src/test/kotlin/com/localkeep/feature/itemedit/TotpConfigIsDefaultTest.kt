package com.localkeep.feature.itemedit

import com.localkeep.core.model.TotpAlgorithm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Gates whether `TotpDetail` (ItemDetailScreen.kt) prints the "Code settings" row — F41,
 * 2026-09-13: the algorithm, digit count and period are stored and editable but never surfaced in
 * the read view, so a user debugging a rejected code from an atypical import had no way to see the
 * one value with no other tell (digit count and Steam mode are already visible in the rendered
 * code's own shape; the algorithm is not).
 *
 * Most TOTP entries are the ordinary RFC 6238 default (SHA1, 6 digits, 30s), so the row is shown
 * only when the entry departs from it — the common case gets no extra line, and the atypical import
 * this exists for is exactly the case that turns the row on.
 */
class TotpConfigIsDefaultTest {
    private val base = ItemEditUiState()

    @Test
    fun `the RFC 6238 default is recognised as default`() {
        assertTrue(
            totpConfigIsDefault(
                base.copy(totpAlgorithm = TotpAlgorithm.SHA1, totpDigits = "6", totpPeriodSec = "30"),
            ),
        )
    }

    @Test
    fun `a non-default algorithm is not the default`() {
        assertFalse(
            totpConfigIsDefault(
                base.copy(totpAlgorithm = TotpAlgorithm.SHA256, totpDigits = "6", totpPeriodSec = "30"),
            ),
        )
    }

    @Test
    fun `a non-default digit count is not the default`() {
        assertFalse(
            totpConfigIsDefault(
                base.copy(totpAlgorithm = TotpAlgorithm.SHA1, totpDigits = "8", totpPeriodSec = "30"),
            ),
        )
    }

    @Test
    fun `a non-default period is not the default`() {
        assertFalse(
            totpConfigIsDefault(
                base.copy(totpAlgorithm = TotpAlgorithm.SHA1, totpDigits = "6", totpPeriodSec = "60"),
            ),
        )
    }

    /**
     * The row must never appear on a Steam entry, however atypical its stored parameters are.
     *
     * `TotpGenerator.generateSteam` hard-codes SHA1, a 30-second window and a five-character
     * alphabet, so not one of the three values the row prints is consulted. `onTotpSteamToggled`
     * only flips the flag — the editor *hides* the algorithm/digits/period controls rather than
     * normalising them — so a `digits = "8"` typed before the toggle stays in the record, and the
     * row read "SHA1, 8 digits, 30s" directly beneath a rendered five-character code.
     *
     * Gated at the call site rather than inside [totpConfigIsDefault]: that function answers "does
     * this depart from RFC 6238's defaults", and folding `!steam` into it would invert its meaning
     * and print the row for every *ordinary* Steam entry — the tests above pin that meaning.
     */
    @Test
    fun `a Steam entry never shows the row, whatever it stores`() {
        assertFalse(
            totpConfigRowVisible(
                base.copy(
                    totpSteam = true,
                    totpAlgorithm = TotpAlgorithm.SHA1,
                    totpDigits = "8",
                    totpPeriodSec = "30",
                ),
            ),
        )
        assertFalse(
            totpConfigRowVisible(
                base.copy(
                    totpSteam = true,
                    totpAlgorithm = TotpAlgorithm.SHA512,
                    totpDigits = "8",
                    totpPeriodSec = "60",
                ),
            ),
        )
    }

    /**
     * The control. Must stay green: an ordinary entry that departs from the default still shows the
     * row, so a change that simply deleted the row would fail here rather than passing both.
     */
    @Test
    fun `a non-Steam entry with atypical settings still shows the row`() {
        assertTrue(
            totpConfigRowVisible(
                base.copy(
                    totpSteam = false,
                    totpAlgorithm = TotpAlgorithm.SHA256,
                    totpDigits = "8",
                    totpPeriodSec = "60",
                ),
            ),
        )
    }

    /** The second control: the ordinary RFC 6238 entry stays free of the extra line. */
    @Test
    fun `a non-Steam entry on the RFC 6238 default does not show the row`() {
        assertFalse(
            totpConfigRowVisible(
                base.copy(
                    totpSteam = false,
                    totpAlgorithm = TotpAlgorithm.SHA1,
                    totpDigits = "6",
                    totpPeriodSec = "30",
                ),
            ),
        )
    }

    /**
     * The same stored period is wrong in a second place: the countdown ring and the "refreshes
     * every N seconds" caption are both drawn on it, and a Steam code rolls on Steam's own
     * 30-second counter regardless. A Steam entry stored with `period = 60` advertised a 60-second
     * refresh over a code that changed at 30.
     */
    @Test
    fun `a Steam entry's ring and caption count on Steam's own period`() {
        assertEquals(30, totpDisplayPeriodSec(base.copy(totpSteam = true, totpPeriodSec = "60")))
        assertEquals(30, totpDisplayPeriodSec(base.copy(totpSteam = true, totpPeriodSec = "15")))
    }

    /**
     * The control. Must stay green: an ordinary entry still counts on its own stored period, so a
     * change that pinned every countdown to 30 would fail here rather than passing both.
     */
    @Test
    fun `a non-Steam entry counts on its own stored period`() {
        assertEquals(60, totpDisplayPeriodSec(base.copy(totpSteam = false, totpPeriodSec = "60")))
        assertEquals(30, totpDisplayPeriodSec(base.copy(totpSteam = false, totpPeriodSec = "30")))
    }

    /** Unparseable or nonsensical stored periods still fall back to 30, as they always have. */
    @Test
    fun `an unusable stored period falls back to thirty`() {
        assertEquals(30, totpDisplayPeriodSec(base.copy(totpSteam = false, totpPeriodSec = "")))
        assertEquals(30, totpDisplayPeriodSec(base.copy(totpSteam = false, totpPeriodSec = "0")))
    }
}
