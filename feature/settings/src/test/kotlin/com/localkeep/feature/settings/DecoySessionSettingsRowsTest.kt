package com.localkeep.feature.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A decoy session must not be recognisable by which Settings rows are missing.
 *
 * Measured on device 2026-09-20 (release sweep, 3A-F23): the decoy's SECURITY group held `Auto-lock`
 * · `Password health` · `Panic lock` and nothing else, while the real vault's also carried
 * `Biometric unlock`, `Change master password`, `Recovery Kit` and `Duress vault`; AUTOFILL lost
 * `Trusted apps`. Each row had been withheld for a defensible reason of its own, and together they
 * were a tell a coercer could read by scrolling, with no knowledge of the feature at all.
 *
 * Two of the five are restored, and they are restored by being *made to work* rather than by being
 * shown and left broken — which would be the same tell one screen deeper:
 *
 * - **Change master password** changes the decoy's own password, through
 *   `MasterPasswordChanger.changeDecoyPassword`. Its Recovery-Kit route answers the ordinary
 *   wrong-words refusal, which is true: the container's one recovery block wraps the real key.
 * - **Trusted apps** shows an empty list in a decoy session, which is what a real vault that has
 *   trusted nothing shows. The scoping is in `TrustedAppsViewModel`, not in whether the row exists.
 *
 * The other three were argued separately (2026-09-20) and are not asserted
 * here, because they were not changed.
 *
 * Asserted on the source, the same idiom as [DuressRemovalSurvivesEntitlementLossTest]:
 * `:feature:settings` declares Robolectric but no Compose UI test dependency, so there is no way to
 * compose this list at `isDuressSession = true` and count its rows.
 */
class DecoySessionSettingsRowsTest {

    /** The screen with its comments removed, so prose about a row cannot satisfy a search for it. */
    private val screen: String =
        File("src/main/kotlin/com/localkeep/feature/settings/SettingsScreen.kt")
            .readLines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    private val viewModel: String =
        File("src/main/kotlin/com/localkeep/feature/settings/SettingsViewModel.kt")
            .readLines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

    private val trustedApps: String =
        File("src/main/kotlin/com/localkeep/feature/settings/TrustedAppsViewModel.kt")
            .readLines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

    /** The change-password row alone: from its title to the Password health row that follows it. */
    private val passwordRow: String =
        screen.substringAfter("R.string.settings_password_title")
            .substringBefore("R.string.settings_health_title")

    /** The trusted-apps row alone: from its title to the passkey row that follows it. */
    private val trustedRow: String =
        screen.substringAfter("R.string.settings_trusted_title")
            .substringBefore("R.string.settings_passkey_title")

    @Test
    fun `the slices are bounded`() {
        assertTrue(
            passwordRow.isNotEmpty() && passwordRow.length < screen.length,
            "the change-password slice is unbounded; length=${passwordRow.length} of " +
                "${screen.length}",
        )
        assertTrue(
            passwordRow.contains("R.string.settings_password_submit"),
            "the change-password slice does not contain its own submit button, so it is not the " +
                "region this test believes it is reading",
        )
        assertTrue(
            trustedRow.isNotEmpty() && trustedRow.length < screen.length,
            "the trusted-apps slice is unbounded; length=${trustedRow.length} of ${screen.length}",
        )
        assertTrue(
            trustedRow.contains("onClick = onTrustedAppsClick"),
            "the trusted-apps slice does not contain the row it is named for",
        )
    }

    @Test
    fun `the change-password row is present in a decoy session`() {
        assertFalse(
            passwordRow.contains("isDuressSession"),
            "the change-master-password row is still withheld in a decoy session, which is one " +
                "of the four missing SECURITY rows a decoy could be recognised by. Row: $passwordRow",
        )
    }

    @Test
    fun `the trusted-apps row is present in a decoy session`() {
        assertFalse(
            trustedRow.contains("isDuressSession"),
            "the Trusted apps row is still withheld in a decoy session. Row: $trustedRow",
        )
    }

    /**
     * Present is only half of it. A row that composes and then fails is the same tell one screen
     * deeper, which is exactly why these rows were taken away in the first place.
     */
    @Test
    fun `the decoy session's password change acts on the decoy`() {
        assertTrue(
            viewModel.contains("MasterPasswordChanger.changeDecoyPassword(lockManager"),
            "the decoy session still routes to the real-vault password change, which " +
                "`realSessionSnapshot` refuses with the generic failure message",
        )
        assertTrue(
            viewModel.contains("MasterPasswordChanger.changePassword(lockManager"),
            "the real session's route is gone, which would be the opposite mistake",
        )
    }

    /**
     * `DeviceKeyStore` holds the *real* vault's biometric-wrapped key and is device-global. Reading
     * it to decide what to say after a decoy password change would pull real-vault state onto a
     * decoy screen and append a sentence about a credential the decoy has not disturbed.
     */
    @Test
    fun `the decoy session's password change reads no device-global real-vault state`() {
        assertTrue(
            viewModel.contains("val hadBiometric = !isDuressSession && DeviceKeyStore.load(context)"),
            "the biometric read is not gated on the session, so a decoy password change reports " +
                "on the real vault's biometric enrolment",
        )
    }

    @Test
    fun `a decoy session's trusted-apps list is empty, and read-only`() {
        assertTrue(
            trustedApps.contains("isDuress == false"),
            "TrustedAppsViewModel does not scope its list to a real session, so the decoy screen " +
                "lists the real user's bank, work SSO and mail provider",
        )
        assertEquals(
            2,
            Regex("if \\(isDecoySession\\(\\)\\) return").findAll(trustedApps).count(),
            "both write paths — forgetDomain and forgetApp — must refuse in a decoy session; a " +
                "stale recomposition is a call this ViewModel would otherwise honour",
        )
    }
}
