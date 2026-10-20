package com.localkeep.feature.onboarding

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The strength gate on Create is work, and it is not done on the frame.
 *
 * `createVault` ran `StrengthAnalyzer.score` inline on `Dispatchers.Main.immediate`. zxcvbn is not a
 * lookup — it runs every matcher it has across the candidate and then a dynamic-programming search
 * for the cheapest guess sequence — and the call sat *before* the `isWorking` write, so nothing on
 * screen had changed when the freeze started. Every new user got a dead tap on Create, on the one
 * screen nobody can skip, and the same screen offers a suggested passphrase, which makes the long
 * input where zxcvbn is most expensive the recommended path rather than the exotic one.
 *
 * Two files already state the rule this one broke: `scoreStrength` in this same class keeps the live
 * meter off the frame ("zxcvbn's matching is superlinear in length"), and `SettingsViewModel`'s
 * master-password gate says running it inline "put exactly that on the main thread, in the one place
 * that goes to lengths to keep Argon2 off it". Found 2026-09-06.
 *
 * Asserted on the source, and deliberately: `OnboardingViewModel` needs a `Context`, a
 * `VaultLockManager`, a `KeystoreManager` and a `BiometricUnlock` to construct, and what is worth
 * pinning is *where the call runs* — which a dispatcher wrapper states exactly and an observable
 * behaviour does not. Same technique, and the same reason, as `RecoveryKitAuthGateTest`.
 */
class CreateVaultStrengthGateTest {

    private val createVault: String = File(
        "src/main/kotlin/com/localkeep/feature/onboarding/OnboardingViewModel.kt",
    ).readText()
        .substringAfter("fun createVault() {")
        .substringBefore("\n    private fun biometricPossible()")

    @Test
    fun `the gate scores off the main thread`() {
        val scoring = createVault.lines().filter { "StrengthAnalyzer.score(" in it }
        assertTrue(scoring.isNotEmpty(), "createVault no longer scores at all — read this test's KDoc.")
        scoring.forEach { line ->
            assertTrue(
                "withContext(Dispatchers.Default)" in line,
                "createVault must measure strength off the frame, the way the live meter and " +
                    "SettingsViewModel's password gate already do. Found: ${line.trim()}",
            )
        }
    }

    @Test
    fun `the gate shows as work before it starts measuring`() {
        val working = createVault.indexOf("isWorking = true")
        val scoring = createVault.indexOf("StrengthAnalyzer.score(")
        assertTrue(
            working in 0 until scoring,
            "isWorking must be set before the measurement, or the tap is dead: nothing on screen " +
                "changes for the length of a zxcvbn run. isWorking at $working, score at $scoring.",
        )
    }
}
