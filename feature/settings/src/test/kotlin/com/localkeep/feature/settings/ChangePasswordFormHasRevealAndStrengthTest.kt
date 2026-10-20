package com.localkeep.feature.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The change-master-password form must let the user see what they typed, and say how strong it is.
 *
 * Neither was true (device, 2026-09-20): three masked wells with no reveal toggle anywhere — alone
 * among the app's password forms — and a character count where onboarding shows a strength verdict.
 * The two compound: this is the one form where a mistype locks the vault, because the same
 * operation revokes the Recovery Kit, and it would accept a twelve-character password zxcvbn calls
 * weak with nothing said until the refusal, by which point the form has been wiped.
 *
 * Asserted on the source, the same idiom as [DuressRemovalSurvivesEntitlementLossTest]:
 * `:feature:settings` declares Robolectric but no Compose UI test dependency, so there is no way to
 * compose this row and look at it.
 */
class ChangePasswordFormHasRevealAndStrengthTest {

    /** The screen with its comments removed, so prose about a control cannot satisfy a search for it. */
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
    fun `the slice is bounded`() {
        assertTrue(
            row.isNotEmpty() && row.length < screen.length,
            "the change-password row slice is unbounded, so every assertion here is measuring the " +
                "whole screen and cannot fail; length=${row.length} of ${screen.length}",
        )
        assertTrue(
            row.contains("R.string.settings_password_confirm_label"),
            "the slice does not contain the form it is named for, so it is not the region this " +
                "test believes it is reading",
        )
    }

    @Test
    fun `all three wells carry a reveal toggle`() {
        assertEquals(
            3,
            Regex("onToggleReveal =").findAll(row).count(),
            "the form must offer a reveal toggle on the current, new and confirm wells — it is " +
                "the only way to check the replacement for the vault's only key before " +
                "committing to it. Row: $row",
        )
    }

    @Test
    fun `the new password is scored, not just counted`() {
        assertTrue(
            row.contains("LkStrengthMeter(LkStrength.ofScore(newPasswordScore))"),
            "no strength meter under the new-password well: the form enforces the zxcvbn floor on " +
                "submit and said nothing about it while it could still be acted on",
        )
        assertTrue(
            row.contains("StrengthAnalyzer.MIN_MASTER_PASSWORD_SCORE"),
            "the meter is shown but the floor it is judged against is not, so a Reasonable " +
                "password reads as acceptable and is refused",
        )
        assertTrue(
            row.contains("StrengthAnalyzer.score(newPassword)"),
            "the score is not computed from the new password",
        )
        assertTrue(
            row.contains("withContext(Dispatchers.Default)"),
            "zxcvbn is superlinear in length and must not be scored on the frame",
        )
    }
}
