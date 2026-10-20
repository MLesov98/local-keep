package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An unreadable 2FA secret is marked on its own field (2026-09-20, release sweep 4B-3).
 *
 * The bug this closes: a secret that is not decodable Base32 — `ABCDEF01`, where 0 and 1 are not in
 * the alphabet — got as far as Save, which answered with the generic "Check the item fields" banner
 * and marked **nothing**. The preview card disappeared, the secret well kept its ordinary ring, and
 * the refusal named no field, on the one field a 2FA item cannot do without. DIGITS and PERIOD
 * beside it were given per-field marks to end exactly that failure mode.
 *
 * The predicate is asked of the decoder rather than of a regex, so it cannot drift from what
 * `toContent` will accept: length and trailing bits are rejected too, not only the alphabet.
 */
class TotpSecretCarriesItsOwnErrorTest {

    private fun secret(value: String, digits: String = "6", period: String = "30") =
        ItemEditUiState(
            type = ItemEditUiState.ItemType.TOTP,
            totpSecret = value,
            totpDigits = digits,
            totpPeriodSec = period,
        )

    @Test
    fun `a secret using characters outside the Base32 alphabet is marked`() {
        assertTrue(secret("ABCDEF01").totpSecretIsUnreadable(), "0 and 1 are not Base32")
    }

    @Test
    fun `a valid secret is not marked`() {
        assertFalse(secret("JBSWY3DPEHPK3PXP").totpSecretIsUnreadable())
    }

    @Test
    fun `whitespace and case are normalised before judging, exactly as saving does`() {
        assertFalse(secret("jbswy3dp ehpk3pxp").totpSecretIsUnreadable())
    }

    @Test
    fun `a key of an impossible length is marked, not only a bad character`() {
        // The alphabet alone would pass this; the decoder is what catches it, which is why the
        // question is asked of the decoder.
        assertTrue(secret("ABC").totpSecretIsUnreadable())
    }

    @Test
    fun `an empty field is not an error`() {
        // The user may still be typing, and a blank required field has its own refusal.
        assertFalse(secret("").totpSecretIsUnreadable())
    }

    @Test
    fun `a bad value in DIGITS does not paint the secret red as well`() {
        // Those two carry their own marks. Attributing one field's mistake to another is the
        // untraceable refusal this whole change exists to end.
        assertFalse(secret("JBSWY3DPEHPK3PXP", digits = "7").totpSecretIsUnreadable())
        assertFalse(secret("JBSWY3DPEHPK3PXP", period = "0").totpSecretIsUnreadable())
    }

    /**
     * And the form actually uses it — for the well's ring and for the caption, which is what names
     * the problem. A mark with no sentence is the colour-only signalling this repo rejects
     * elsewhere: a TalkBack user gets nothing from a ring.
     */
    @Test
    fun `the form marks the well and says what is wrong`() {
        val screen =
            File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt").readText()
        val manual = screen.substringAfter("val secretUnreadable = state.totpSecretIsUnreadable()", "")
            .substringBefore("CheckboxRow(")

        assertTrue(manual.isNotEmpty(), "the secret well moved; this test no longer reads it")
        assertTrue("isError = secretUnreadable" in manual, "the well carries no error ring")
        assertTrue(
            "R.string.itemedit_totp_secret_error" in manual,
            "the well is marked but nothing names the problem",
        )
    }
}
