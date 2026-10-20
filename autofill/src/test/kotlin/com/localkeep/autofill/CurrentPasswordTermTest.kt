package com.localkeep.autofill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The term matching behind `FieldDetector.isCurrentPassword`, which tells a change-password form's
 * Current box from its New and Confirm boxes (2026-10-02).
 *
 * Nothing drew that line before, so "Generate password" filled the Current box with the new
 * password too and the site refused the change as "current password incorrect". The node-level
 * function needs a `ViewNode`; this is the part of it that decides, and it is pure.
 */
class CurrentPasswordTermTest {

    @Test
    fun `matches the declarations and ids a Current box carries`() {
        // The WHATWG token, the usual id spellings, a joined-up id, and a visible label.
        assertTrue(FieldDetector.isCurrentPasswordTerm("current-password"))
        assertTrue(FieldDetector.isCurrentPasswordTerm("currentPassword"))
        assertTrue(FieldDetector.isCurrentPasswordTerm("et_old_password"))
        assertTrue(FieldDetector.isCurrentPasswordTerm("oldpassword"))
        assertTrue(FieldDetector.isCurrentPasswordTerm("existing_pwd"))
        assertTrue(FieldDetector.isCurrentPasswordTerm("Current password"))
        assertTrue(FieldDetector.isCurrentPasswordTerm("passwordOld"))
    }

    @Test
    fun `does not match the New and Confirm boxes or a plain password box`() {
        assertFalse(FieldDetector.isCurrentPasswordTerm("new-password"))
        assertFalse(FieldDetector.isCurrentPasswordTerm("confirm_password"))
        assertFalse(FieldDetector.isCurrentPasswordTerm("password"))
        assertFalse(FieldDetector.isCurrentPasswordTerm(""))
    }

    @Test
    fun `does not match words that merely contain old`() {
        // Whole words for the short qualifiers: "hold", "folder" and "placeholder" contain "old".
        assertFalse(FieldDetector.isCurrentPasswordTerm("Password (hold to reveal)"))
        assertFalse(FieldDetector.isCurrentPasswordTerm("folder_password"))
        assertFalse(FieldDetector.isCurrentPasswordTerm("password_placeholder"))
    }
}
