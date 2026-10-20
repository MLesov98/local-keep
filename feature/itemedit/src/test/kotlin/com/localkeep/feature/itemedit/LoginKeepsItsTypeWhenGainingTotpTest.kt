package com.localkeep.feature.itemedit

import com.localkeep.core.model.Totp
import com.localkeep.core.model.TotpAlgorithm
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Adding a second factor to a Login leaves it a Login (2026-09-20, release sweep 4A-F3, blocker).
 *
 * The bug this closes: `withScannedTotp` set `type = ItemType.TOTP` unconditionally, and every
 * route in — the QR scanner and the "Paste setup link" field alike — lands there. So using the
 * Login editor's own Two-factor card converted the item to a standalone 2FA code: the header
 * flipped from `Login` to `2FA code`, and because `toContent` then built a `Totp` payload, Save
 * threw away the username, password, websites, notes and password history. Silent, unconfirmed
 * and irreversible, on the only advertised path for adding a factor to a login.
 *
 * The state fields are shared on purpose (see `withLoginTotp`), so preserving the type is the whole
 * fix: a Login already renders and saves exactly these values as its inline factor.
 */
class LoginKeepsItsTypeWhenGainingTotpTest {

    private fun totp() = Totp(
        issuer = "GitHub",
        account = "convert@example.test",
        secretBase32 = "JBSWY3DPEHPK3PXP",
        algorithm = TotpAlgorithm.SHA1,
        digits = 6,
        periodSec = 30,
    )

    private val login = ItemEditUiState(
        type = ItemEditUiState.ItemType.LOGIN,
        title = "4A TOTP Convert",
        username = "convert@example.test",
        password = "hunter2",
        uris = listOf("example.test"),
        loginNotes = "kept",
    )

    @Test
    fun `a login gaining a second factor stays a login`() {
        assertEquals(ItemEditUiState.ItemType.LOGIN, login.withScannedTotp(totp()).type)
    }

    @Test
    fun `a login gaining a second factor keeps the credentials it already had`() {
        val scanned = login.withScannedTotp(totp())

        assertEquals("convert@example.test", scanned.username)
        assertEquals("hunter2", scanned.password)
        assertEquals(listOf("example.test"), scanned.uris)
        assertEquals("kept", scanned.loginNotes)
        assertEquals("4A TOTP Convert", scanned.title)
    }

    @Test
    fun `the factor still lands in the fields a login saves from`() {
        // `toContent`'s LOGIN branch reads these, so this is what makes the factor persist.
        val scanned = login.withScannedTotp(totp())

        assertEquals("JBSWY3DPEHPK3PXP", scanned.totpSecret)
        assertEquals("GitHub", scanned.totpIssuer)
        assertEquals("convert@example.test", scanned.totpAccount)
        assertEquals("6", scanned.totpDigits)
        assertEquals("30", scanned.totpPeriodSec)
    }

    @Test
    fun `the standalone 2FA wizard still produces a 2FA item`() {
        val wizard = ItemEditUiState(type = ItemEditUiState.ItemType.TOTP)

        assertEquals(ItemEditUiState.ItemType.TOTP, wizard.withScannedTotp(totp()).type)
    }
}
