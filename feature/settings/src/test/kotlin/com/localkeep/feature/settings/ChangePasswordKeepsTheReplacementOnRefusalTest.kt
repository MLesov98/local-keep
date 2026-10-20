package com.localkeep.feature.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A refused change-master-password attempt must not empty the new-password wells.
 *
 * The submit handler cleared all three wells before calling the ViewModel, so a mistyped *current*
 * password — the one refusal the form exists to produce, and the one the user is most likely to
 * make — threw away a correct new password and its confirmation as well. Measured on device
 * 2026-09-20 (3A-F29): after `That isn't your current master password.` all three wells read
 * `text=''`.
 *
 * The credential still goes on submit: it is proof of presence, spent by the attempt. Only the
 * replacement survives, and only until the change succeeds.
 *
 * Asserted on the source, the same idiom as [ChangePasswordFormHasRevealAndStrengthTest]:
 * `:feature:settings` declares Robolectric but no Compose UI test dependency, so there is no way to
 * compose this row, type into it and look at it.
 */
class ChangePasswordKeepsTheReplacementOnRefusalTest {

    /** The screen with its comments removed, so prose about a control cannot satisfy a search for it. */
    private val screen: String =
        File("src/main/kotlin/com/localkeep/feature/settings/SettingsScreen.kt")
            .readLines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    /** The submit handler alone: from the submit button's label to the route-switch below it. */
    private val submit: String =
        screen.substringAfter("R.string.settings_password_submit")
            .substringBefore("R.string.settings_password_forgot_cta")

    /** The change-password row alone: from its title to the Recovery Kit row that follows it. */
    private val row: String =
        screen.substringAfter("R.string.settings_password_title")
            .substringBefore("R.string.settings_recovery_kit_title")

    @Test
    fun `the slices are bounded`() {
        assertTrue(
            submit.isNotEmpty() && submit.length < screen.length,
            "the submit slice is unbounded, so every assertion here is measuring the whole " +
                "screen and cannot fail; length=${submit.length} of ${screen.length}",
        )
        assertTrue(
            submit.contains("viewModel.changePassword("),
            "the submit slice does not contain the call it is named for, so it is not the region " +
                "this test believes it is reading",
        )
        assertTrue(
            row.isNotEmpty() && row.length < screen.length,
            "the row slice is unbounded; length=${row.length} of ${screen.length}",
        )
    }

    @Test
    fun `submitting does not empty the new password wells`() {
        assertFalse(
            submit.contains("newPassword = \"\""),
            "the submit handler clears the new-password well, so a wrong current password " +
                "discards a correct replacement the user has already typed twice. Submit: $submit",
        )
        assertFalse(
            submit.contains("confirmPassword = \"\""),
            "the submit handler clears the confirm well, so a refusal costs the user both " +
                "halves of the replacement. Submit: $submit",
        )
    }

    @Test
    fun `submitting still spends the credential`() {
        assertTrue(
            submit.contains("currentPassword = \"\""),
            "the current-password well survives the attempt: the credential is proof of presence " +
                "and must not outlive the call that consumed it. Submit: $submit",
        )
        assertTrue(
            submit.contains("recoveryWords = \"\""),
            "the Recovery Kit well survives the attempt, leaving 24 words that open the vault in " +
                "composition after they have been spent. Submit: $submit",
        )
    }

    @Test
    fun `a change that succeeded clears the replacement`() {
        assertTrue(
            row.contains("SettingsMessage.PasswordChanged"),
            "nothing clears the new-password wells on success, so the password the vault now uses " +
                "stays on screen after the form is done with it. Row: $row",
        )
    }
}
