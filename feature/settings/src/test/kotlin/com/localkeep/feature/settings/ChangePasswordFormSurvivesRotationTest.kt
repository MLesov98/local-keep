package com.localkeep.feature.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Rotating the phone must not empty the change-master-password form, and must not put a password
 * in the instance-state Bundle to achieve that (S5-08, 2026-10-02).
 *
 * The three wells, the "forgot" mode and the Recovery Kit words were plain `remember`, so a
 * rotation — or a font-scale, language or split-screen change; `MainActivity` declares no
 * `android:configChanges` — emptied all of them under a row that stayed open, since the expanded
 * set is `rememberSaveable`. The duress form beside it was moved into the ViewModel for exactly
 * this on 2026-09-20 (3A-F24); this form was not.
 *
 * Asserted on the source, the idiom [DuressDialogsSurviveRotationTest] uses for the same fix:
 * `:feature:settings` has no Compose UI test dependency, so the row cannot be composed, typed into
 * and rotated here.
 */
class ChangePasswordFormSurvivesRotationTest {

    /** The screen with its comments removed, so prose about a control cannot satisfy a search. */
    private val screen: String =
        File("src/main/kotlin/com/localkeep/feature/settings/SettingsScreen.kt")
            .readLines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    /** The change-password row alone: from its title to the Recovery Kit row that follows it. */
    private val row: String =
        screen.substringAfter("R.string.settings_password_title")
            .substringBefore("R.string.settings_recovery_kit_title")

    @Test
    fun `the row slice is bounded`() {
        assertTrue(
            row.isNotEmpty() && row.length < screen.length && "R.string.settings_password_submit" in row,
            "the change-password row slice is not the region this test believes it is reading",
        )
    }

    @Test
    fun `no well is held in composition`() {
        listOf(
            "var currentPassword",
            "var newPassword",
            "var confirmPassword",
            "var usingRecoveryKit",
            "var recoveryWords",
        ).forEach { declaration ->
            assertFalse(
                declaration in screen,
                "`$declaration` still lives in the composable, so a configuration change empties it",
            )
        }
        assertTrue(
            "viewModel.passwordForm" in screen,
            "the form must be read back from the ViewModel that holds it",
        )
    }

    @Test
    fun `every well writes through the ViewModel`() {
        listOf("currentPassword", "newPassword", "confirmPassword", "recoveryWords").forEach { field ->
            assertTrue(
                "viewModel.updatePasswordForm(passwordForm.copy($field = it))" in row,
                "the $field well does not write to the ViewModel-held form. Row: $row",
            )
        }
    }

    /** The control. Surviving rotation is trivially had with `rememberSaveable`, which writes the
     *  vault's master password and the Recovery Kit words to disk. */
    @Test
    fun `nothing in the row is made saveable`() {
        assertFalse(
            "rememberSaveable" in row,
            "something in the change-password row is saveable, and instance state is written to " +
                "disk. Row: $row",
        )
    }

    @Test
    fun `a fresh form is empty and in password mode`() {
        val form = PasswordFormState()
        assertEquals("", form.currentPassword)
        assertEquals("", form.newPassword)
        assertEquals("", form.confirmPassword)
        assertEquals("", form.recoveryWords)
        assertFalse(form.usingRecoveryKit)
    }
}
