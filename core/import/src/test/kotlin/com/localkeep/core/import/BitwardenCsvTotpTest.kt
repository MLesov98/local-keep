package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import com.localkeep.core.model.Login
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * A Bitwarden CSV keeps its 2FA seeds.
 *
 * Bitwarden is a named, supported source, and its CSV export prefixes the login columns:
 * `login_uri`, `login_username`, `login_password`, `login_totp`. Three of the four were in the
 * synonym table and `login_totp` was not, so every seed in a Bitwarden CSV was dropped — with no
 * skip entry, and without even the HIDDEN-custom-field rescue, which only fires for a column the
 * mapper recognised but could not parse. The import reported complete success while the seed
 * existed nowhere in the vault (V11, 2026-09-12).
 *
 * The four columns are tested together, because the bug was precisely that they had drifted apart.
 */
class BitwardenCsvTotpTest {

    private val csv =
        "folder,favorite,type,name,notes,fields,login_uri,login_username,login_password,login_totp\n" +
            ",,login,Example,,,https://example.com,ada@example.com,hunter2,JBSWY3DPEHPK3PXP"

    private fun onlyLogin(): Login =
        when (val parsed = CsvImporter.parse(csv.toByteArray())) {
            is Outcome.Success -> parsed.value.items.single().content as Login
            is Outcome.Failure -> error("expected a successful parse, got: ${parsed.error.message}")
        }

    @Test
    fun `a Bitwarden CSV carries its TOTP seed into the item`() {
        val totp = onlyLogin().totp
        assertNotNull("login_totp must be recognised, or every Bitwarden 2FA seed is lost", totp)
        assertEquals("JBSWY3DPEHPK3PXP", totp!!.secretBase32)
    }

    @Test
    fun `the other three Bitwarden login columns still map`() {
        val login = onlyLogin()
        assertEquals("ada@example.com", login.username)
        assertEquals("hunter2", login.password)
        assertEquals("https://example.com", login.uris.single().uri)
    }
}
