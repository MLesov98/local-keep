package com.localkeep.autofill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The term matching behind `FieldDetector.isNewPasswordForm`, which is what tells a sign-up form
 * from a sign-in one and so decides whether the generated-password suggestion appears.
 *
 * The whole-structure function needs an `AssistStructure` and therefore an instrumented
 * environment; this is the part of it that decides, and it is pure.
 */
class NewPasswordTermTest {

    @Test
    fun `matches the three spellings of the same declaration`() {
        // The WHATWG autocomplete token, Android's AUTOFILL_HINT_NEW_PASSWORD, and the id spelling
        // an ordinary form uses. Separators are stripped, so one set entry covers all three.
        assertTrue(FieldDetector.isNewPasswordTerm("new-password"))
        assertTrue(FieldDetector.isNewPasswordTerm("newPassword"))
        assertTrue(FieldDetector.isNewPasswordTerm("new_password"))
    }

    @Test
    fun `matches the confirmation box a sign-up form asks for`() {
        assertTrue(FieldDetector.isNewPasswordTerm("confirm_password"))
        assertTrue(FieldDetector.isNewPasswordTerm("passwordConfirm"))
        assertTrue(FieldDetector.isNewPasswordTerm("retype-password"))
        assertTrue(FieldDetector.isNewPasswordTerm("password2"))
    }

    @Test
    fun `does not match an ordinary sign-in password field`() {
        // The whole point of the qualifiers: the bare word is on every sign-in form there is, and
        // matching it would put a generator offer above the credential the user came back for.
        assertFalse(FieldDetector.isNewPasswordTerm("password"))
        assertFalse(FieldDetector.isNewPasswordTerm("current-password"))
        assertFalse(FieldDetector.isNewPasswordTerm("login_password"))
        assertFalse(FieldDetector.isNewPasswordTerm("passwd"))
    }

    @Test
    fun `does not match unrelated fields`() {
        assertFalse(FieldDetector.isNewPasswordTerm(""))
        assertFalse(FieldDetector.isNewPasswordTerm("username"))
        assertFalse(FieldDetector.isNewPasswordTerm("cc-number"))
        assertFalse(FieldDetector.isNewPasswordTerm("one-time-code"))
    }
}
