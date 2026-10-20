package com.localkeep.feature.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Rotating with a duress confirmation open must not destroy it, and must not put a password in the
 * instance-state Bundle to achieve that.
 *
 * Measured on device 2026-09-20 (3A-F24): rotating with `Replace the duress vault?` open left a
 * landscape tree containing neither the dialog nor anything typed into the two DURESS PASSWORD
 * wells. The biometric consent dialog survived the same rotation, so this was per-control rather
 * than a screen-wide reset.
 *
 * The filed fix — make the flag `rememberSaveable` — was refused in the first fix wave, correctly:
 * the wells beside it are plain `remember` because a duress password must never be written to disk
 * by `onSaveInstanceState`, so restoring only the boolean brings the dialog back over an emptied
 * form and its Confirm submits an empty `CharArray`. Both halves therefore moved to the ViewModel,
 * which outlives a configuration change and dies with the process — see [DuressFormState].
 *
 * Three properties, and all three are needed. The flags survive rotation; the wells survive with
 * them; and **nothing about either is saveable**, which is the one that keeps the first two honest.
 */
class DuressDialogsSurviveRotationTest {

    /**
     * The screen with its comments removed. Load-bearing here: the comment beside the form explains
     * at length why it is not `rememberSaveable`, so a raw-text search for that word would read the
     * rationale as the defect.
     */
    private val screen: String =
        File("src/main/kotlin/com/localkeep/feature/settings/SettingsScreen.kt")
            .readLines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    /** The duress row alone: from its title constant to the Panic row that follows it. */
    private val duressRow: String =
        screen.substringAfter("stringResource(SettingsFocus.DURESS)")
            .substringBefore("R.string.settings_panic_title")

    @Test
    fun `the row slice is bounded`() {
        assertTrue(
            duressRow.isNotEmpty() && duressRow.length < screen.length,
            "the duress row slice is unbounded, so every assertion here is measuring the whole " +
                "screen and cannot fail; length=${duressRow.length} of ${screen.length}",
        )
        assertTrue(
            duressRow.contains("R.string.settings_duress_setup_button"),
            "the slice does not contain the form it is named for, so it is not the region this " +
                "test believes it is reading",
        )
    }

    /**
     * A configuration change rebuilds the composable and keeps the ViewModel, so state that has to
     * survive one and only one of those belongs on the ViewModel. This is the whole fix.
     */
    @Test
    fun `a fresh form is empty and closed`() {
        val form = DuressFormState()
        assertEquals("", form.password)
        assertEquals("", form.confirmPassword)
        assertEquals("", form.gatePassword)
        assertFalse(form.confirmSetup, "a form must never start with a confirmation already open")
        assertFalse(form.confirmRemoval)
    }

    @Test
    fun `neither confirmation is held in composition`() {
        assertFalse(
            duressRow.contains("var confirmDuressSetup") ||
                duressRow.contains("var confirmDuressRemoval"),
            "a dialog flag still lives in the composable, so a configuration change destroys it " +
                "or restores it apart from the password it acts on. Row: $duressRow",
        )
        assertTrue(
            duressRow.contains("duressForm.confirmSetup") &&
                duressRow.contains("duressForm.confirmRemoval"),
            "both confirmations must be rendered from the ViewModel-held form. Row: $duressRow",
        )
    }

    @Test
    fun `no well is held in composition either`() {
        assertFalse(
            screen.contains("var duressPassword") ||
                screen.contains("var confirmDuressPassword") ||
                screen.contains("var duressGatePassword"),
            "a duress well still lives in the composable, so the dialog comes back over an " +
                "emptied form and its Confirm submits an empty CharArray",
        )
        assertTrue(
            duressRow.contains("duressForm.password") &&
                duressRow.contains("duressForm.gatePassword"),
            "the wells must be rendered from the ViewModel-held form. Row: $duressRow",
        )
    }

    /**
     * The control on the two above. Surviving rotation is easy to get by making everything
     * `rememberSaveable`, and that writes a duress password and a vault password into the Bundle
     * the system persists to disk.
     */
    @Test
    fun `no duress secret is made saveable`() {
        val declaration = screen.substringAfter("data class DuressFormState", "")
        assertEquals(
            "",
            declaration,
            "DuressFormState is declared in the screen rather than the ViewModel; it is the " +
                "ViewModel's lifetime that this fix depends on",
        )
        assertFalse(
            duressRow.contains("rememberSaveable"),
            "something in the duress row is saveable. Nothing here may be: the row holds three " +
                "password wells, and instance state is written to disk. Row: $duressRow",
        )
    }
}
