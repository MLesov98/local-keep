package com.localkeep.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * `FieldDetector.classifyTerms`'s totpTerms/passwordTerms ordering (2026-09-13).
 *
 * `totpTerms` used to carry a bare "token", checked ahead of passwordTerms/usernameTerms in the
 * when-chain, so any id/hint merely *containing* that substring -- a password-reset-token or an
 * invite/download-token field, neither an OTP box -- was classified `FieldType.Totp`. Per
 * `SaveHandler.extract`, which requires both Username and Password non-blank or returns null
 * outright, that did not just mis-tag one field: it silently dropped the entire save prompt for
 * the login.
 */
class FieldClassificationTest {

    @Test
    fun `a csrf or access token field is not classified as an OTP box`() {
        assertNotEquals(FieldType.Totp, FieldDetector.classifyTerms("csrf_token"))
        assertNotEquals(FieldType.Totp, FieldDetector.classifyTerms("access_token"))
    }

    @Test
    fun `a password-reset-token field is classified as Password, not fabricated as Totp`() {
        // The save-side consequence the verifier flagged: this used to win Totp ahead of the
        // password match below it, which drops the whole login save (extract() requires both
        // Username and Password non-blank).
        assertEquals(FieldType.Password, FieldDetector.classifyTerms("password_reset_token"))
    }

    /**
     * 2026-10-02: "pin" and "pass" were substrings, so every one of these was a password field and
     * picking the LocalKeep suggestion wrote the stored password, in plain text, into a checkout's
     * address boxes (and two of them made the form a "new password" form).
     */
    @Test
    fun `checkout and travel fields that merely contain pin or pass are not passwords`() {
        for (raw in listOf(
            "shipping",
            "et_shipping_name ",
            "shipping address-line1",
            "shippingAddress",
            "Start typing your address",
            "shopping_list",
            "Your opinion",
            "passenger_name",
            "passport_number",
            "spinner_country",
        )) {
            assertNotEquals("[$raw]", FieldType.Password, FieldDetector.classifyTerms(raw))
        }
    }

    @Test
    fun `real password and pin fields still are`() {
        for (raw in listOf(
            "pin",
            "PIN",
            "et_pin ",
            "userPin",
            "pin_code",
            "pincode",
            "PINCode",
            "Enter your PIN",
            "password",
            "userPassword",
            "current-password",
            "passcode",
            "passwd",
            "pwd",
            "newpwd",
            "et_pass",
            "Mot de passe",
            "Passwort",
        )) {
            assertEquals("[$raw]", FieldType.Password, FieldDetector.classifyTerms(raw))
        }
    }

    @Test
    fun `a genuine one-time-code field still is`() {
        // Control: tightening the bare "token" term must not regress real OTP detection, which
        // never depended on it -- every entry below was already in totpTerms.
        assertEquals(FieldType.Totp, FieldDetector.classifyTerms("totp_code"))
        assertEquals(FieldType.Totp, FieldDetector.classifyTerms("one-time-code"))
        assertEquals(FieldType.Totp, FieldDetector.classifyTerms("otp"))
    }
}
