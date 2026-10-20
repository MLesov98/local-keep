package com.localkeep.core.import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TotpFieldParserTest {

    /**
     * The parser used to strip only the space character, so a secret separated any other way
     * failed its own Base32 check and the item arrived with no TOTP and no complaint. Everything
     * downstream — the editor, OtpAuthParser, TotpGenerator's decoder — strips all whitespace, so
     * this was the one link in the chain that rejected a secret the rest would have accepted.
     */
    @Test
    fun aSecretBrokenUpByAnyWhitespaceIsStillRead() {
        val expected = "JBSWY3DPEHPK3PXP"

        listOf(
            "JBSW Y3DP EHPK 3PXP",
            "JBSW\tY3DP\tEHPK\t3PXP",
            "JBSWY3DP\nEHPK3PXP",
            "  jbswy3dp ehpk3pxp  ",
        ).forEach { raw ->
            assertEquals(raw, expected, TotpFieldParser.parse(raw)?.secretBase32)
        }
    }

    /** Same rule inside an otpauth:// URI, where the secret arrives as a query parameter. */
    @Test
    fun theSecretInAnOtpauthUriIsNormalisedTheSameWay() {
        val totp = TotpFieldParser.parse(
            "otpauth://totp/Example:ada@example.com?secret=JBSW%09Y3DP%20EHPK3PXP&issuer=Example",
        )

        assertEquals("JBSWY3DPEHPK3PXP", totp?.secretBase32)
        assertEquals("Example", totp?.issuer)
    }

    /** Stripping whitespace must not start accepting things that are not Base32. */
    @Test
    fun charactersOutsideTheBase32AlphabetAreStillRejected() {
        assertNull(TotpFieldParser.parse("JBSW Y3DP !!!!"))
        assertNull(TotpFieldParser.parse("   "))
        assertNull(TotpFieldParser.parse(""))
    }

    /**
     * This parser used to take `digits`, `period` and `algorithm` at face value while the editor's
     * `OtpAuthParser` rejected the same URI outright. The item imported, looked fine, and could
     * never produce a code: every `TotpGenerator` call site wraps its `require` in `runCatching`, so
     * the failure was silent — an entry with a 2FA field that renders nothing, forever.
     */
    @Test
    fun aUriDescribingATokenThisAppCannotComputeIsRejected() {
        val secret = "JBSWY3DPEHPK3PXP"

        listOf(
            "digits=7" to "otpauth://totp/a?secret=$secret&digits=7",
            "digits=0" to "otpauth://totp/a?secret=$secret&digits=0",
            "period=0" to "otpauth://totp/a?secret=$secret&period=0",
            "period negative" to "otpauth://totp/a?secret=$secret&period=-30",
            "period absurd" to "otpauth://totp/a?secret=$secret&period=86401",
            "digits not a number" to "otpauth://totp/a?secret=$secret&digits=six",
        ).forEach { (name, uri) -> assertNull(name, TotpFieldParser.parse(uri)) }
    }

    /**
     * An unknown algorithm is the worse half of the same defect: it used to fold to SHA1 rather than
     * fail, so the entry imported *and* rendered six confident digits that were wrong.
     */
    @Test
    fun anUnsupportedAlgorithmIsRejectedRatherThanFoldedToSha1() {
        assertNull(TotpFieldParser.parse("otpauth://totp/a?secret=JBSWY3DPEHPK3PXP&algorithm=SHA384"))
        assertNull(TotpFieldParser.parse("otpauth://totp/a?secret=JBSWY3DPEHPK3PXP&algorithm=MD5"))
    }

    /**
     * F15: the bare-secret branch checked alphabet membership but not length. A secret whose
     * un-padded length mod 8 is not one RFC 4648 allows ({0,2,4,5,7}) is one TotpGenerator's own
     * Base32.decode throws on — so it imported as a Totp that could never render a code. Rejected
     * to null now, so the CSV/KDBX paths rescue the raw value into a HIDDEN field instead of losing it.
     */
    @Test
    fun aBareSecretOfAnInvalidBase32LengthIsRejected() {
        // Every character is valid Base32; only the length is wrong, isolating the length rule the
        // old alphabet-only check let through.
        assertNull("length 3", TotpFieldParser.parse("MZX"))
        assertNull("length 6", TotpFieldParser.parse("MZXW6Y"))
    }

    /**
     * F3: the otpauth:// URI branch validated digits/period/algorithm but never the secret at all —
     * not even alphabet membership. A URI carrying a secret that cannot decode produced a silently
     * dead TOTP. The same Base32 rule now guards both branches.
     */
    @Test
    fun aUriSecretThatCannotDecodeIsRejected() {
        // 0, 1, 8 and 9 are not in the Base32 alphabet — the URI branch used to accept them outright.
        assertNull("non-alphabet", TotpFieldParser.parse("otpauth://totp/a?secret=01890189"))
        // Alphabet-valid but an invalid Base32 length.
        assertNull("bad length", TotpFieldParser.parse("otpauth://totp/a?secret=MZX"))
    }

    /**
     * A `+` in an otpauth query stands for itself: the query of a URI is not an HTML form body,
     * and RFC 3986 lists `+` among the sub-delims a query may carry unescaped. `URLDecoder` folds
     * it to a space, so an issuer the user really named with a plus arrived renamed, silently.
     */
    @Test
    fun aLiteralPlusInTheQueryIsNotReadAsASpace() {
        val plusInIssuer = TotpFieldParser.parse(
            "otpauth://totp/ada@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Google+Workspace",
        )
        assertEquals("Google+Workspace", plusInIssuer?.issuer)

        // Control — percent escapes still decode, including %20 for a real space and %2B for a
        // plus. Both held before the fix; only the bare `+` reading changed.
        val escaped = TotpFieldParser.parse(
            "otpauth://totp/ada@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Acme%20Co%2BSons",
        )
        assertEquals("Acme Co+Sons", escaped?.issuer)
    }

    /** The control: everything the app really can compute still imports. */
    @Test
    fun theSupportedCombinationsStillImport() {
        val eightDigit = TotpFieldParser.parse(
            "otpauth://totp/a?secret=JBSWY3DPEHPK3PXP&digits=8&period=60&algorithm=SHA512",
        )
        assertEquals(8, eightDigit?.digits)
        assertEquals(60, eightDigit?.periodSec)

        val plain = TotpFieldParser.parse("otpauth://totp/a?secret=JBSWY3DPEHPK3PXP")
        assertEquals(6, plain?.digits)
        assertEquals(30, plain?.periodSec)
    }
}
