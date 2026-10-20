package com.localkeep.autofill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide whether tapping a field produces anything at all.
 *
 * The defect these guard: before [SuggestionPolicy] existed, a form whose site or package matched
 * nothing in the vault got a [android.service.autofill.FillResponse] with no datasets on it, which
 * the platform renders as an empty autofill menu — indistinguishable, to the user who chose this
 * app over the platform's own manager, from the service not running.
 */
class SuggestionPolicyTest {

    @Test
    fun `offers to generate when nothing is stored for this form`() {
        assertTrue(
            SuggestionPolicy.offerGeneratedPassword(
                hasPasswordField = true,
                hasStoredMatch = false,
                isNewPasswordForm = false,
            ),
        )
    }

    @Test
    fun `offers to generate on a new-password form even when a login is already stored`() {
        // Changing a password, and adding a second account on a site already in the vault, are both
        // forms where the stored credential is the wrong answer and the generator is the right one.
        assertTrue(
            SuggestionPolicy.offerGeneratedPassword(
                hasPasswordField = true,
                hasStoredMatch = true,
                isNewPasswordForm = true,
            ),
        )
    }

    @Test
    fun `does not offer to generate above a stored login on an ordinary sign-in form`() {
        // The row the user came for is the credential they already have. A generator offer sitting
        // above it is noise on the one form where the answer is unambiguous.
        assertFalse(
            SuggestionPolicy.offerGeneratedPassword(
                hasPasswordField = true,
                hasStoredMatch = true,
                isNewPasswordForm = false,
            ),
        )
    }

    @Test
    fun `never offers to generate on a form with no password box`() {
        // A username-only first step, or a checkout with card fields alone: there is nowhere to
        // put a generated password, so the row would be a control that cannot act.
        assertFalse(
            SuggestionPolicy.offerGeneratedPassword(
                hasPasswordField = false,
                hasStoredMatch = false,
                isNewPasswordForm = true,
            ),
        )
    }

    @Test
    fun `offers a hand search wherever a credential field exists`() {
        assertTrue(
            SuggestionPolicy.offerVaultSearch(hasUsernameField = true, hasPasswordField = false),
        )
        assertTrue(
            SuggestionPolicy.offerVaultSearch(hasUsernameField = false, hasPasswordField = true),
        )
        assertTrue(
            SuggestionPolicy.offerVaultSearch(hasUsernameField = true, hasPasswordField = true),
        )
    }

    @Test
    fun `does not offer a hand search on a form with no credential field`() {
        // A lone URL box, or a checkout form carrying only card fields, is not somewhere a login
        // belongs — and the picker fills username and password, so it would have nothing to do.
        assertFalse(
            SuggestionPolicy.offerVaultSearch(hasUsernameField = false, hasPasswordField = false),
        )
    }

    @Test
    fun `a locked vault offers no unlock on a field unlocking could not fill`() {
        // 2026-10-02: a lone website box (or a "Security question" box, which reads as one) got
        // "Unlock LocalKeep", and the unlock then closed with nothing filled and nothing said.
        assertFalse(SuggestionPolicy.offerUnlockGate(listOf(FieldType.Url)))
        assertFalse(SuggestionPolicy.offerUnlockGate(emptyList()))
    }

    @Test
    fun `a locked vault still offers unlock wherever a credential or card could be filled`() {
        for (type in listOf(
            FieldType.Username,
            FieldType.Password,
            FieldType.Totp,
            FieldType.CardNumber,
            FieldType.CardSecurityCode,
        )) {
            assertTrue("$type", SuggestionPolicy.offerUnlockGate(listOf(FieldType.Url, type)))
        }
    }
}
