package com.localkeep.feature.onboarding

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the first three onboarding screens promise, held to what the app actually does.
 *
 * Every assertion here corresponds to a sentence that shipped wrong. None of them could be written
 * before the strings were pulled out of their composables, which is the reason they were pulled
 * out: inline copy is copy no test can reach, and this project has now removed the same false
 * claim from six separate screens across four waves.
 *
 * The three bodies moved from Kotlin `const val`s to `res/values/strings.xml` (i18n extraction,
 * 2026-07-30) — see `onboarding_welcome_body`, `onboarding_password_recovery_kit_promise` and
 * `onboarding_migrate_body`. This is a pure JVM `kotlin.test`, with no Compose/Robolectric runtime
 * to call `stringResource` from, so it reads the base (English) resource file directly, the same
 * way `UnlockSubtitleTest` does. It guards English only — see that test's doc comment for why the
 * translations are covered by the review the security-copy protocol requires instead.
 */
class OnboardingCopyTest {

    private val strings: String = File("src/main/res/values/strings.xml").readText()

    private fun stringResource(name: String): String {
        val match = Regex("""<string name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(strings)
            ?: error("$name not found in strings.xml")
        return match.groupValues[1]
    }

    private val WELCOME_BODY: String = stringResource("onboarding_welcome_body")
    private val RECOVERY_KIT_PROMISE: String = stringResource("onboarding_password_recovery_kit_promise")
    private val MIGRATE_BODY: String = stringResource("onboarding_migrate_body")

    /**
     * The welcome body used to say passwords "stay here". Android Auto Backup carries `vault.enc`
     * when the user asks it to, so no screen may state the vault's location categorically.
     */
    @Test
    fun theWelcomeBodyDoesNotClaimNothingLeavesTheDevice() {
        assertTrue("stay here" !in WELCOME_BODY, "said: $WELCOME_BODY")
        assertTrue("never leaves" !in WELCOME_BODY, "said: $WELCOME_BODY")
        assertTrue("device only" !in WELCOME_BODY, "said: $WELCOME_BODY")
        assertTrue("Nothing leaves" !in WELCOME_BODY, "said: $WELCOME_BODY")
    }

    /**
     * The claims that survived the edit are the true ones, and they carry the reassurance the
     * screen exists to give. A fix that left the sentence accurate but empty would pass the test
     * above and fail the user.
     */
    @Test
    fun theWelcomeBodyStillSaysWhatIsActuallyTrue() {
        assertTrue("no account" in WELCOME_BODY, "said: $WELCOME_BODY")
        assertTrue("no sync server" in WELCOME_BODY, "said: $WELCOME_BODY")
        assertTrue("encrypted" in WELCOME_BODY, "said: $WELCOME_BODY")
    }

    /**
     * The password screen may not say the Recovery Kit is *next*. On any device with a lock and a
     * strong sensor, `createVault` routes to biometric enrolment first — which is most phones, so
     * the promise was wrong in the common case and right only in the fallback.
     */
    @Test
    fun theRecoveryKitPromiseDoesNotClaimToBeTheNextStep() {
        assertTrue("Next" !in RECOVERY_KIT_PROMISE, "said: $RECOVERY_KIT_PROMISE")
        assertTrue("next" !in RECOVERY_KIT_PROMISE, "said: $RECOVERY_KIT_PROMISE")
    }

    /** It still has to promise the kit, and say why it matters. That was never the broken part. */
    @Test
    fun theRecoveryKitPromiseStillPromisesTheKit() {
        assertTrue("24-word" in RECOVERY_KIT_PROMISE, "said: $RECOVERY_KIT_PROMISE")
        assertTrue("Recovery Kit" in RECOVERY_KIT_PROMISE, "said: $RECOVERY_KIT_PROMISE")
    }

    /**
     * Every manager named on the migration screen must be reachable from the wizard.
     *
     * Firefox and LastPass are named deliberately and are not an error: `CsvImporter` detects both
     * by header row. An audit read them as unsupported because they have no dedicated tile and
     * proposed deleting them, which would have removed two working paths from the copy. The
     * sentence has to name the CSV route in the same breath, or that reading is a fair one.
     */
    @Test
    fun theMigrationBodyRoutesFirefoxAndLastPassThroughCsv() {
        val firefoxAt = MIGRATE_BODY.indexOf("Firefox")
        val csvAt = MIGRATE_BODY.indexOf("CSV")

        assertTrue(firefoxAt >= 0, "Firefox is supported via CSV and should be named: $MIGRATE_BODY")
        assertTrue(csvAt >= 0, "the CSV route must be named: $MIGRATE_BODY")
        assertTrue(
            csvAt < firefoxAt,
            "Firefox is named before the CSV route that serves it, so it reads as a missing " +
                "tile: $MIGRATE_BODY",
        )
    }

    /** The three sources that do have their own tile and went unmentioned. */
    @Test
    fun theMigrationBodyNamesTheSourcesThatHaveTheirOwnTile() {
        for (source in listOf("Apple Passwords", "KeePass", "Aegis", "Bitwarden", "1Password")) {
            assertTrue(source in MIGRATE_BODY, "'$source' has a tile but is unnamed: $MIGRATE_BODY")
        }
    }

    /**
     * D6: `onboarding_password_error_mismatch` ("Passwords do not match") lacked a terminal period
     * while the identical-meaning `onboarding_password_mismatch` / `settings_password_mismatch`
     * ("These don't match yet.") ship with one in both modules — an inconsistency between two
     * mismatch messages inside this same file. Fails without the fix because the un-punctuated
     * source string ends in "match</string>", not "match.</string>".
     */
    @Test
    fun thePasswordErrorMismatchStringEndsWithAPeriod() {
        val mismatchError = stringResource("onboarding_password_error_mismatch")
        assertTrue(
            mismatchError.trim().endsWith("."),
            "onboarding_password_error_mismatch should end with a period, to match the sibling " +
                "mismatch string's convention: $mismatchError",
        )
    }
}
