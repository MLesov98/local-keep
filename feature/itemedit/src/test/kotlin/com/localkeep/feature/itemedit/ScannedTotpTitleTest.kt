package com.localkeep.feature.itemedit

import com.localkeep.core.model.Totp
import com.localkeep.core.model.TotpAlgorithm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Scanning a 2FA QR code names the item (2026-07-29).
 *
 * The bug this closes: `scanQrCode` populated issuer, account, secret, algorithm, digits, period
 * and the Steam flag — and never `title`. Title is required, so a successful scan of a GitHub code
 * left the app knowing the issuer was GitHub while Save stayed blocked on an empty field, with the
 * answer visible in the next row down. Asking for something the system has already derived.
 */
class ScannedTotpTitleTest {

    private fun totp(
        issuer: String = "GitHub",
        account: String = "user@example.com",
    ) = Totp(
        issuer = issuer,
        account = account,
        secretBase32 = "JBSWY3DPEHPK3PXP",
        algorithm = TotpAlgorithm.SHA1,
        digits = 6,
        periodSec = 30,
    )

    // The standalone 2FA wizard's state: `presetType(TOTP)` has already run by the time its
    // chooser renders. It matters here because `ItemEditUiState`'s default type is LOGIN, and
    // since 2026-09-20 a scan on a Login keeps the login (see LoginKeepsItsTypeWhenGainingTotpTest).
    private val blank = ItemEditUiState(type = ItemEditUiState.ItemType.TOTP)

    @Test
    fun `a scan names the item after its issuer`() {
        assertEquals("GitHub", blank.withScannedTotp(totp()).title)
    }

    @Test
    fun `a scan leaves nothing for the user to type before saving`() {
        val scanned = blank.copy(titleError = true).withScannedTotp(totp())

        assertTrue(scanned.title.isNotBlank(), "Save would still be blocked on an empty Title")
        assertFalse(scanned.titleError, "the required-field caption must clear with the field")
    }

    @Test
    fun `an issuer-less code falls back to the account name`() {
        // Some issuers omit the parameter entirely; the account is the only name available.
        assertEquals(
            "user@example.com",
            blank.withScannedTotp(totp(issuer = "")).title,
        )
    }

    @Test
    fun `a re-scan never overwrites a name the user chose`() {
        // Correcting a mistyped seed by scanning again must not silently rename the item.
        val named = blank.copy(title = "Work GitHub")
        assertEquals("Work GitHub", named.withScannedTotp(totp()).title)
    }

    @Test
    fun `a code carrying no name at all leaves the title empty rather than inventing one`() {
        val anonymous = blank.withScannedTotp(totp(issuer = "", account = ""))

        assertEquals("", anonymous.title)
        // And the required-field error must NOT be cleared — there is genuinely nothing there, so
        // clearing it would hide a real refusal.
        assertTrue(blank.copy(titleError = true).withScannedTotp(totp(issuer = "", account = "")).titleError)
    }

    @Test
    fun `whitespace in the issuer does not become a title made of spaces`() {
        // `isBlank` on the source, `trim` on the result — a title of three spaces passes a
        // `isNotEmpty` check and renders as an unnamed row.
        assertEquals("user@example.com", blank.withScannedTotp(totp(issuer = "   ")).title)
    }

    @Test
    fun `a scan carries every field the code supplied`() {
        val scanned = blank.withScannedTotp(totp())

        assertEquals(ItemEditUiState.ItemType.TOTP, scanned.type)
        assertEquals("GitHub", scanned.totpIssuer)
        assertEquals("user@example.com", scanned.totpAccount)
        assertEquals("JBSWY3DPEHPK3PXP", scanned.totpSecret)
        assertEquals("6", scanned.totpDigits)
        assertEquals("30", scanned.totpPeriodSec)
    }
}
