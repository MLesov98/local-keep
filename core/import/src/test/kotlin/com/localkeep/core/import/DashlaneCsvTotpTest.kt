package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import com.localkeep.core.model.Login
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A Dashlane CSV keeps its 2FA seeds.
 *
 * The sibling of [BitwardenCsvTotpTest], and the same defect one alias over. `TOTP_HEADERS` carried
 * the underscored `otp_secret`, but Dashlane's real export column is the camelCase `otpSecret`, and
 * `normalize` only lowercased and replaced spaces — so it produced `otpsecret`, matched nothing, and
 * `totpCol` stayed null. Every Dashlane row's seed was then read as the empty string and dropped:
 * no skip entry, and not even the HIDDEN-custom-field rescue, which fires only for a column the
 * mapper recognised but could not parse. A device pass on 2026-09-11 imported a `dashlane.csv` with this exact header and
 * recorded its TOTP arriving only from the LastPass/Safari rows — the symptom was seen and never
 * filed (2-2, 2026-09-13).
 *
 * The header is Dashlane's own, verbatim, rather than a minimal reproduction: the bug was a column
 * name, so the test is worth only as much as the name it uses.
 */
class DashlaneCsvTotpTest {

    private val header = "username,username2,username3,title,password,note,url,category,otpSecret"

    private val csv = header + "\n" +
        "ada@example.com,,,Example,hunter2,,https://example.com,,JBSWY3DPEHPK3PXP"

    private fun onlyLogin(): Login =
        when (val parsed = CsvImporter.parse(csv.toByteArray())) {
            is Outcome.Success -> parsed.value.items.single().content as Login
            is Outcome.Failure -> error("expected a successful parse, got: ${parsed.error.message}")
        }

    @Test
    fun `the camelCase TOTP column is detected at all`() {
        val mapping = CsvColumnMapper.autoDetect(header.split(","))
        assertNotNull(mapping.totpCol, "otpSecret must map to a column, or the seed is never even read")
        assertEquals(8, mapping.totpCol)
    }

    @Test
    fun `a Dashlane CSV carries its TOTP seed into the item`() {
        val totp = onlyLogin().totp
        assertNotNull(totp, "otpSecret must be recognised, or every Dashlane 2FA seed is lost")
        assertEquals("JBSWY3DPEHPK3PXP", totp.secretBase32)
    }

    /** The rest of Dashlane's columns, so a fix that finds the seed cannot mislay anything else. */
    @Test
    fun `the other Dashlane columns still map`() {
        val login = onlyLogin()
        assertEquals("ada@example.com", login.username)
        assertEquals("hunter2", login.password)
        assertEquals("https://example.com", login.uris.single().uri)
    }

    /**
     * The control the camelCase split could have broken: Firefox writes `formActionOrigin`, which
     * has lived in `URL_HEADERS` as the run-together `formactionorigin` since the mapper was
     * written. Splitting camelCase *instead of* rather than *as well as* the old form would have
     * bought Dashlane's TOTP column at the price of Firefox's URL column.
     */
    @Test
    fun `Firefox's run-together header still maps to the url column`() {
        // `url` is deliberately absent: Firefox always writes it first, so a header that has both
        // would pass this assertion no matter what happened to `formActionOrigin`.
        val mapping = CsvColumnMapper.autoDetect(
            "formActionOrigin,username,password,httpRealm,guid".split(","),
        )
        assertEquals(0, mapping.urlCol)
        assertEquals(1, mapping.usernameCol)
        assertEquals(2, mapping.passwordCol)
    }
}
