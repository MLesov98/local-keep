package com.localkeep.autofill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SaveHandler.ExtractedSave`'s `toString()` (2026-09-13).
 *
 * `ExtractedSave` -- the intermediate holder for a credential just captured off another app's
 * login form -- was a plain data class with no `toString()` override, unlike
 * `PendingSaveStore.Entry`/`Login`/`Totp`, which all redact for exactly this reason: printing the
 * object anywhere in the autofill service process (a log line, a crash report) would render the
 * captured plaintext password verbatim. `ExtractedSave` is private, so reflection is the smallest
 * way to reach it without inventing a fake `SaveRequest`/`AssistStructure`.
 */
class ExtractedSaveRedactionTest {

    private fun newExtractedSave(username: String, password: String): Any {
        val clazz = Class.forName("com.localkeep.autofill.SaveHandler\$ExtractedSave")
        // Five since 2026-10-02: the change-password form's Current box travels with the save.
        val ctor = clazz.getDeclaredConstructor(
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(username, password, "example.com", null, "old-secret-value")
    }

    @Test
    fun `toString hides the captured password`() {
        val extracted = newExtractedSave("alice", "hunter2-super-secret")

        assertFalse(
            "the captured password leaked through toString()",
            extracted.toString().contains("hunter2-super-secret"),
        )
        assertFalse(
            "the captured current password leaked through toString()",
            extracted.toString().contains("old-secret-value"),
        )
    }

    @Test
    fun `toString matches the redacted shape every other secret holder uses`() {
        // checkNoHardcodedUiStrings' redactedToString regex (build.gradle.kts) requires exactly
        // this shape: a single identifier followed by "(redacted)". Exact equality here also
        // covers the username, which must not leak either.
        val extracted = newExtractedSave("alice", "hunter2-super-secret")

        assertTrue(extracted.toString() == "ExtractedSave(redacted)")
    }
}
