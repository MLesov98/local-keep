package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.Login
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A TOTP column whose value cannot become a live code is rescued as a hidden custom field — and
 * until 2026-09-20 that was all: neither the preview nor the Done step said it had happened, so an
 * import that quietly downgraded a working second factor into a field the user must move by hand
 * reported full success (1B-F5).
 *
 * `EXAMPLESECRET` is the real-world case: 13 Base32 symbols whose last symbol leaves a non-zero
 * trailing bit, which `TotpFieldParser` refuses because the generator would throw on it.
 */
class CsvUnusableTotpReportedTest {

    private fun parse(csv: String): ParsedImport {
        val result = CsvImporter.parse(csv.toByteArray(Charsets.UTF_8), ImportDefaultTitles())
        assertTrue(result is Outcome.Success, "fixture must parse: $result")
        return result.value
    }

    @Test
    fun `a seed that cannot generate a code is counted, not only rescued`() {
        val parsed = parse(
            """
            Title,URL,Username,Password,OTPAuth
            Apple,https://apple.example,ada,pw,otpauth://totp/test?secret=examplesecret
            """.trimIndent(),
        )

        val item = parsed.items.single()
        assertEquals(null, (item.content as Login).totp, "this seed cannot become a live code")
        assertEquals(CustomFieldType.HIDDEN, item.customFields.single().type, "it is still rescued")
        assertEquals(1, parsed.unusableTotpCount, "and now it is reported")
    }

    /** A seed that works is not reported: the count is about what the user still has to do. */
    @Test
    fun `a usable seed is not counted`() {
        val parsed = parse(
            """
            Title,URL,Username,Password,OTPAuth
            Bank,https://bank.example,ada,pw,otpauth://totp/test?secret=JBSWY3DPEHPK3PXP
            """.trimIndent(),
        )

        assertTrue((parsed.items.single().content as Login).totp != null, "this seed is decodable")
        assertEquals(0, parsed.unusableTotpCount)
    }

    /** And a file with no TOTP column at all reports nothing. */
    @Test
    fun `a file with no 2FA column counts none`() {
        val parsed = parse(
            """
            name,url,username,password
            Bank,https://bank.example,ada,pw
            """.trimIndent(),
        )

        assertEquals(0, parsed.unusableTotpCount)
    }
}
