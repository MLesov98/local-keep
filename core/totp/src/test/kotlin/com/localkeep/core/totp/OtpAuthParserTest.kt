package com.localkeep.core.totp

import com.localkeep.core.common.Outcome
import com.localkeep.core.model.Totp
import com.localkeep.core.model.TotpAlgorithm
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OtpAuthParserTest {
    @Test
    fun `standard URI parses defaults and label`() {
        val parsed = success(
            "otpauth://totp/ACME:alice%40example.com" +
                "?secret=JBSWY3DPEHPK3PXP&issuer=ACME",
        )

        assertEquals("ACME", parsed.issuer)
        assertEquals("alice@example.com", parsed.account)
        assertEquals("JBSWY3DPEHPK3PXP", parsed.secretBase32)
        assertEquals(TotpAlgorithm.SHA1, parsed.algorithm)
        assertEquals(6, parsed.digits)
        assertEquals(30, parsed.periodSec)
    }

    @Test
    fun `explicit algorithm digits period and spaced secret parse`() {
        val parsed = success(
            "otpauth://totp/user?secret=JBSW%20Y3DP%20EHPK%203PXP" +
                "&issuer=Example&algorithm=sha512&digits=8&period=60",
        )

        assertEquals("Example", parsed.issuer)
        assertEquals("user", parsed.account)
        assertEquals("JBSWY3DPEHPK3PXP", parsed.secretBase32)
        assertEquals(TotpAlgorithm.SHA512, parsed.algorithm)
        assertEquals(8, parsed.digits)
        assertEquals(60, parsed.periodSec)
    }

    /**
     * 4B-5, 2026-09-20: the link that is the whole reason Steam mode exists was parsed into a plain
     * six-digit token. Nothing on screen said so — the editor simply showed `Steam Guard codes,
     * disabled` — so the user walked away with an entry that produces codes Steam will never accept.
     * The exact URI from `results-4B.md` § 4B.1b-4.
     */
    @Test
    fun `encoder=steam is honoured, not dropped`() {
        val parsed = success("otpauth://totp/steam:acct?secret=JBSWY3DPEHPK3PXP&encoder=steam")

        assertTrue(parsed.steam, "encoder=steam must switch the entry into Steam mode")
        assertEquals("steam", parsed.issuer)
        assertEquals("acct", parsed.account)
        assertEquals("JBSWY3DPEHPK3PXP", parsed.secretBase32)
        // What the generator then produces is the five-character Steam alphabet, not six digits.
        val code = TotpGenerator.generate(parsed, Instant.ofEpochSecond(1_789_919_202))
        assertEquals("FQCFJ", code)
    }

    /** The control: without the parameter the same link is an ordinary six-digit TOTP. */
    @Test
    fun `a link with no encoder stays a plain TOTP`() {
        val parsed = success("otpauth://totp/steam:acct?secret=JBSWY3DPEHPK3PXP")

        assertFalse(parsed.steam)
        assertEquals(6, parsed.digits)
    }

    /**
     * An encoder this build cannot render is refused rather than quietly downgraded to RFC 6238 —
     * the silent-drop failure 4B-5 was, one parameter over.
     */
    @Test
    fun `an unknown encoder is refused`() {
        assertIs<Outcome.Failure>(
            OtpAuthParser.parse("otpauth://totp/x:acct?secret=JBSWY3DPEHPK3PXP&encoder=mobius"),
        )
    }

    @Test
    fun `serialization round-trip preserves special label and all parameters`() {
        val original = Totp(
            issuer = "Example Service",
            account = "alice+work@example.com:admin",
            secretBase32 = "jbswy3dpehpk3pxp====",
            algorithm = TotpAlgorithm.SHA256,
            digits = 8,
            periodSec = 45,
        )

        val uri = OtpAuthParser.toUri(original)
        val parsed = success(uri)

        assertEquals(original.copy(secretBase32 = "JBSWY3DPEHPK3PXP"), parsed)
        assertTrue(uri.startsWith("otpauth://totp/"))
    }

    @Test
    fun `serialization omits defaults and supports no issuer`() {
        val original = Totp(
            account = "local",
            secretBase32 = "JBSWY3DPEHPK3PXP",
        )

        val uri = OtpAuthParser.toUri(original)

        assertFalse(uri.contains("issuer="))
        assertFalse(uri.contains("algorithm="))
        assertFalse(uri.contains("digits="))
        assertFalse(uri.contains("period="))
        assertEquals(original, success(uri))
    }

    @Test
    fun `invalid scheme type label and missing secret fail`() {
        listOf(
            "https://totp/user?secret=JBSWY3DPEHPK3PXP",
            "otpauth://hotp/user?secret=JBSWY3DPEHPK3PXP",
            "otpauth://totp/?secret=JBSWY3DPEHPK3PXP",
            "otpauth://totp/user?issuer=Example",
        ).forEach {
            assertIs<Outcome.Failure>(OtpAuthParser.parse(it))
        }
    }

    @Test
    fun `invalid secret algorithm digits and period fail without retaining cause`() {
        val invalid = listOf(
            "otpauth://totp/user?secret=NOT*BASE32",
            "otpauth://totp/user?secret=JBSWY3DPEHPK3PXP&algorithm=MD5",
            "otpauth://totp/user?secret=JBSWY3DPEHPK3PXP&digits=7",
            "otpauth://totp/user?secret=JBSWY3DPEHPK3PXP&period=0",
            "otpauth://totp/user?secret=JBSWY3DPEHPK3PXP&period=999999",
        )

        invalid.forEach {
            val failure = assertIs<Outcome.Failure>(OtpAuthParser.parse(it))
            assertEquals(null, failure.error.cause)
        }
    }

    @Test
    fun `issuer mismatch duplicate parameters and fragments fail`() {
        listOf(
            "otpauth://totp/One:user?secret=JBSWY3DPEHPK3PXP&issuer=Two",
            "otpauth://totp/user?secret=JBSWY3DPEHPK3PXP&secret=JBSWY3DPEHPK3PXP",
            "otpauth://totp/user?secret=JBSWY3DPEHPK3PXP#fragment",
        ).forEach {
            assertIs<Outcome.Failure>(OtpAuthParser.parse(it))
        }
    }

    /**
     * S3-11, 2026-10-02: a generator that spells a space as '+' in both the label and the query
     * (Java's `URLEncoder`, Python's `quote_plus`) was refused as "label and query issuers must
     * match", because the label is a URI path (literal '+') and the query is form-decoded ('+' is
     * a space), so `Example+Corp` was compared with `Example Corp`.
     */
    @Test
    fun `an issuer spelled with plus for the space in label and query is accepted`() {
        val parsed = success(
            "otpauth://totp/Example+Corp:alice?secret=JBSWY3DPEHPK3PXP&issuer=Example+Corp",
        )

        assertEquals("Example Corp", parsed.issuer)
        assertEquals("alice", parsed.account)
    }

    /** The control: relaxing the issuer comparison must not fold '+' in the account. */
    @Test
    fun `a plus in the account stays literal`() {
        val parsed = success(
            "otpauth://totp/Example+Corp:alice+work@example.com" +
                "?secret=JBSWY3DPEHPK3PXP&issuer=Example+Corp",
        )

        assertEquals("alice+work@example.com", parsed.account)
    }

    /** The other control: it is a spelling tolerance, not a licence to accept different issuers. */
    @Test
    fun `an issuer that differs by more than the plus spelling is still refused`() {
        listOf(
            "otpauth://totp/Example+Corp:alice?secret=JBSWY3DPEHPK3PXP&issuer=Example",
            "otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP&issuer=Example+Corp",
        ).forEach {
            assertIs<Outcome.Failure>(OtpAuthParser.parse(it))
        }
    }

    @Test
    fun `unsupported Steam serialization and issuer colon are rejected`() {
        val base = Totp(account = "user", secretBase32 = "JBSWY3DPEHPK3PXP")

        assertFailsWith<IllegalArgumentException> {
            OtpAuthParser.toUri(base.copy(steam = true))
        }
        assertFailsWith<IllegalArgumentException> {
            OtpAuthParser.toUri(base.copy(issuer = "bad:issuer"))
        }
    }

    private fun success(uri: String): Totp =
        assertIs<Outcome.Success<Totp>>(OtpAuthParser.parse(uri)).value
}
