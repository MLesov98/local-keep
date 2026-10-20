package com.localkeep.autofill

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The same caller is named the same way on both screens.
 *
 * The picker resolved the app's label; the save confirmation did not. So the same app was "LocalKeep
 * Autofill Test" when it offered a credential and "com.localkeep.autofilltest" when it asked to
 * store one — and the item the save created carried the package name as its title, which is what
 * the user reads in their vault from then on. The picker's own KDoc had the rule written down:
 * "The package name is the last resort rather than the first, because it is the one form of this
 * that a user cannot check against what is on their screen." Found by the F15 QA persona,
 * 2026-08-30.
 *
 * The platform lookup is a parameter, so the ordering that carries all of that is testable without
 * a PackageManager.
 */
class CallerLabelTest {

    private val label: (String) -> String? = { pkg ->
        if (pkg == "com.localkeep.autofilltest") "LocalKeep Autofill Test" else null
    }

    @Test
    fun `a site is named by its domain`() {
        assertEquals(
            "example.com",
            callerLabel("example.com", "com.android.chrome", "fallback", label),
        )
    }

    @Test
    fun `an app is named by its label, not its package`() {
        assertEquals(
            "LocalKeep Autofill Test",
            callerLabel(null, "com.localkeep.autofilltest", "fallback", label),
        )
    }

    /** The last resort, and only that: a package we cannot resolve is still better than nothing. */
    @Test
    fun `an unresolvable package falls back to the package name`() {
        assertEquals("com.unknown.app", callerLabel(null, "com.unknown.app", "fallback", label))
    }

    @Test
    fun `a caller with neither gets the fallback`() {
        assertEquals("fallback", callerLabel(null, null, "fallback", label))
    }

    /**
     * Blanks are not names. An empty domain used to win over a perfectly good package label purely
     * by being non-null, which is the shape of bug that makes a heading read as a stray colon.
     */
    @Test
    fun `blank values are skipped rather than shown`() {
        assertEquals(
            "LocalKeep Autofill Test",
            callerLabel("   ", "com.localkeep.autofilltest", "fallback", label),
        )
        assertEquals("fallback", callerLabel(null, "  ", "fallback", label))
        assertEquals(
            "com.localkeep.autofilltest",
            callerLabel(null, "com.localkeep.autofilltest", "fallback") { "" },
        )
    }
}
